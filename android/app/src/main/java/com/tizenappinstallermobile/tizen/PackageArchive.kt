package com.tizenappinstallermobile.tizen

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

data class PackageInfo(val packageId: String, val appId: String, val version: String, val name: String, val kind: String)
data class SigningIdentity(val key: PrivateKey, val chain: List<X509Certificate>) {
    val fingerprint: String get() = sha256(chain.first().encoded)
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

object PackageArchive {
    const val MAX_BYTES = 512L * 1024 * 1024
    const val DS = "http://www.w3.org/2000/09/xmldsig#"
    private const val DSP = "http://www.w3.org/2009/xmldsig-properties"
    private const val C14N = "http://www.w3.org/2001/10/xml-exc-c14n#"
    private const val C14N11 = "http://www.w3.org/2006/12/xml-c14n11"
    private const val SHA512 = "http://www.w3.org/2001/04/xmlenc#sha512"
    private const val RSA512 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512"

    fun xml(bytes: ByteArray): Document {
        require(bytes.size <= 1024 * 1024 && bytes.none { it == 0.toByte() }) { "XML must be UTF-8 and smaller than 1 MiB." }
        val text = bytes.toString(Charsets.UTF_8)
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "External entities and DTDs are not allowed." }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    fun inspect(file: File): PackageInfo = ZipFile(file).use { zip ->
        validateEntries(zip)
        val web = zip.getEntry("config.xml")
        val entry = web ?: zip.getEntry("tizen-manifest.xml") ?: error("Package has no config.xml or tizen-manifest.xml.")
        val doc = xml(zip.getInputStream(entry).use { bounded(it, 1024 * 1024) })
        if (web != null) {
            val app = doc.getElementsByTagNameNS("*", "application").item(0) as? Element
                ?: error("Widget has no Tizen application identity.")
            val appId = app.getAttribute("id")
            val packageId = app.getAttribute("package").ifBlank { appId.substringBeforeLast('.', "") }
            validId(packageId)
            validId(appId)
            PackageInfo(packageId, appId, doc.documentElement.getAttribute("version"),
                doc.getElementsByTagNameNS("*", "name").item(0)?.textContent?.trim().orEmpty(), "wgt")
        } else {
            val packageId = doc.documentElement.getAttribute("package")
            validId(packageId)
            val application = doc.getElementsByTagNameNS("*", "ui-application").item(0) as? Element
            val appId = application?.getAttribute("appid").orEmpty().ifBlank { packageId }
            validId(appId)
            PackageInfo(packageId, appId, doc.documentElement.getAttribute("version"), packageId, "tpk")
        }
    }

    fun validId(id: String) {
        require(Regex("[A-Za-z0-9_.-]{1,128}").matches(id)) { "Invalid Tizen package/application ID." }
    }

