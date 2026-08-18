package com.fer.wavault;

import android.content.Context;
import android.content.Intent;

/** Lightweight in-process/app-private signal used to refresh visible UI without navigation. */
public final class VaultUiNotifier {
    private VaultUiNotifier() {}
    public static final String ACTION_DATA_CHANGED = "com.fer.wavault.DATA_CHANGED";
    public static final String EXTRA_KIND = "kind";
    public static final String INTERNAL_PERMISSION = "com.fer.wavault.permission.INTERNAL_EVENTS";

    public static void notifyChanged(Context context, String kind) {
        if (context == null) return;
        try {
            Intent i = new Intent(ACTION_DATA_CHANGED);
            i.setPackage(context.getPackageName());
            i.putExtra(EXTRA_KIND, kind == null ? "data" : kind);
            context.sendBroadcast(i, INTERNAL_PERMISSION);
        } catch (Throwable ignored) {}
    }
}
