package com.mendozameteo.x10;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

final class NotificationPermissionGate {
    private static final String PREFS = "notification_permission_v1";
    private static final String KEY_ASKED = "asked";

    private NotificationPermissionGate() { }

    static boolean shouldRequest(Activity activity) {
        if (Build.VERSION.SDK_INT < 33) return false;
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return !prefs.getBoolean(KEY_ASKED, false);
    }

    static void markRequested(Context context) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ASKED, true).apply();
    }
}
