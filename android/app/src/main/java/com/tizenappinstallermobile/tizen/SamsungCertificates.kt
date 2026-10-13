package com.tizenappinstallermobile.tizen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtensionsGenerator
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.json.JSONObject

class SamsungCertificates(
    private val context: Context,
    private val register: (Closeable) -> Unit,
    private val checkCancelled: () -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder().connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS).callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()

    fun create(duid: String, existing: CertificateProfile?): CertificateProfile {
        existing?.author?.chain?.forEach { it.checkValidity() } // Never silently replace the author key.
        val auth = login()
        onStatus("Requesting Samsung certificates")
        val email = auth.optString("inputEmailID").ifBlank { "Tizen Pocket" }
        val author = existing?.author ?: run {
            val pair = keyPair()
            val subject = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, email).build()
            val pem = csr(pair, subject, null)
            val response = post("authors", auth, pem)
            identity(pair, response, "vd_tizen_dev_author_ca.cer")
        }
        checkCancelled()
        val pair = keyPair()
        val subject = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "TizenSDK").addRDN(BCStyle.E, email).build()
        val response = post("distributors", auth, csr(pair, subject, duid))
        return CertificateProfile(author, identity(pair, response, "vd_tizen_dev_public2.crt")).also { it.validate(duid) }
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun csr(pair: KeyPair, subject: X500Name, duid: String?): String {
        val builder = JcaPKCS10CertificationRequestBuilder(subject, pair.public)
        if (duid != null) {
            val extensions = ExtensionsGenerator()
            extensions.addExtension(Extension.subjectAlternativeName, false, GeneralNames(arrayOf(
                GeneralName(GeneralName.uniformResourceIdentifier, "URN:tizen:packageid="),
                GeneralName(GeneralName.uniformResourceIdentifier, "URN:tizen:deviceid=$duid"))))
            builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate())
        }
        val bytes = builder.build(JcaContentSignerBuilder("SHA512withRSA").build(pair.private)).encoded
        return "-----BEGIN CERTIFICATE REQUEST-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(bytes) +
            "\n-----END CERTIFICATE REQUEST-----\n"
    }

    private fun post(kind: String, auth: JSONObject, pem: String): String {
        checkCancelled()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("access_token", auth.getString("access_token"))
            .addFormDataPart("user_id", auth.getString("userId"))
            .addFormDataPart("platform", "VD")
            .addFormDataPart("csr", if (kind == "authors") "author.csr" else "distributor.csr",
                pem.toRequestBody("application/octet-stream".toMediaType()))
        if (kind == "distributors") body.addFormDataPart("privilege_level", "Public").addFormDataPart("developer_type", "Individual")
        val call = http.newCall(Request.Builder().url("https://svdca.samsungqbe.com/apis/v3/$kind").post(body.build()).build())
        register(Closeable { call.cancel() })
        return call.execute().use { response ->
            check(response.isSuccessful) { "Samsung certificate request failed (HTTP ${response.code}). Sign in again or import existing certificates." }
            PackageArchive.bounded(response.body!!.byteStream(), 1024 * 1024).toString(Charsets.UTF_8)
        }
    }

    private fun identity(pair: KeyPair, response: String, caName: String): SigningIdentity {
        val factory = CertificateFactory.getInstance("X.509")
        val decoded = response.replace("&amp;", "&").replace("&#10;", "\n").replace("\\n", "\n")
        val certificates = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----")
            .findAll(decoded).map { factory.generateCertificate(it.value.byteInputStream()) as X509Certificate }.toList()
        val leaf = certificates.firstOrNull { it.publicKey.encoded.contentEquals(pair.public.encoded) }
            ?: error("Samsung returned no certificate matching the requested key.")
        val intermediate = context.assets.open("samsung/$caName").use { factory.generateCertificate(it) as X509Certificate }
        leaf.checkValidity()
        leaf.verify(intermediate.publicKey)
        return SigningIdentity(pair.private, listOf(leaf, intermediate))
    }

    private fun login(): JSONObject {
        onStatus("Complete Samsung sign-in in your browser, then return here")
        val state = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val server = ServerSocket()
        register(server)
        server.reuseAddress = true
        server.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 4794))
        server.soTimeout = 1000
        val redirect = URLEncoder.encode("http://localhost:4794/signin/callback", "UTF-8")
        val url = "https://account.samsung.com/accounts/be1dce529476c1a6d407c4c7578c31bd/signInGate?" +
            "locale=&clientId=v285zxnl3h&redirect_uri=$redirect&state=$state&tokenType=TOKEN"
        Handler(Looper.getMainLooper()).post {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                server.close()
            }
        }
        val deadline = System.nanoTime() + 300L * 1_000_000_000
        server.use {
            while (System.nanoTime() < deadline) {
                checkCancelled()
                val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                register(socket)
                socket.use {
                    socket.soTimeout = 5000
                    try {
                        val input = socket.getInputStream()
                        fun line(): String {
                            val bytes = java.io.ByteArrayOutputStream()
                            while (true) {
                                val byte = input.read()
                                require(byte >= 0 && bytes.size() < 8192) { "Invalid callback request." }
                                if (byte == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                                bytes.write(byte)
                            }
                        }
                        val request = line().split(' ')
                        require(request.size == 3) { "Invalid callback request." }
                        val headers = mutableMapOf<String, String>()
                        var headerSize = 0
                        while (true) {
                            val next = line()
                            if (next.isEmpty()) break
                            headerSize += next.length
                            require(headerSize < 16384) { "Callback headers too large." }
                            val parts = next.split(':', limit = 2)
                            if (parts.size == 2) headers[parts[0].lowercase()] = parts[1].trim()
                        }
                        require(request[1].substringBefore('?') == "/signin/callback") { "Invalid callback path." }
                        val data = when (request[0]) {
                            "GET" -> request[1].substringAfter('?', "")
                            "POST" -> {
                                require(headers["content-type"].orEmpty().startsWith("application/x-www-form-urlencoded")) { "Invalid callback content type." }
                                val size = headers["content-length"]?.toInt() ?: 0
                                require(size in 1..65536) { "Invalid callback body size." }
                                SdbClient.readExactly(input, size).toString(Charsets.UTF_8)
                            }
                            else -> error("Invalid callback method.")
                        }
                        val params = data.split('&').associate {
                            val pair = it.split('=', limit = 2)
                            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                        }
                        val auth = JSONObject(params["code"] ?: error("Missing callback code."))
                        require((params["state"] ?: auth.optString("state")) == state) { "Invalid sign-in state." }
                        require(auth.optString("access_token").isNotBlank() && auth.optString("userId").isNotBlank()) { "Missing authentication fields." }
                        respond(socket, 200, "Sign-in complete. Return to Tizen Pocket.")
                        return auth
                    } catch (_: Exception) {
                        runCatching { respond(socket, 400, "Invalid sign-in response. Please retry sign-in from Tizen Pocket.") }
                    }
                }
            }
        }
        error("Samsung sign-in timed out after five minutes. Try again or import your certificate files.")
    }

    private fun respond(socket: java.net.Socket, status: Int, text: String) {
        val body = "<!doctype html><html><meta name=\"viewport\" content=\"width=device-width\"><body><h2>$text</h2></body></html>".toByteArray()
        socket.getOutputStream().write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Bad Request"}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
    }
}
