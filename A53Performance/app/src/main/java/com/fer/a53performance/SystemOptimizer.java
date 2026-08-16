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
    public interface Callback{void onDone(boolean ok,String message);}
    public enum Profile{CLASS,GAMING,PERFORMANCE,BALANCED,COOL,BATTERY,DATA}
    public record ProfileResult(int ok,int total,int verified,int rollbackVerified,int rollbackTotal){public boolean success(){return ok==total&&verified==total;}public boolean rollbackComplete(){return rollbackTotal>0&&rollbackVerified==rollbackTotal;}}
    private record ProfilePlan(float peak,float min,boolean low,boolean restrict){int total(){return 4;}}
    private record SystemState(float peak,float min,int low,int restrict){}

    private final Context app;private final ShizukuShell shell;private final SharedPreferences prefs;
    private final ExecutorService ramExecutor=Executors.newSingleThreadExecutor(r->new Thread(r,"a53-ram"));
    private final ExecutorService profileExecutor=Executors.newSingleThreadExecutor(r->new Thread(r,"a53-profile"));
    private final AtomicInteger ramGeneration=new AtomicInteger(),profileGeneration=new AtomicInteger();

    public SystemOptimizer(Context context,ShizukuShell shell){app=context.getApplicationContext();this.shell=shell;prefs=app.getSharedPreferences("a53_ui",Context.MODE_PRIVATE);}

    public void cleanRam(Callback cb){
        int gen=ramGeneration.incrementAndGet();ramExecutor.execute(()->{
            if(gen!=ramGeneration.get())return;if(!shell.permissionGranted()){cb.onDone(false,"Shizuku es necesario para identificar y cerrar procesos de usuario con seguridad.");return;}if(!shell.selfTest()){cb.onDone(false,"Shizuku no respondió al autotest. No se cerró ninguna app.");return;}
            ShizukuShell.Result runningResult=shell.listRunningUserPackages(),sensitiveResult=shell.listSensitiveUserPackages();
            if(!runningResult.ok()||!sensitiveResult.ok()){cb.onDone(false,"No se pudo confirmar qué apps están activas/foreground. Por seguridad no se cerró ninguna app.");return;}
            long before=availableMemory();Set<String> running=parsePackages(runningResult),sensitive=parsePackages(sensitiveResult),candidates=new LinkedHashSet<>();
            for(String pkg:running)if(!sensitive.contains(pkg)&&!AppProtection.isProtected(app,pkg))candidates.add(pkg);
            int requested=0,timeouts=0;LinkedHashSet<String> attempted=new LinkedHashSet<>();
            for(String pkg:candidates){if(gen!=ramGeneration.get())return;if(requested>=40)break;requested++;attempted.add(pkg);ShizukuShell.Result r=shell.forceStopPackage(pkg);if(r.code()==-2)timeouts++;}
            SystemClock.sleep(700);ShizukuShell.Result afterResult=shell.listRunningUserPackages();int disappeared=-1;if(afterResult.ok()){Set<String> afterRunning=parsePackages(afterResult);disappeared=0;for(String pkg:attempted)if(!afterRunning.contains(pkg))disappeared++;}
            long after=availableMemory(),delta=after-before;
            String disappearedText=disappeared>=0?Integer.toString(disappeared):"no verificable";
            String msg="RAM disponible: "+FileAdapter.formatBytes(before)+" → "+FileAdapter.formatBytes(after)+". Solicitudes: "+requested+" · procesos que ya no aparecen: "+disappearedText+" · protegidos por actividad sensible: "+sensitive.size()+(timeouts>0?" · timeout: "+timeouts:"")+". "+(delta>0?"Cambio medido: +"+FileAdapter.formatBytes(delta):"Sin liberación neta medible; Android puede recrear procesos cuando los necesita.");
            cb.onDone(true,msg);
        });
    }

    public void applyProfile(Profile profile,Callback cb){
        int gen=profileGeneration.incrementAndGet();profileExecutor.execute(()->{
            if(gen!=profileGeneration.get())return;if(!shell.permissionGranted()){cb.onDone(false,"Shizuku necesita permiso para aplicar el perfil real.");return;}if(!shell.selfTest()){cb.onDone(false,"Shizuku no respondió al autotest. No se aplicó ningún ajuste.");return;}
            ProfileResult result=applyProfileSync(profile,shell,()->gen==profileGeneration.get());if(gen!=profileGeneration.get())return;if(result.success())prefs.edit().putString("last_profile",profile.name()).putLong("last_profile_ok",System.currentTimeMillis()).apply();
            String tail=result.success()?". Estado verificado.":result.rollbackComplete()?". La aplicación parcial fue revertida y el estado anterior quedó verificado.":result.rollbackTotal()>0?". Se intentó restaurar el estado anterior: "+result.rollbackVerified()+"/"+result.rollbackTotal()+" ajustes confirmados.":". Android rechazó algún ajuste y no había estado previo legible para restaurarlo por completo.";
            cb.onDone(result.success(),"Perfil "+label(profile)+": "+result.ok()+"/"+result.total()+" aplicados · "+result.verified()+"/"+result.total()+" verificados"+tail);
        });
    }

    public static ProfileResult applyProfileSync(Profile profile,ShizukuShell shell){return applyProfileSync(profile,shell,()->true);}
    private static ProfileResult applyProfileSync(Profile profile,ShizukuShell shell,ContinueGate gate){
        ProfilePlan p=plan(profile);SystemState before=snapshot(shell);int ok=0,total=p.total();
        if(gate.go()&&shell.setPeakRefreshRate(p.peak()).ok())ok++;if(gate.go()&&shell.setMinRefreshRate(p.min()).ok())ok++;if(gate.go()&&shell.setLowPower(p.low()).ok())ok++;if(gate.go()&&shell.setRestrictBackground(p.restrict()).ok())ok++;
        int verified=verifyTarget(p,shell);if(ok==total&&verified==total)return new ProfileResult(ok,total,verified,0,0);int[] rollback=restorePrevious(before,shell);return new ProfileResult(ok,total,verified,rollback[0],rollback[1]);
    }
    private static ProfilePlan plan(Profile p){return switch(p){case CLASS,BALANCED->new ProfilePlan(120f,60f,false,false);case GAMING,PERFORMANCE->new ProfilePlan(120f,120f,false,false);case COOL->new ProfilePlan(60f,60f,false,false);case BATTERY->new ProfilePlan(60f,60f,true,false);case DATA->new ProfilePlan(120f,60f,false,true);};}
    private static SystemState snapshot(ShizukuShell s){return new SystemState(s.getPeakRefreshRate(),s.getMinRefreshRate(),s.getLowPower(),s.getRestrictBackground());}
    private static int verifyTarget(ProfilePlan p,ShizukuShell s){int v=0;if(close(s.getPeakRefreshRate(),p.peak()))v++;if(close(s.getMinRefreshRate(),p.min()))v++;if(s.getLowPower()==(p.low()?1:0))v++;if(s.getRestrictBackground()==(p.restrict()?1:0))v++;return v;}
    private static int[] restorePrevious(SystemState x,ShizukuShell s){int total=0;if(x.peak()>=0){total++;s.setPeakRefreshRate(x.peak());}if(x.min()>=0){total++;s.setMinRefreshRate(x.min());}if(x.low()>=0){total++;s.setLowPower(x.low()==1);}if(x.restrict()>=0){total++;s.setRestrictBackground(x.restrict()==1);}int v=0;if(x.peak()>=0&&close(s.getPeakRefreshRate(),x.peak()))v++;if(x.min()>=0&&close(s.getMinRefreshRate(),x.min()))v++;if(x.low()>=0&&s.getLowPower()==x.low())v++;if(x.restrict()>=0&&s.getRestrictBackground()==x.restrict())v++;return new int[]{v,total};}
    private static boolean close(float a,float b){return a>=0f&&Math.abs(a-b)<=1.0f;}
    private interface ContinueGate{boolean go();}

    public void cancelRam(){ramGeneration.incrementAndGet();}public void cancelProfiles(){profileGeneration.incrementAndGet();}
    private static Set<String> parsePackages(ShizukuShell.Result r){LinkedHashSet<String> out=new LinkedHashSet<>();if(r!=null&&r.ok()&&r.output()!=null)for(String line:r.output().split("\\R")){String p=line.trim();if(!p.isBlank())out.add(p);}return out;}
    private long availableMemory(){ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();((ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(m);return m.availMem;}
    public static String label(Profile p){return switch(p){case CLASS->"Clases";case GAMING->"Gaming";case PERFORMANCE->"Rendimiento";case BALANCED->"Balanced";case COOL->"Cool";case BATTERY->"Batería";case DATA->"Datos";};}
    public static String description(Profile p){return switch(p){case CLASS->"60–120 Hz, ahorro y Data Saver desactivados. Conserva apps protegidas.";case GAMING->"120 Hz, ahorro y Data Saver desactivados. No altera CPU/GPU.";case PERFORMANCE->"120 Hz, ahorro y Data Saver desactivados para máxima fluidez disponible.";case BALANCED->"60–120 Hz, ahorro y Data Saver desactivados para uso diario.";case COOL->"60 Hz, ahorro y Data Saver desactivados; reduce carga de pantalla sin tocar CPU/GPU.";case BATTERY->"60 Hz, ahorro activado y Data Saver desactivado.";case DATA->"60–120 Hz, ahorro desactivado y Data Saver activado.";};}
    public void shutdown(){cancelRam();cancelProfiles();ramExecutor.shutdownNow();profileExecutor.shutdownNow();}
}
