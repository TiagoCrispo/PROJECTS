package com.fer.a53performance;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class AppProtection {
    private static final String PREFS="a53_protected_apps";
    private static final String KEY_USER="user_packages";
    private static final Set<String> DEFAULTS;
    static {
        HashSet<String> s=new HashSet<>();
        Collections.addAll(s,
                "com.google.android.gm",
                "com.google.android.apps.messaging",
                "com.samsung.android.messaging",
                "com.sec.android.app.clockpackage",
                "com.google.android.deskclock",
                "com.brave.browser",
                "com.openai.chatgpt",
                "com.samsung.android.app.voicenote",
                "com.samsung.android.dialer",
                "com.google.android.dialer",
                "com.samsung.android.contacts",
                "com.android.systemui",
                "com.sec.android.app.launcher",
                "com.google.android.gms",
                "com.google.android.inputmethod.latin",
                "com.samsung.android.honeyboard"
        );
        DEFAULTS=Collections.unmodifiableSet(s);
    }

    private AppProtection(){}
    public static Set<String> defaults(){return DEFAULTS;}

    public static Set<String> userProtected(Context context){
        SharedPreferences p=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        Set<String> stored=p.getStringSet(KEY_USER,Collections.emptySet());
        return Collections.unmodifiableSet(new HashSet<>(stored));
    }

    public static void setUserProtected(Context context,Set<String> packages){
        HashSet<String> clean=new HashSet<>();
        if(packages!=null)for(String pkg:packages)if(pkg!=null&&!pkg.isBlank()&&!pkg.equals(context.getPackageName()))clean.add(pkg);
        context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putStringSet(KEY_USER,clean).apply();
    }

    public static boolean isProtected(Context context,String pkg){
        if(pkg==null||pkg.isBlank())return true;
        if(pkg.equals(context.getPackageName())||DEFAULTS.contains(pkg)||userProtected(context).contains(pkg))return true;
        try{
            ApplicationInfo ai=context.getPackageManager().getApplicationInfo(pkg,0);
            return(ai.flags&ApplicationInfo.FLAG_SYSTEM)!=0||(ai.flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0;
        }catch(PackageManager.NameNotFoundException ignored){return true;}
    }
}
