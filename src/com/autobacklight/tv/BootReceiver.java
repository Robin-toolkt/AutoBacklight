package com.autobacklight.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BacklightService.anythingEnabled(Prefs.get(context))) return;
        // Android 14 forbids starting a camera foreground service straight from BOOT_COMPLETED,
        // so bring up an invisible activity that starts it from the foreground instead.
        try {
            Intent i = new Intent(context, BootActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            context.startActivity(i);
        } catch (Exception e) {
            Log.w(BacklightService.TAG, "Boot activity failed, starting service directly", e);
            BacklightService.start(context);
        }
    }
}
