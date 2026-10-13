package com.tizenappinstallermobile.tizen

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject

data class CertificateProfile(val author: SigningIdentity, val distributor: SigningIdentity) {
    fun validate(duid: String) {
        author.chain.forEach { it.checkValidity() }
        distributor.chain.forEach { it.checkValidity() }
        val certificate = distributor.chain.first()
        val sans = certificate.subjectAlternativeNames.orEmpty()
        require(sans.any { it[0] == 6 && it[1].toString().equals("URN:tizen:deviceid=$duid", true) }) {
            "Distributor certificate does not authorize this TV's DUID. Import the matching profile or renew it with Samsung."
        }
    }
}

class CertificateStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "certificates").apply { mkdirs() }
    private val alias = "tizen-pocket-certificates-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun file(duid: String) = AtomicFile(File(directory, sha256(duid.toByteArray()) + ".enc"))

    fun load(duid: String): CertificateProfile? {
        val file = file(duid)
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        require(bytes.size > 12) { "Stored certificate profile is corrupt. Import your original certificates." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(duid.toByteArray())
        val json = JSONObject(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        return CertificateProfile(readP12(Base64.getDecoder().decode(json.getString("author")), charArrayOf(), false),
            readP12(Base64.getDecoder().decode(json.getString("distributor")), charArrayOf(), false))
    }

    fun save(duid: String, profile: CertificateProfile) {
        profile.validate(duid)
        val json = JSONObject().put("author", Base64.getEncoder().encodeToString(exportP12(profile.author)))
            .put("distributor", Base64.getEncoder().encodeToString(exportP12(profile.distributor)))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(duid.toByteArray())
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray())
        val target = file(duid)
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    companion object {
        // Instantiate BC directly rather than replacing Android's system security provider.
        private val provider = BouncyCastleProvider()

        fun readP12(bytes: ByteArray, password: CharArray, validateValidity: Boolean = true): SigningIdentity {
            val store = KeyStore.getInstance("PKCS12", provider)
            try {
                store.load(ByteArrayInputStream(bytes), password)
                val alias = store.aliases().asSequence().firstOrNull { store.isKeyEntry(it) }
                    ?: error("Certificate file contains no private key.")
                val key = store.getKey(alias, password) as? PrivateKey ?: error("No signing private key.")
                require(key.algorithm == "RSA") { "Tizen signing requires an RSA certificate." }
                val chain = store.getCertificateChain(alias).map { it as X509Certificate }
                if (validateValidity) chain.forEach { it.checkValidity() }
                // Prove the imported private key belongs to the leaf certificate.
                val proof = java.security.Signature.getInstance("SHA512withRSA")
                proof.initSign(key)
                proof.update("tizen-key-check".toByteArray())
                val signature = proof.sign()
                proof.initVerify(chain.first())
                proof.update("tizen-key-check".toByteArray())
                require(proof.verify(signature)) { "Private key does not match certificate." }
                return SigningIdentity(key, chain)
            } catch (error: Exception) {
                throw IllegalArgumentException("Unable to open PKCS#12 certificate. Check its password, validity and private key.", error)
            }
        }

        fun exportP12(identity: SigningIdentity, password: CharArray = charArrayOf()): ByteArray {
            val store = KeyStore.getInstance("PKCS12", provider).apply { load(null, charArrayOf()) }
            store.setKeyEntry("signing", identity.key, password, identity.chain.toTypedArray())
            val output = ByteArrayOutputStream()
            store.store(output, password)
            return output.toByteArray()
        }
    }
}
