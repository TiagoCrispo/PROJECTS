package com.fer.wavault;

import android.content.Context;
import android.content.SharedPreferences;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.os.Build;
import java.util.List;

/** Privacy-safe local crash marker: no message text, names, paths, or stack traces. */
public final class CrashRegistry {
    private CrashRegistry(){}
    private static final String PREF="wa_vault_diag";
    private static volatile boolean installed=false;

    public static synchronized void install(Context context){
        if(context==null||installed)return;installed=true;
        Context app=context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try{
                SharedPreferences p=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
                String type=error==null?"Unknown":error.getClass().getSimpleName();
                String threadName=thread==null?"unknown":safe(thread.getName(),48);
                String component=component(threadName);
                p.edit().putInt("crash_count",p.getInt("crash_count",0)+1)
                        .putLong("crash_last_at",System.currentTimeMillis())
                        .putString("crash_last_type",safe(type,64))
                        .putString("crash_last_thread",threadName)
                        .putString("crash_last_component",component)
                        .commit();
            }catch(Throwable ignored){}
            if(previous!=null)previous.uncaughtException(thread,error);
        });
    }

    /** Android 11+: capture OS-reported prior exits (ANR/native crash/LMK) without description or trace data. */
    public static void capturePreviousExit(Context c){
        if(c==null||Build.VERSION.SDK_INT<Build.VERSION_CODES.R)return;
        try{
            ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);if(am==null)return;
            List<ApplicationExitInfo> list=am.getHistoricalProcessExitReasons(c.getPackageName(),0,5);if(list==null||list.isEmpty())return;
            SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);long seen=p.getLong("exit_info_seen_at",0L);
            ApplicationExitInfo best=null;for(ApplicationExitInfo info:list){if(info==null||info.getTimestamp()<=seen||!isUnexpectedReason(info.getReason()))continue;if(best==null||info.getTimestamp()>best.getTimestamp())best=info;}
            if(best==null)return;long at=best.getTimestamp();String type=exitReason(best.getReason());
            p.edit().putLong("exit_info_seen_at",at).putLong("exit_last_at",at).putString("exit_last_type",type).putString("exit_last_component","Android").apply();
        }catch(Throwable ignored){}
    }

    public static boolean hasUnacknowledged(Context c){if(c==null)return false;SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);long latest=Math.max(p.getLong("crash_last_at",0L),p.getLong("exit_last_at",0L));return latest>p.getLong("crash_ack_at",0L);}
    public static String summary(Context c){if(c==null)return "Sin cierres inesperados registrados";SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);long crash=p.getLong("crash_last_at",0L),exit=p.getLong("exit_last_at",0L);long at=Math.max(crash,exit);if(at<=0)return "Sin cierres inesperados registrados";boolean os=exit>crash;String type=p.getString(os?"exit_last_type":"crash_last_type","Error");String component=p.getString(os?"exit_last_component":"crash_last_component","app");return type+" · "+component+" · "+android.text.format.DateFormat.format("dd/MM HH:mm",new java.util.Date(at));}
    private static boolean isUnexpectedReason(int r){return r==ApplicationExitInfo.REASON_ANR||r==ApplicationExitInfo.REASON_CRASH||r==ApplicationExitInfo.REASON_CRASH_NATIVE||r==ApplicationExitInfo.REASON_LOW_MEMORY||r==ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE||r==ApplicationExitInfo.REASON_INITIALIZATION_FAILURE||r==ApplicationExitInfo.REASON_DEPENDENCY_DIED||r==ApplicationExitInfo.REASON_SIGNALED;}
    private static String exitReason(int r){if(r==ApplicationExitInfo.REASON_ANR)return "ANR";if(r==ApplicationExitInfo.REASON_CRASH_NATIVE)return "Crash nativo";if(r==ApplicationExitInfo.REASON_CRASH)return "Crash";if(r==ApplicationExitInfo.REASON_LOW_MEMORY)return "Memoria insuficiente";if(r==ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE)return "Recursos excesivos";if(r==ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)return "Fallo de inicio";if(r==ApplicationExitInfo.REASON_DEPENDENCY_DIED)return "Dependencia finalizada";if(r==ApplicationExitInfo.REASON_SIGNALED)return "Proceso finalizado por Android";return "Cierre de Android";}
    public static void acknowledge(Context c){if(c!=null)c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putLong("crash_ack_at",System.currentTimeMillis()).apply();}
    private static String component(String thread){String t=thread==null?"":thread.toLowerCase();if(t.contains("watchdog"))return "watchdog";if(t.contains("media"))return "media";if(t.contains("audio")||t.contains("voice"))return "audio";if(t.contains("notification"))return "listener";if(t.contains("main"))return "interfaz";return "motor";}
    private static String safe(String s,int max){if(s==null)return "";String out=s.replace('\n',' ').replace('\r',' ').trim();return out.length()>max?out.substring(0,max):out;}
}
