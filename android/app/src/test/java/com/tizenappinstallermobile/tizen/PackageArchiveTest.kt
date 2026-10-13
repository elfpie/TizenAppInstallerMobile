package com.tizenappinstallermobile.tizen

import java.io.File
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.apache.xml.security.Init
import org.apache.xml.security.signature.XMLSignature
import org.apache.xml.security.signature.XMLSignatureInput
import org.apache.xml.security.utils.resolver.ResourceResolverContext
import org.apache.xml.security.utils.resolver.ResourceResolverSpi
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class PackageArchiveTest {
    private fun identity(): SigningIdentity {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Name("CN=Test")
        val cert = JcaX509CertificateConverter().getCertificate(JcaX509v3CertificateBuilder(subject, BigInteger.ONE,
            Date(System.currentTimeMillis() - 60000), Date(System.currentTimeMillis() + 86400000), subject, pair.public)
            .build(JcaContentSignerBuilder("SHA512withRSA").build(pair.private)))
        return SigningIdentity(pair.private, listOf(cert))
    }
    private fun widget(file: File, extra: String = "folder/a b+#.js") {
        ZipOutputStream(file.outputStream()).use { out ->
            val entries = mapOf("config.xml" to """<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" version="0.1.0"><name>Jellyfin</name><tizen:application id="abc.Jellyfin" package="abc"/></widget>""",
                extra to "window.example = 'hello';")
            entries.forEach { (name, value) -> out.putNextEntry(ZipEntry(name)); out.write(value.toByteArray()); out.closeEntry() }
        }
    }
    private fun verify(xml: ByteArray, files: Map<String, ByteArray>, identity: SigningIdentity): Boolean {
        Init.init()
        val doc = PackageArchive.xml(xml)
        (doc.getElementsByTagNameNS(PackageArchive.DS, "Object").item(0) as Element).setIdAttribute("Id", true)
        val signature = XMLSignature(doc.documentElement, "", false)
        signature.addResourceResolver(object : ResourceResolverSpi() {
            override fun engineCanResolveURI(context: ResourceResolverContext) = !context.uriToResolve.startsWith("#")
            override fun engineResolveURI(context: ResourceResolverContext): XMLSignatureInput {
                val name = URLDecoder.decode(context.uriToResolve.replace("+", "%2B"), "UTF-8")
                return XMLSignatureInput(files[name] ?: error("Missing reference " + name))
            }
        })
        return signature.checkSignatureValue(identity.chain.first())
    }
    @Test fun signaturesValidateIndependentlyAndRejectTampering() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val original = File(directory, "original.wgt").also { widget(it) }
            val signed = File(directory, "signed.wgt")
            val author = identity(); val distributor = identity()
            PackageArchive.sign(original, signed, author, distributor)
            val files = ZipFile(signed).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).use { stream -> stream.readBytes() } } }
            assertTrue(verify(files.getValue("author-signature.xml"), files, author))
            assertTrue(verify(files.getValue("signature1.xml"), files, distributor))
            assertFalse(verify(files.getValue("author-signature.xml"), files + ("folder/a b+#.js" to "tampered".toByteArray()), author))
            assertEquals(PackageArchive.inspect(original), PackageArchive.inspect(signed))
            assertEquals(PackageArchive.contentFingerprint(original), PackageArchive.signatureContentFingerprint(files.getValue("author-signature.xml")))
            assertEquals(author.fingerprint, PackageArchive.authorFingerprint(signed))
        } finally { directory.deleteRecursively() }
    }
    @Test fun importedP12KeepsTheAuthorKey() {
        val original = identity()
        val imported = CertificateStore.readP12(CertificateStore.exportP12(original), charArrayOf())
        assertEquals(original.fingerprint, imported.fingerprint)
        assertArrayEquals(original.key.encoded, imported.key.encoded)
        val password = "twelve-characters-or-more".toCharArray()
        val protected = CertificateStore.exportP12(original, password)
        assertEquals(original.fingerprint, CertificateStore.readP12(protected, password).fingerprint)
        assertThrows(IllegalArgumentException::class.java) { CertificateStore.readP12(protected, "wrong".toCharArray()) }
    }
    @Test fun unsafePathsAndEntitiesAreRejected() {
        val file = File.createTempFile("unsafe", ".wgt")
        try {
            widget(file, "../escape")
            assertThrows(IllegalArgumentException::class.java) { PackageArchive.inspect(file) }
            assertThrows(IllegalArgumentException::class.java) { PackageArchive.xml("""<!DOCTYPE x [<!ENTITY a SYSTEM "file:///etc/passwd">]><x>&a;</x>""".toByteArray()) }
        } finally { file.delete() }
    }
    @Test fun readsTizenIdentityInsteadOfWidgetUrl() {
        val file = File.createTempFile("valid", ".wgt")
        try {
            widget(file)
            val info = PackageArchive.inspect(file)
            assertEquals("abc.Jellyfin", info.appId); assertEquals("abc", info.packageId)
        } finally { file.delete() }
    }
}
