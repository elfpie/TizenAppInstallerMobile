package com.tizenappinstallermobile.tizen;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import java.io.IOException;

/** Standalone test-APK component: framework-only, no target-app runtime dependencies. */
public final class TestVpnService extends VpnService {
    private ParcelFileDescriptor tunnel;
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (tunnel == null) {
            tunnel = new Builder().setSession("Tizen Pocket networking regression")
                .addAddress("172.30.0.2", 32).addRoute("203.0.113.0", 24).establish();
        }
        if (tunnel == null) throw new IllegalStateException("CI must authorize the test VPN");
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        if (tunnel != null) {
            try { tunnel.close(); } catch (IOException ignored) { }
            tunnel = null;
        }
        super.onDestroy();
    }
}
