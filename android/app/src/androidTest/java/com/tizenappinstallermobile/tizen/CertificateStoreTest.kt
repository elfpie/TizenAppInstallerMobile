package com.tizenappinstallermobile.tizen

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date
import java.util.UUID
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CertificateStoreTest {
    private fun profile(duid: String): CertificateProfile {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Name("CN=Storage test")
        val cert = JcaX509CertificateConverter().getCertificate(JcaX509v3CertificateBuilder(subject, BigInteger.ONE,
            Date(System.currentTimeMillis() - 60000), Date(System.currentTimeMillis() + 86400000), subject, pair.public)
            .addExtension(Extension.subjectAlternativeName, false,
                GeneralNames(GeneralName(GeneralName.uniformResourceIdentifier, "URN:tizen:deviceid=$duid")))
            .build(JcaContentSignerBuilder("SHA512withRSA").build(pair.private)))
        val identity = SigningIdentity(pair.private, listOf(cert))
        return CertificateProfile(identity, identity)
    }
    @Test fun importingOriginalAppKeysPreservesTheDefaultAndOtherAppKeys() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "key-test-" + UUID.randomUUID()).apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        val duid = "TEST-" + UUID.randomUUID()
        try {
            val current = profile(duid)
            val original = profile(duid)
            CertificateStore(context).save(duid, current)
            CertificateStore(context).save(duid, original, "original.Jellyfin")
            val restored = CertificateStore(context)
            assertEquals(current.author.fingerprint, restored.load(duid)!!.author.fingerprint)
            assertEquals(current.author.fingerprint, restored.forApp(duid, "new.Moonlight")!!.author.fingerprint)
            assertEquals(original.author.fingerprint, restored.forApp(duid, "original.Jellyfin")!!.author.fingerprint)
            assertArrayEquals(original.author.key.encoded, restored.load(duid, "original.Jellyfin")!!.author.key.encoded)
            assertNull(restored.load(duid, "new.Moonlight"))
        } finally { directory.deleteRecursively() }
    }
}
