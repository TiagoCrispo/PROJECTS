package com.fer.a53performance;

import android.content.Context;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class AppProtection {
    private static final Set<String> DEFAULTS;
    static {
        HashSet<String> s=new HashSet<>();
        Collections.addAll(s,
                "com.google.android.gm","com.google.android.apps.messaging","com.samsung.android.messaging",
                "com.sec.android.app.clockpackage","com.google.android.deskclock","com.brave.browser","com.openai.chatgpt",
                "com.samsung.android.app.voicenote","com.samsung.android.dialer","com.google.android.dialer","com.samsung.android.contacts",
                "com.android.systemui","com.sec.android.app.launcher","com.google.android.gms","com.google.android.inputmethod.latin","com.samsung.android.honeyboard");
        DEFAULTS=Collections.unmodifiableSet(s);
    }
    private AppProtection(){}
    public static Set<String> defaults(){return DEFAULTS;}
    public static boolean isProtected(Context context,String pkg){return pkg==null||pkg.isBlank()||pkg.equals(context.getPackageName())||DEFAULTS.contains(pkg);}
}
