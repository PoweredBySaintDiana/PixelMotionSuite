package dev.pixelsuite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class UpgradePreferences extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        try {
            ModulePreferences.features(context, true);
            ModulePreferences.dock(context);
        } catch (RuntimeException failure) {
            Log.w("PixelSuite", "Preference upgrade deferred until settings open", failure);
        }
    }
}
