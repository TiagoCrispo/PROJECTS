package com.fer.wavault;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** High-resolution telemetry enabled only during an explicit 60s extreme test. */
public final class CaptureMetrics {
    private CaptureMetrics() {}
    private static final String PREFS="wa_vault_benchmark";
    private static final ConcurrentHashMap<String,Long> STARTS=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,Trace> TRACES=new ConcurrentHashMap<>();
    private static final class Trace{long event,fd,first,ready,commit,end;}

    public static long nowNs(){ return SystemClock.elapsedRealtimeNanos(); }
    public static boolean active(Context c){ return c!=null && c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getLong("until",0L)>System.currentTimeMillis(); }
    public static long remainingMs(Context c){ if(c==null)return 0L; return Math.max(0L,c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getLong("until",0L)-System.currentTimeMillis()); }

    public static void start60s(Context c){ startExtreme60s(c); }
    public static void startExtreme60s(Context c){
        if(c==null)return;
        SharedPreferences.Editor e=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear();
        long now=System.currentTimeMillis();
        e.putLong("started",now).putLong("until",now+60_000L).putString("mode","extreme").apply();
        STARTS.clear();TRACES.clear();
    }

    public static void markStart(Context c,String key){ if(active(c)&&key!=null)STARTS.put(key,nowNs()); }
    public static void finish(Context c,String key,String metric){
        if(!active(c)||key==null)return; Long st=STARTS.remove(key); if(st==null)return;
        record(c,metric,(nowNs()-st)/1_000_000.0);
    }

    public static void traceEvent(Context c,String key){if(!active(c)||key==null)return;Trace t=new Trace();t.event=nowNs();TRACES.put(key,t);}
    public static void traceStage(Context c,String key,String stage){
        if(!active(c)||key==null||stage==null)return;Trace t=TRACES.get(key);if(t==null)return;long n=nowNs();
        switch(stage){case "fd":t.fd=n;break;case "first_byte":t.first=n;break;case "staging_ready":t.ready=n;break;case "commit":t.commit=n;break;case "partial":case "failed":t.end=n;break;default:break;}
    }
    public static void finishTrace(Context c,String key){
        if(!active(c)||key==null)return;Trace t=TRACES.remove(key);if(t==null)return;long end=t.commit>0?t.commit:(t.end>0?t.end:nowNs());
        if(t.event>0&&t.fd>=t.event)record(c,"event_fd",(t.fd-t.event)/1_000_000.0);
        if(t.fd>0&&t.first>=t.fd)record(c,"fd_first",(t.first-t.fd)/1_000_000.0);
        if(t.first>0&&t.ready>=t.first)record(c,"first_ready",(t.ready-t.first)/1_000_000.0);
        if(t.ready>0&&t.commit>=t.ready)record(c,"ready_commit",(t.commit-t.ready)/1_000_000.0);
        if(t.event>0&&end>=t.event)record(c,"event_end",(end-t.event)/1_000_000.0);
    }

    public static void record(Context c,String metric,double ms){
        if(!active(c)||metric==null||ms<0)return;
        SharedPreferences sp=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        synchronized(CaptureMetrics.class){
            long n=sp.getLong(metric+"_n",0L)+1L;
            double sum=Double.longBitsToDouble(sp.getLong(metric+"_sum",Double.doubleToRawLongBits(0d)))+ms;
            double max=Math.max(Double.longBitsToDouble(sp.getLong(metric+"_max",Double.doubleToRawLongBits(0d))),ms);
            double min=n==1?ms:Math.min(Double.longBitsToDouble(sp.getLong(metric+"_min",Double.doubleToRawLongBits(ms))),ms);
            sp.edit().putLong(metric+"_n",n).putLong(metric+"_sum",Double.doubleToRawLongBits(sum)).putLong(metric+"_max",Double.doubleToRawLongBits(max)).putLong(metric+"_min",Double.doubleToRawLongBits(min)).apply();
        }
    }
    private static String line(SharedPreferences sp,String key,String label){
        long n=sp.getLong(key+"_n",0L); if(n<=0)return label+": sin muestras";
        double sum=Double.longBitsToDouble(sp.getLong(key+"_sum",Double.doubleToRawLongBits(0d)));
        double max=Double.longBitsToDouble(sp.getLong(key+"_max",Double.doubleToRawLongBits(0d)));
        double min=Double.longBitsToDouble(sp.getLong(key+"_min",Double.doubleToRawLongBits(0d)));
        return String.format(Locale.ROOT,"%s: %.3f ms prom · %.3f–%.3f ms · %d",label,sum/n,min,max,n);
    }
    public static String summary(Context c){
        if(c==null)return "Sin datos"; SharedPreferences sp=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String state=active(c)?("PRUEBA EXTREMA ACTIVA · "+Math.max(1,remainingMs(c)/1000)+" s") : "Última prueba extrema";
        return state+"\n"
                +line(sp,"event_fd","Evento → descriptor abierto")+"\n"
                +line(sp,"fd_first","Descriptor → primer byte")+"\n"
                +line(sp,"first_ready","Primer byte → staging seguro")+"\n"
                +line(sp,"ready_commit","Staging → Vault")+"\n"
                +line(sp,"event_end","Evento → resultado final");
    }
}
