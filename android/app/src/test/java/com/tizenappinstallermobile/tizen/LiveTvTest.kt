package com.tizenappinstallermobile.tizen

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional, read-only checks against a TV explicitly supplied by the developer. */
class LiveTvTest {
    @Test fun httpDiscovery() {
        val host = System.getenv("TIZEN_HTTP_TEST_HOST").orEmpty()
        assumeTrue("Set TIZEN_HTTP_TEST_HOST to run against a real TV", host.isNotEmpty())
        val tv = SamsungTvInfo.probe(host, OkHttpClient())
        assertNotNull("Samsung TV metadata should be reachable", tv)
        println("Found TV: " + tv!!.name + ", model=" + tv.model + ", developerMode=" + tv.developerMode + ", hostIP=" + tv.developerHost)
    }
    @Test fun sdbQueries() {
        val host = System.getenv("TIZEN_SDB_TEST_HOST").orEmpty()
        assumeTrue("Set TIZEN_SDB_TEST_HOST after authorizing this computer's IP on the TV", host.isNotEmpty())
        SdbClient(host).use { client ->
            val capabilities = client.capabilities()
            assertNotNull("TV should report its platform version", capabilities["platform_version"])
            val duid = client.deviceUid()
            assertTrue("TV should report a DUID", duid.length >= 8)
            val apps = TvInventory.parse(client.shell("0 vd_applist"))
            repeat(2) {
                assertEquals(duid, client.deviceUid())
                assertEquals(capabilities["platform_version"], client.capabilities()["platform_version"])
                assertEquals(apps.size, TvInventory.parse(client.shell("0 vd_applist")).size)
            }
            for (app in apps.filter { it.name.contains("Jellyfin", true) }) {
                val base = app.path?.trimEnd('/') ?: continue
                var fingerprint: String? = null
                for (suffix in listOf("/res/wgt/author-signature.xml", "/author-signature.xml", "/res/author-signature.xml")) {
                    fingerprint = runCatching { PackageArchive.signatureContentFingerprint(client.pull(base + suffix)) }.getOrNull()
                    if (fingerprint != null) break
                }
                println("Jellyfin installed signature readable: " + (fingerprint != null))
                assertEquals(duid, client.deviceUid())
            }
            println("SDB queries passed: Tizen " + capabilities["platform_version"] + ", " + apps.size + " installed apps")
            apps.filter { Regex("jellyfin|tizenbrew|moonlight", RegexOption.IGNORE_CASE).containsMatchIn(it.name + it.appId) }
                .forEach { println(it.name + ": version=" + it.version) }
        }
    }
}
