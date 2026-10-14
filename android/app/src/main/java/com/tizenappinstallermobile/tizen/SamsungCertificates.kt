package com.tizenappinstallermobile.tizen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.net.URLEncoder
import java.security.KeyPair
import java.security.KeyPairGenerator
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
    private val onEvent: (String, Map<String, Any>) -> Unit = { _, _ -> },
    private val launchBrowser: ((String) -> Unit)? = null,
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
            onEvent("samsung.certificate.$kind.response", mapOf("http_status" to response.code))
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

    internal fun login(): JSONObject {
        onStatus("Complete Samsung sign-in in your browser, then return here")
        SamsungLoginCallback(checkCancelled = checkCancelled, onEvent = onEvent).use { callback ->
            register(callback)
            val redirect = URLEncoder.encode("http://localhost:${callback.port}/signin/callback", "UTF-8")
            val url = "https://account.samsung.com/accounts/be1dce529476c1a6d407c4c7578c31bd/signInGate?" +
                "locale=&clientId=v285zxnl3h&redirect_uri=$redirect&state=${callback.state}&tokenType=TOKEN"
            Handler(Looper.getMainLooper()).post {
                try {
                    if (launchBrowser != null) launchBrowser.invoke(url)
                    else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    onEvent("samsung.browser.opened", emptyMap())
                } catch (_: Exception) {
                    onEvent("samsung.browser.failed", mapOf("reason" to "browser_error"))
                    callback.fail(IllegalStateException("Cannot open the browser. Install a browser or import your certificate files."))
                }
            }
            return callback.await()
        }
    }
}
