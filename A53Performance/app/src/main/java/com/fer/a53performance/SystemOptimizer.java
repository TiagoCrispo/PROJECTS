package com.fer.a53performance;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class SystemOptimizer {
    public interface Callback { void onDone(boolean ok,String message); }
    public enum Profile { CLASS,GAMING,PERFORMANCE,BALANCED,COOL,BATTERY,DATA }
    public record ProfileResult(int ok,int total,int verified){public boolean success(){return ok==total&&verified==total;}}

    private final Context app;
    private final ShizukuShell shell;
    private final SharedPreferences prefs;
    private final ExecutorService ramExecutor=Executors.newSingleThreadExecutor(r->new Thread(r,"a53-ram"));
    private final ExecutorService profileExecutor=Executors.newSingleThreadExecutor(r->new Thread(r,"a53-profile"));
    private final AtomicInteger ramGeneration=new AtomicInteger();
    private final AtomicInteger profileGeneration=new AtomicInteger();

    public SystemOptimizer(Context context,ShizukuShell shell){app=context.getApplicationContext();this.shell=shell;prefs=app.getSharedPreferences("a53_ui",Context.MODE_PRIVATE);}

    public void cleanRam(Callback cb){
        int gen=ramGeneration.incrementAndGet();ramExecutor.execute(()->{
            if(gen!=ramGeneration.get())return;long before=availableMemory();Set<String> candidates=runningUserPackages();int attempted=0,closed=0,timeouts=0;ActivityManager am=(ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE);
            for(String pkg:candidates){if(gen!=ramGeneration.get())return;if(attempted>=40)break;attempted++;boolean ok=false;if(shell.permissionGranted()){ShizukuShell.Result r=shell.forceStopPackage(pkg);ok=r.ok();if(r.code()==-2)timeouts++;}else{try{am.killBackgroundProcesses(pkg);ok=true;}catch(Throwable ignored){}}if(ok)closed++;}
            SystemClock.sleep(550);long after=availableMemory(),delta=after-before;String msg="RAM disponible: "+FileAdapter.formatBytes(before)+" → "+FileAdapter.formatBytes(after)+". Apps tratadas: "+attempted+", cerradas: "+closed+(timeouts>0?", timeout: "+timeouts:"")+". "+(delta>0?"Cambio medido: +"+FileAdapter.formatBytes(delta):"Android no mostró una liberación neta medible; no se inventa un porcentaje.");cb.onDone(true,msg);
        });
    }

    public void applyProfile(Profile profile,Callback cb){
        int gen=profileGeneration.incrementAndGet();profileExecutor.execute(()->{
            if(gen!=profileGeneration.get())return;if(!shell.permissionGranted()){cb.onDone(false,"Shizuku necesita permiso para aplicar el perfil real.");return;}if(!shell.warmUp(1800)){cb.onDone(false,"No se pudo iniciar el servicio privilegiado de Shizuku.");return;}
            ProfileResult result=applyProfileSync(profile,shell,()->gen==profileGeneration.get());if(gen!=profileGeneration.get())return;if(result.success())prefs.edit().putString("last_profile",profile.name()).putLong("last_profile_ok",System.currentTimeMillis()).apply();
            cb.onDone(result.success(),"Perfil "+label(profile)+": "+result.ok()+"/"+result.total()+" aplicados · "+result.verified()+"/"+result.total()+" verificados"+(result.success()?".":". Android rechazó o no confirmó algún ajuste."));
        });
    }

    public static ProfileResult applyProfileSync(Profile profile,ShizukuShell shell){return applyProfileSync(profile,shell,()->true);}
    private static ProfileResult applyProfileSync(Profile profile,ShizukuShell shell,ContinueGate gate){
        int ok=0,total=0;float peak=120f,min=60f;boolean low=false,restrict=false;boolean useRestrict=true;
        switch(profile){case CLASS,BALANCED->{peak=120f;min=60f;}case GAMING,PERFORMANCE->{peak=120f;min=120f;}case COOL->{peak=60f;min=60f;}case BATTERY->{peak=60f;min=60f;low=true;useRestrict=false;}case DATA->{peak=120f;min=60f;restrict=true;}}
        if(gate.go()){total++;if(shell.setPeakRefreshRate(peak).ok())ok++;}
        if(gate.go()){total++;if(shell.setMinRefreshRate(min).ok())ok++;}
        if(profile!=Profile.DATA&&gate.go()){total++;if(shell.setLowPower(low).ok())ok++;}
        if(useRestrict&&gate.go()){total++;if(shell.setRestrictBackground(restrict).ok())ok++;}
        int verified=0;
        if(close(shell.getPeakRefreshRate(),peak))verified++;
        if(close(shell.getMinRefreshRate(),min))verified++;
        if(profile!=Profile.DATA&&shell.getLowPower()==(low?1:0))verified++;
        if(useRestrict&&shell.getRestrictBackground()==(restrict?1:0))verified++;
        return new ProfileResult(ok,total,verified);
    }
    private static boolean close(float a,float b){return a>=0f&&Math.abs(a-b)<=1.0f;}
    private interface ContinueGate{boolean go();}

    public void cancelRam(){ramGeneration.incrementAndGet();}
    public void cancelProfiles(){profileGeneration.incrementAndGet();}
    private Set<String> runningUserPackages(){LinkedHashSet<String> out=new LinkedHashSet<>();if(shell.permissionGranted()){ShizukuShell.Result r=shell.listRunningUserPackages();if(r.ok()&&r.output()!=null)for(String line:r.output().split("\\R")){String pkg=line.trim();if(!pkg.isBlank()&&!AppProtection.isProtected(app,pkg))out.add(pkg);}}return out;}
    private long availableMemory(){ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();((ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(m);return m.availMem;}
    public static String label(Profile p){return switch(p){case CLASS->"Clases";case GAMING->"Gaming";case PERFORMANCE->"Rendimiento";case BALANCED->"Balanced";case COOL->"Cool";case BATTERY->"Batería";case DATA->"Datos";};}
    public static String description(Profile p){return switch(p){case CLASS->"Equilibrio 60–120 Hz para estudiar. No cierra ChatGPT, Brave, mensajería ni otras apps protegidas.";case GAMING->"Solicita 120 Hz y desactiva ahorro de batería. No promete overclock ni altera apps protegidas.";case PERFORMANCE->"Prioriza fluidez con 120 Hz y ahorro desactivado. Solo aplica ajustes reales disponibles.";case BALANCED->"60–120 Hz y datos normales para uso diario, sin restricciones por app.";case COOL->"Limita la pantalla a 60 Hz para reducir carga y calor; no inventa control directo de CPU/GPU.";case BATTERY->"60 Hz y ahorro de batería del sistema. Es un ajuste global de Android.";case DATA->"Activa Data Saver global. La protección por app sigue evitando acciones individuales sobre Gmail, Mensajes, Reloj, Brave, ChatGPT y Grabadora.";};}
    public void shutdown(){cancelRam();cancelProfiles();ramExecutor.shutdownNow();profileExecutor.shutdownNow();}
}
