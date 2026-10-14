package com.tizenappinstallermobile.tizen;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** A separate APK owns the foreground, just like the user's external browser. */
public class TestBrowserActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("External sign-in page");
        setContentView(text);
    }
}