    fun contentFingerprint(file: File, checkCancelled: () -> Unit = {}): String = ZipFile(file).use { zip ->
        validateEntries(zip)
        val references = zip.entries().asSequence().filter { !it.isDirectory && it.name != "author-signature.xml" &&
            !Regex("signature[0-9]+\\.xml").matches(it.name) }.map { entry ->
            checkCancelled()
            val hash = MessageDigest.getInstance("SHA-512")
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= entry.size) { "Invalid archive entry size." }
                    hash.update(buffer, 0, count)
                }
            }
            entry.name to Base64.getEncoder().encodeToString(hash.digest())
        }.toList()
        fingerprintReferences(references)
    }

    fun signatureContentFingerprint(bytes: ByteArray): String? {
        val doc = xml(bytes)
        val refs = doc.getElementsByTagNameNS(DS, "Reference")
        val entries = mutableListOf<Pair<String, String>>()
        for (i in 0 until refs.length) {
            val ref = refs.item(i) as Element
            val uri = ref.getAttribute("URI")
            if (uri.startsWith('#') || uri.isBlank()) continue
            val algorithm = ref.getElementsByTagNameNS(DS, "DigestMethod").item(0) as? Element ?: return null
            if (algorithm.getAttribute("Algorithm") != SHA512) return null
            val digest = ref.getElementsByTagNameNS(DS, "DigestValue").item(0)?.textContent?.replace(Regex("\\s"), "") ?: return null
            val name = java.net.URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8")
            if (name != "author-signature.xml" && !Regex("signature[0-9]+\\.xml").matches(name)) entries.add(name to digest)
        }
        return if (entries.isEmpty()) null else fingerprintReferences(entries)
    }

    private fun fingerprintReferences(entries: List<Pair<String, String>>): String =
        sha256(entries.sortedBy { it.first }.joinToString("\n") { it.first + "\t" + it.second }.toByteArray())

    fun authorFingerprint(file: File): String? = ZipFile(file).use { zip ->
        val entry = zip.getEntry("author-signature.xml") ?: return null
        val doc = xml(zip.getInputStream(entry).use { bounded(it, 1024 * 1024) })
        val cert = doc.getElementsByTagNameNS(DS, "X509Certificate").item(0)?.textContent ?: return null
        sha256(Base64.getDecoder().decode(cert.replace(Regex("\\s"), "")))
    }

    private fun validateEntries(zip: ZipFile) {
        val names = HashSet<String>()
        var size = 0L
        var count = 0
        zip.entries().asSequence().forEach { entry ->
            require(++count <= 30000) { "Too many package entries." }
            require(names.add(entry.name)) { "Duplicate archive entry." }
            require(!entry.name.startsWith('/') && !entry.name.contains('\\') &&
                entry.name.split('/').none { it == ".." } && entry.name.none { it.code < 32 }) { "Unsafe archive entry." }
            require(entry.size >= 0) { "Unknown archive entry size." }
            size += entry.size
            require(size <= MAX_BYTES) { "Uncompressed package exceeds 512 MiB." }
        }
    }

    fun bounded(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size().toLong() + count <= limit) { "File exceeds size limit." }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    /** Hashes files as streams and writes a new archive without changing manifests or app content. */
    fun sign(input: File, output: File, author: SigningIdentity, distributor: SigningIdentity, checkCancelled: () -> Unit = {}) {
        ZipFile(input).use { zip ->
            validateEntries(zip)
            val entries = zip.entries().asSequence().filter {
                !it.isDirectory && it.name != "author-signature.xml" && !Regex("signature[0-9]+\\.xml").matches(it.name)
            }.toList()
            val references = entries.map { entry ->
                checkCancelled()
                val digest = MessageDigest.getInstance("SHA-512")
                var read = 0L
                zip.getInputStream(entry).use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        read += count
                        require(read <= entry.size) { "Archive size does not match its directory." }
                        digest.update(buffer, 0, count)
                    }
                }
                require(read == entry.size) { "Truncated archive entry." }
                entry.name to b64(digest.digest())
            }
            val authorXml = signature("AuthorSignature", author, references)
            val distributorXml = signature("DistributorSignature", distributor,
                references + ("author-signature.xml" to digest(authorXml)))
            ZipOutputStream(output.outputStream()).use { out ->
                for ((name, bytes) in listOf("author-signature.xml" to authorXml, "signature1.xml" to distributorXml)) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(bytes)
                    out.closeEntry()
                }
                for (entry in entries) {
                    checkCancelled()
                    out.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            checkCancelled()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            read += count
                            require(read <= entry.size) { "Archive size does not match its directory." }
                            out.write(buffer, 0, count)
                        }
                    }
                    out.closeEntry()
                }
            }
        }
    }

    fun signature(id: String, identity: SigningIdentity, references: List<Pair<String, String>>): ByteArray {
        val role = if (id == "AuthorSignature") "author" else "distributor"
        // These are canonical serializations of XML we construct ourselves, not a general XML canonicalizer.
        val properties = "<Object xmlns=\"$DS\" Id=\"prop\"><SignatureProperties xmlns:dsp=\"$DSP\">" +
            "<SignatureProperty Id=\"profile\" Target=\"#$id\"><dsp:Profile URI=\"http://www.w3.org/ns/widgets-digsig#profile\"></dsp:Profile></SignatureProperty>" +
            "<SignatureProperty Id=\"role\" Target=\"#$id\"><dsp:Role URI=\"http://www.w3.org/ns/widgets-digsig#role-$role\"></dsp:Role></SignatureProperty>" +
            "<SignatureProperty Id=\"identifier\" Target=\"#$id\"><dsp:Identifier></dsp:Identifier></SignatureProperty>" +
            "</SignatureProperties></Object>"
        val refs = references.joinToString("") { (name, hash) ->
            reference(URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~"), hash, false)
        } + reference("#prop", digest(properties.toByteArray()), true)
        val signedInfo = "<SignedInfo xmlns=\"$DS\"><CanonicalizationMethod Algorithm=\"$C14N\"></CanonicalizationMethod>" +
            "<SignatureMethod Algorithm=\"$RSA512\"></SignatureMethod>$refs</SignedInfo>"
        val signer = Signature.getInstance("SHA512withRSA")
        signer.initSign(identity.key)
        signer.update(signedInfo.toByteArray(Charsets.UTF_8))
        val chain = identity.chain.joinToString("") { "<X509Certificate>${b64(it.encoded)}</X509Certificate>" }
        return ("<Signature xmlns=\"$DS\" Id=\"$id\">$signedInfo<SignatureValue>${b64(signer.sign())}</SignatureValue>" +
            "<KeyInfo><X509Data>$chain</X509Data></KeyInfo>$properties</Signature>").toByteArray(Charsets.UTF_8)
    }

    private fun reference(uri: String, digest: String, property: Boolean): String =
        "<Reference URI=\"${escape(uri)}\">" +
            (if (property) "<Transforms><Transform Algorithm=\"$C14N11\"></Transform></Transforms>" else "") +
            "<DigestMethod Algorithm=\"$SHA512\"></DigestMethod><DigestValue>$digest</DigestValue></Reference>"

    private fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun digest(bytes: ByteArray) = b64(MessageDigest.getInstance("SHA-512").digest(bytes))
}
