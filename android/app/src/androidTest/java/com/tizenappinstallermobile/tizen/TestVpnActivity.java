package com.tizenappinstallermobile.tizen;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;

public final class TestVpnActivity extends Activity {
    @Override public void onCreate(Bundle state) { super.onCreate(state); handle(getIntent()); }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); handle(intent); }
    private void handle(Intent intent) {
        if (intent.getBooleanExtra("stop", false)) {
            stopService(new Intent(this, TestVpnService.class));
            finish();
            return;
        }
        if (VpnService.prepare(this) != null) throw new IllegalStateException("Test VPN permission was not granted");
        startService(new Intent(this, TestVpnService.class));
    }
}
