package com.mendozameteo.x10;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class LauncherActivity extends Activity {
    private static final int NOTIFICATION_REQUEST = 73;
    private boolean forwarded;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WeatherNotifier.createChannels(getApplicationContext());
        if (NotificationPermissionGate.shouldRequest(this)) {
            NotificationPermissionGate.markRequested(this);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
            return;
        }
        syncSchedulerAndOpen();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_REQUEST) syncSchedulerAndOpen();
    }

    private void syncSchedulerAndOpen() {
        if (WeatherNotifier.canPost(this)) NotificationScheduler.ensureScheduled(this);
        else NotificationScheduler.cancel(this);
        openMain();
    }

    private void openMain() {
        if (forwarded || isFinishing()) return;
        forwarded = true;
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
