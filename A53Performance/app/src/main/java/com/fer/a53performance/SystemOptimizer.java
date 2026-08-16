package com.fer.a53performance;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class SystemOptimizer {
    public interface Callback { void onDone(boolean ok, String message); }
    public enum Profile { CLASS, GAMING, PERFORMANCE, BALANCED, COOL, BATTERY, DATA }

    private final Context app;
    private final ShizukuShell shell;
    private final ExecutorService ramExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r,"a53-ram"));
    private final ExecutorService profileExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r,"a53-profile"));
    private final AtomicInteger ramGeneration = new AtomicInteger();
    private final AtomicInteger profileGeneration = new AtomicInteger();

    public SystemOptimizer(Context context, ShizukuShell shell) { this.app=context.getApplicationContext();this.shell=shell; }

    public void cleanRam(Callback cb) {
        int gen=ramGeneration.incrementAndGet();
        ramExecutor.execute(() -> {
            if(gen!=ramGeneration.get())return;
            long before=availableMemory();
            Set<String> candidates=runningUserPackages();
            int attempted=0,closed=0,timeouts=0;
            ActivityManager am=(ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE);
            for(String pkg:candidates){
                if(gen!=ramGeneration.get())return;
                if(attempted>=40)break;
                attempted++;
                boolean ok=false;
                if(shell.permissionGranted()){
                    ShizukuShell.Result r=shell.exec("am force-stop "+safePackage(pkg),900);
                    ok=r.ok(); if(r.code()==-2)timeouts++;
                } else {
                    try{am.killBackgroundProcesses(pkg);ok=true;}catch(Throwable ignored){}
                }
                if(ok)closed++;
            }
            SystemClock.sleep(550);
            long after=availableMemory();
            long delta=after-before;
            String msg="RAM disponible: "+FileAdapter.formatBytes(before)+" → "+FileAdapter.formatBytes(after)+
                    ". Apps tratadas: "+attempted+", cerradas: "+closed+(timeouts>0?", timeout: "+timeouts:"")+". "+
                    (delta>0?"Cambio medido: +"+FileAdapter.formatBytes(delta):"Android no mostró una liberación neta medible; no se inventa un porcentaje.");
            cb.onDone(true,msg);
        });
    }

    public void applyProfile(Profile profile, Callback cb) {
        int gen=profileGeneration.incrementAndGet();
        profileExecutor.execute(() -> {
            if(gen!=profileGeneration.get())return;
            if(!shell.permissionGranted()){cb.onDone(false,"Shizuku necesita permiso para aplicar el perfil real.");return;}
            ArrayList<String> commands=new ArrayList<>();
            switch(profile){
                case CLASS, BALANCED -> {
                    commands.add("settings put system peak_refresh_rate 120.0");
                    commands.add("settings put system min_refresh_rate 60.0");
                    commands.add("settings put global low_power 0");
                    commands.add("cmd netpolicy set restrict-background false");
                }
                case GAMING, PERFORMANCE -> {
                    commands.add("settings put system peak_refresh_rate 120.0");
                    commands.add("settings put system min_refresh_rate 120.0");
                    commands.add("settings put global low_power 0");
                    commands.add("cmd netpolicy set restrict-background false");
                }
                case COOL -> {
                    commands.add("settings put system peak_refresh_rate 60.0");
                    commands.add("settings put system min_refresh_rate 60.0");
                    commands.add("settings put global low_power 0");
                    commands.add("cmd netpolicy set restrict-background false");
                }
                case BATTERY -> {
                    commands.add("settings put system peak_refresh_rate 60.0");
                    commands.add("settings put system min_refresh_rate 60.0");
                    commands.add("settings put global low_power 1");
                }
                case DATA -> {
                    commands.add("cmd netpolicy set restrict-background true");
                    commands.add("settings put system peak_refresh_rate 120.0");
                    commands.add("settings put system min_refresh_rate 60.0");
                }
            }
            int ok=0;
            for(String command:commands){
                if(gen!=profileGeneration.get())return;
                ShizukuShell.Result r=shell.exec(command,1400);
                if(r.ok())ok++;
            }
            if(gen!=profileGeneration.get())return;
            cb.onDone(ok==commands.size(),"Perfil "+label(profile)+" aplicado en segundo plano: "+ok+"/"+commands.size()+" ajustes"+(ok==commands.size()?".":". Algunos ajustes fueron rechazados por Android."));
        });
    }

    public void cancelRam(){ramGeneration.incrementAndGet();}
    public void cancelProfiles(){profileGeneration.incrementAndGet();}

    private Set<String> runningUserPackages(){
        LinkedHashSet<String> out=new LinkedHashSet<>();
        ActivityManager am=(ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE);
        try{
            List<ActivityManager.RunningAppProcessInfo> ps=am.getRunningAppProcesses();
            if(ps!=null)for(ActivityManager.RunningAppProcessInfo p:ps){
                if(p.importance<=ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)continue;
                if(p.pkgList!=null)for(String pkg:p.pkgList)addCandidate(out,pkg);
            }
        }catch(Throwable ignored){}
        if(out.size()<3 && shell.permissionGranted()){
            ShizukuShell.Result r=shell.exec("ps -A -o NAME",1500);
            if(r.ok()&&r.output()!=null)for(String line:r.output().split("\\R")){
                String pkg=line.trim();int colon=pkg.indexOf(':');if(colon>0)pkg=pkg.substring(0,colon);
                if(pkg.contains("."))addCandidate(out,pkg);
            }
        }
        return out;
    }

    private void addCandidate(Set<String> out,String pkg){
        if(pkg==null||pkg.isBlank()||AppProtection.isProtected(app,pkg))return;
        try{
            ApplicationInfo ai=app.getPackageManager().getApplicationInfo(pkg,0);
            if((ai.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))==0)out.add(pkg);
        }catch(PackageManager.NameNotFoundException ignored){}
    }

    private long availableMemory(){ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();((ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(m);return m.availMem;}
    private static String safePackage(String p){return p.replaceAll("[^A-Za-z0-9._]","");}
    public static String label(Profile p){return switch(p){case CLASS->"Clases";case GAMING->"Gaming";case PERFORMANCE->"Rendimiento";case BALANCED->"Balanced";case COOL->"Cool";case BATTERY->"Batería";case DATA->"Datos";};}
    public static String description(Profile p){return switch(p){
        case CLASS->"Equilibrio 60–120 Hz para estudiar. No cierra ChatGPT, Brave, mensajería ni otras apps protegidas.";
        case GAMING->"Solicita 120 Hz y desactiva ahorro de batería. No promete overclock ni altera apps protegidas.";
        case PERFORMANCE->"Prioriza fluidez con 120 Hz y ahorro desactivado. Solo aplica ajustes reales disponibles.";
        case BALANCED->"60–120 Hz y datos normales para uso diario, sin restricciones por app.";
        case COOL->"Limita la pantalla a 60 Hz para reducir carga y calor; no inventa control directo de CPU/GPU.";
        case BATTERY->"60 Hz y ahorro de batería del sistema. Es un ajuste global de Android.";
        case DATA->"Activa Data Saver global. La protección por app sigue evitando acciones individuales sobre Gmail, Mensajes, Reloj, Brave, ChatGPT y Grabadora.";
    };}
    public void shutdown(){cancelRam();cancelProfiles();ramExecutor.shutdownNow();profileExecutor.shutdownNow();}
}
