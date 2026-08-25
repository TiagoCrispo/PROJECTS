package com.mendozameteo.x10;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

final class AndroidConnectivityProbe implements ConnectivityProbe {
    private final Context appContext;

    AndroidConnectivityProbe(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public boolean isOnline() {
        ConnectivityManager manager = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return true;
        try {
            Network active = manager.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(active);
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException ignored) {
            return true;
        }
    }
}
