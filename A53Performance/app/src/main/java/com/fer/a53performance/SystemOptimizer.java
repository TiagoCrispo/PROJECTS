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
    public record ProfileResult(int ok,int total,int verified,int rollbackVerified,int rollbackTotal){
        public boolean success(){return ok==total&&verified==total;}
        public boolean rollbackComplete(){return rollbackTotal>0&&rollbackVerified==rollbackTotal;}
    }
    private record ProfilePlan(float peak,float min,boolean useLow,boolean low,boolean useRestrict,boolean restrict){int total(){return 2+(useLow?1:0)+(useRestrict?1:0);}}
    private record SystemState(float peak,float min,int low,int restrict){}

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
            for(String pkg:candidates){
                if(gen!=ramGeneration.get())return;if(attempted>=40)break;attempted++;boolean ok=false;
                if(shell.permissionGranted()){ShizukuShell.Result r=shell.forceStopPackage(pkg);ok=r.ok();if(r.code()==-2)timeouts++;}
                else{try{am.killBackgroundProcesses(pkg);ok=true;}catch(Throwable ignored){}}
                if(ok)closed++;
            }
            SystemClock.sleep(550);long after=availableMemory(),delta=after-before;
            String msg="RAM disponible: "+FileAdapter.formatBytes(before)+" → "+FileAdapter.formatBytes(after)+". Apps tratadas: "+attempted+", cerradas: "+closed+(timeouts>0?", timeout: "+timeouts:"")+". "+(delta>0?"Cambio medido: +"+FileAdapter.formatBytes(delta):"Android no mostró una liberación neta medible; no se inventa un porcentaje.");
            cb.onDone(true,msg);
        });
    }

    public void applyProfile(Profile profile,Callback cb){
        int gen=profileGeneration.incrementAndGet();profileExecutor.execute(()->{
            if(gen!=profileGeneration.get())return;
            if(!shell.permissionGranted()){cb.onDone(false,"Shizuku necesita permiso para aplicar el perfil real.");return;}
            if(!shell.warmUp(1800)){cb.onDone(false,"No se pudo iniciar el servicio privilegiado de Shizuku.");return;}
            ProfileResult result=applyProfileSync(profile,shell,()->gen==profileGeneration.get());if(gen!=profileGeneration.get())return;
            if(result.success())prefs.edit().putString("last_profile",profile.name()).putLong("last_profile_ok",System.currentTimeMillis()).apply();
            String tail;
            if(result.success())tail=". Estado verificado.";
            else if(result.rollbackComplete())tail=". La aplicación parcial fue revertida y el estado anterior quedó verificado.";
            else if(result.rollbackTotal()>0)tail=". Se intentó restaurar el estado anterior: "+result.rollbackVerified()+"/"+result.rollbackTotal()+" ajustes confirmados.";
            else tail=". Android rechazó algún ajuste y no había estado previo legible para restaurarlo por completo.";
            cb.onDone(result.success(),"Perfil "+label(profile)+": "+result.ok()+"/"+result.total()+" aplicados · "+result.verified()+"/"+result.total()+" verificados"+tail);
        });
    }

    public static ProfileResult applyProfileSync(Profile profile,ShizukuShell shell){return applyProfileSync(profile,shell,()->true);}
    private static ProfileResult applyProfileSync(Profile profile,ShizukuShell shell,ContinueGate gate){
        ProfilePlan plan=plan(profile);SystemState before=snapshot(shell);int total=plan.total(),ok=0;
        if(gate.go()&&shell.setPeakRefreshRate(plan.peak()).ok())ok++;
        if(gate.go()&&shell.setMinRefreshRate(plan.min()).ok())ok++;
        if(plan.useLow()&&gate.go()&&shell.setLowPower(plan.low()).ok())ok++;
        if(plan.useRestrict()&&gate.go()&&shell.setRestrictBackground(plan.restrict()).ok())ok++;
        int verified=verifyTarget(plan,shell);
        if(ok==total&&verified==total)return new ProfileResult(ok,total,verified,0,0);
        int[] rollback=restorePrevious(plan,before,shell);
        return new ProfileResult(ok,total,verified,rollback[0],rollback[1]);
    }

    private static ProfilePlan plan(Profile profile){
        return switch(profile){
            case CLASS,BALANCED->new ProfilePlan(120f,60f,true,false,true,false);
            case GAMING,PERFORMANCE->new ProfilePlan(120f,120f,true,false,true,false);
            case COOL->new ProfilePlan(60f,60f,true,false,true,false);
            case BATTERY->new ProfilePlan(60f,60f,true,true,false,false);
            case DATA->new ProfilePlan(120f,60f,false,false,true,true);
        };
    }

    private static SystemState snapshot(ShizukuShell shell){return new SystemState(shell.getPeakRefreshRate(),shell.getMinRefreshRate(),shell.getLowPower(),shell.getRestrictBackground());}
    private static int verifyTarget(ProfilePlan p,ShizukuShell shell){
        int v=0;if(close(shell.getPeakRefreshRate(),p.peak()))v++;if(close(shell.getMinRefreshRate(),p.min()))v++;
        if(p.useLow()&&shell.getLowPower()==(p.low()?1:0))v++;
        if(p.useRestrict()&&shell.getRestrictBackground()==(p.restrict()?1:0))v++;
        return v;
    }

    private static int[] restorePrevious(ProfilePlan p,SystemState s,ShizukuShell shell){
        int total=0;
        if(s.peak()>=0f){total++;shell.setPeakRefreshRate(s.peak());}
        if(s.min()>=0f){total++;shell.setMinRefreshRate(s.min());}
        if(p.useLow()&&s.low()>=0){total++;shell.setLowPower(s.low()==1);}
        if(p.useRestrict()&&s.restrict()>=0){total++;shell.setRestrictBackground(s.restrict()==1);}
        int verified=0;
        if(s.peak()>=0f&&close(shell.getPeakRefreshRate(),s.peak()))verified++;
        if(s.min()>=0f&&close(shell.getMinRefreshRate(),s.min()))verified++;
        if(p.useLow()&&s.low()>=0&&shell.getLowPower()==s.low())verified++;
        if(p.useRestrict()&&s.restrict()>=0&&shell.getRestrictBackground()==s.restrict())verified++;
        return new int[]{verified,total};
    }

    private static boolean close(float a,float b){return a>=0f&&Math.abs(a-b)<=1.0f;}
    private interface ContinueGate{boolean go();}
    public void cancelRam(){ramGeneration.incrementAndGet();}
    public void cancelProfiles(){profileGeneration.incrementAndGet();}

    private Set<String> runningUserPackages(){
        LinkedHashSet<String> out=new LinkedHashSet<>();
        if(shell.permissionGranted()){
            ShizukuShell.Result r=shell.listRunningUserPackages();
            if(r.ok()&&r.output()!=null)for(String line:r.output().split("\\R")){String pkg=line.trim();if(!pkg.isBlank()&&!AppProtection.isProtected(app,pkg))out.add(pkg);}
        }
        return out;
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
        case DATA->"Activa Data Saver global. La protección por app sigue evitando acciones individuales sobre las apps protegidas.";
    };}
    public void shutdown(){cancelRam();cancelProfiles();ramExecutor.shutdownNow();profileExecutor.shutdownNow();}
}
