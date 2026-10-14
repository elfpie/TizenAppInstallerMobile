package com.tizenappinstallermobile.tizen
import org.junit.Assert.*
import org.junit.Test
class InstallPolicyTest {
    private val info = PackageInfo("abc", "abc.Jellyfin", "0.1.0", "Jellyfin", "wgt")
    @Test fun exactIdentityMatching() {
        assertTrue(InstallPolicy.isInstalled("app_tizen_id =abc.Jellyfin", info))
        assertFalse(InstallPolicy.isInstalled("app_tizen_id =abc.JellyfinSecondary", info))
    }
    @Test fun authorOrIdentityChangesStopTheUpdate() {
        assertThrows(IllegalArgumentException::class.java) { InstallPolicy.verifyIdentity(info, info.copy(appId = "other.Jellyfin"), "old", "old") }
        assertThrows(IllegalArgumentException::class.java) { InstallPolicy.verifyIdentity(info, info, "old", "new") }
        assertThrows(IllegalArgumentException::class.java) { InstallPolicy.verifyIdentity(info, info, "old", null) }
    }
    @Test fun explicitSuccessRequired() {
        InstallPolicy.verifyResult("install completed\nresult[success]:val[0]")
        assertThrows(IllegalStateException::class.java) { InstallPolicy.verifyResult("install failed: author mismatch") }
        assertThrows(IllegalStateException::class.java) { InstallPolicy.verifyResult("[100]") }
        assertThrows(IllegalStateException::class.java) { InstallPolicy.verifyResult("result[success]:val[1]") }
    }
    @Test fun lateCertificateFailureIsReportedBrieflyWithItsTvCode() {
        val response = "install start\n" + "installing[28]\n".repeat(100) +
            "install failed[118, -12], reason: Check certificate error : Invalid format of certificate in signature."
        assertEquals("118:-12", InstallPolicy.errorCode(response))
        val failure = assertThrows(IllegalStateException::class.java) { InstallPolicy.verifyResult(response) }
        assertTrue(failure.message.orEmpty().contains("certificate"))
        assertTrue(failure.message.orEmpty().contains("118:-12"))
        assertTrue(failure.message.orEmpty().length < 200)
    }
    @Test fun tvListingContainsActualVersions() {
        val output = """
            --------------------
            --------------app_id =123-------------
            --------------app_title =Jellyfin-------------
            --------------app_tizen_id =abc.Jellyfin-------------
            --------------app_version =0.1.0-------------
            --------------app_installed_path =/opt/usr/home/owner/apps_rw/abc/-------------
            --------------------
            --------------app_id =124-------------
            --------------app_title =Moonlight-------------
            --------------app_tizen_id =MoonLightS.MoonlightWasm-------------
            --------------app_version =1.18.0-------------
            --------------------
        """.trimIndent()
        val apps = TvInventory.parse(output)
        assertEquals(2, apps.size); assertEquals("abc.Jellyfin", apps[0].appId)
        assertEquals("0.1.0", apps[0].version); assertEquals("1.18.0", apps[1].version)
        assertEquals(0, TvInventory.parse("apps count: 0").size)
        assertThrows(IllegalStateException::class.java) { TvInventory.parse("error: command unavailable") }
    }
}
