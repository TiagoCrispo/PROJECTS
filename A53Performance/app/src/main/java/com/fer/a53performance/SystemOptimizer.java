package com.fer.a53performance;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class SystemOptimizer {
    public interface Callback { void onDone(boolean ok,String message); }
    public enum Profile { CLASS,GAMING,PERFORMANCE,BALANCED,COOL,BATTERY,DATA }
    public record ProfileResult(int ok,int total){public boolean success(){return ok==total;}}

    private final Context app;
    private final ShizukuShell shell;
    private final SharedPreferences prefs;
    private final ExecutorService ramExecutor=Executors.newSingleThreadExecutor(r->new Thread(r,"a53-ram"));
    private final ExecutorService profileExecutor=Executors.newSingleThreadExecutor(r->new Thread(r,"a53-profile"));
    private final AtomicInteger ramGeneration=new AtomicInteger();
    private final AtomicInteger profileGeneration=new AtomicInteger();

    public SystemOptimizer(Context context,ShizukuShell shell){app=context.getApplicationContext();this.shell=shell;prefs=app.getSharedPreferences("a53_ui",Context.MODE_PRIVATE);}

    public void cleanRam(Callback cb){
        int gen=ramGeneration.incrementAndGet();
        ramExecutor.execute(()->{
            if(gen!=ramGeneration.get())return;
            long before=availableMemory();Set<String> candidates=runningUserPackages();
            int attempted=0,closed=0,timeouts=0;ActivityManager am=(ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE);
            for(String pkg:candidates){
                if(gen!=ramGeneration.get())return;if(attempted>=40)break;attempted++;
                boolean ok=false;
                if(shell.permissionGranted()){
                    ShizukuShell.Result r=shell.forceStopPackage(pkg);ok=r.ok();if(r.code()==-2)timeouts++;
                }else{
                    try{am.killBackgroundProcesses(pkg);ok=true;}catch(Throwable ignored){}
                }
                if(ok)closed++;
            }
            SystemClock.sleep(550);long after=availableMemory(),delta=after-before;
            String msg="RAM disponible: "+FileAdapter.formatBytes(before)+" → "+FileAdapter.formatBytes(after)+
                    ". Apps tratadas: "+attempted+", cerradas: "+closed+(timeouts>0?", timeout: "+timeouts:"")+". "+
                    (delta>0?"Cambio medido: +"+FileAdapter.formatBytes(delta):"Android no mostró una liberación neta medible; no se inventa un porcentaje.");
            cb.onDone(true,msg);
        });
    }

    public void applyProfile(Profile profile,Callback cb){
        int gen=profileGeneration.incrementAndGet();
        profileExecutor.execute(()->{
            if(gen!=profileGeneration.get())return;
            if(!shell.permissionGranted()){cb.onDone(false,"Shizuku necesita permiso para aplicar el perfil real.");return;}
            if(!shell.warmUp(1800)){cb.onDone(false,"No se pudo iniciar el servicio privilegiado de Shizuku.");return;}
            ProfileResult result=applyProfileSync(profile,shell,()->gen==profileGeneration.get());
            if(gen!=profileGeneration.get())return;
            if(result.success())prefs.edit().putString("last_profile",profile.name()).apply();
            cb.onDone(result.success(),"Perfil "+label(profile)+" aplicado en segundo plano: "+result.ok()+"/"+result.total()+" ajustes"+(result.success()?".":". Algunos ajustes fueron rechazados por Android."));
        });
    }

    public static ProfileResult applyProfileSync(Profile profile,ShizukuShell shell){return applyProfileSync(profile,shell,()->true);}
    private static ProfileResult applyProfileSync(Profile profile,ShizukuShell shell,ContinueGate gate){
        int ok=0,total=0;
        switch(profile){
            case CLASS,BALANCED->{
                if(gate.go()){total++;if(shell.setPeakRefreshRate(120f).ok())ok++;}
                if(gate.go()){total++;if(shell.setMinRefreshRate(60f).ok())ok++;}
                if(gate.go()){total++;if(shell.setLowPower(false).ok())ok++;}
                if(gate.go()){total++;if(shell.setRestrictBackground(false).ok())ok++;}
            }
            case GAMING,PERFORMANCE->{
                if(gate.go()){total++;if(shell.setPeakRefreshRate(120f).ok())ok++;}
                if(gate.go()){total++;if(shell.setMinRefreshRate(120f).ok())ok++;}
                if(gate.go()){total++;if(shell.setLowPower(false).ok())ok++;}
                if(gate.go()){total++;if(shell.setRestrictBackground(false).ok())ok++;}
            }
            case COOL->{
                if(gate.go()){total++;if(shell.setPeakRefreshRate(60f).ok())ok++;}
                if(gate.go()){total++;if(shell.setMinRefreshRate(60f).ok())ok++;}
                if(gate.go()){total++;if(shell.setLowPower(false).ok())ok++;}
                if(gate.go()){total++;if(shell.setRestrictBackground(false).ok())ok++;}
            }
            case BATTERY->{
                if(gate.go()){total++;if(shell.setPeakRefreshRate(60f).ok())ok++;}
                if(gate.go()){total++;if(shell.setMinRefreshRate(60f).ok())ok++;}
                if(gate.go()){total++;if(shell.setLowPower(true).ok())ok++;}
            }
            case DATA->{
                if(gate.go()){total++;if(shell.setRestrictBackground(true).ok())ok++;}
                if(gate.go()){total++;if(shell.setPeakRefreshRate(120f).ok())ok++;}
                if(gate.go()){total++;if(shell.setMinRefreshRate(60f).ok())ok++;}
            }
        }
        return new ProfileResult(ok,total);
    }
    private interface ContinueGate{boolean go();}

    public void cancelRam(){ramGeneration.incrementAndGet();}
    public void cancelProfiles(){profileGeneration.incrementAndGet();}

    private Set<String> runningUserPackages(){
        LinkedHashSet<String> out=new LinkedHashSet<>();ActivityManager am=(ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE);
        try{
            List<ActivityManager.RunningAppProcessInfo> ps=am.getRunningAppProcesses();
            if(ps!=null)for(ActivityManager.RunningAppProcessInfo p:ps){
                if(p.importance<=ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)continue;
                if(p.pkgList!=null)for(String pkg:p.pkgList)addCandidate(out,pkg);
            }
        }catch(Throwable ignored){}
        if(out.size()<3&&shell.permissionGranted()){
            ShizukuShell.Result r=shell.listProcessNames();
            if(r.ok()&&r.output()!=null)for(String line:r.output().split("\\R")){
                String pkg=line.trim();int colon=pkg.indexOf(':');if(colon>0)pkg=pkg.substring(0,colon);if(pkg.contains("."))addCandidate(out,pkg);
            }
        }
        return out;
    }

    private void addCandidate(Set<String> out,String pkg){
        if(pkg==null||pkg.isBlank()||AppProtection.isProtected(app,pkg))return;
        try{ApplicationInfo ai=app.getPackageManager().getApplicationInfo(pkg,0);if((ai.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))==0)out.add(pkg);}catch(PackageManager.NameNotFoundException ignored){}
    }

    private long availableMemory(){ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();((ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(m);return m.availMem;}
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
