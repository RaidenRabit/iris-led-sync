package com.raidenrabit.irisledsync;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Zero-UI activity that handles Google Assistant voice shortcuts.
 * Reads the "mode" extra (DAY / NIGHT / AUTO), applies it to DayNightManager,
 * then finishes immediately — no window is ever shown to the user.
 */
public class VoiceCommandActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyMode(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyMode(intent);
        finish();
    }

    private void applyMode(Intent intent) {
        if (intent == null) return;
        String modeStr = intent.getStringExtra("mode");
        if (modeStr == null) return;
        try {
            DayNightManager.getInstance(this)
                    .setMode(DayNightManager.Mode.valueOf(modeStr));
        } catch (IllegalArgumentException ignored) {}
    }
}
