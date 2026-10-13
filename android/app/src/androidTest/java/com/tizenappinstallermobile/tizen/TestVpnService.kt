package com.tizenappinstallermobile.tizen

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.ParcelFileDescriptor

/** Test APK only. Split tunnel keeps LAN reachable but forbids explicit VPN bypass. */
class TestVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (tunnel == null) tunnel = Builder().setSession("Tizen Pocket networking regression")
            .addAddress("172.30.0.2", 32).addRoute("203.0.113.0", 24).establish()
        check(tunnel != null) { "CI must authorize the test VPN" }
        return START_NOT_STICKY
    }
    override fun onDestroy() { tunnel?.close(); tunnel = null; super.onDestroy() }
}

class TestVpnActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(VpnService.prepare(this) == null) { "Test VPN permission was not granted" }
        startService(Intent(this, TestVpnService::class.java))
    }
}
