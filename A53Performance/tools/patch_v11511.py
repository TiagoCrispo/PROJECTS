from pathlib import Path
import re

ROOT=Path('A53Performance')
JAVA=ROOT/'app/src/main/java/com/fer/a53performance'
TEST=ROOT/'app/src/androidTest/java/com/fer/a53performance/StabilityInstrumentedTest.java'

# Version
p=ROOT/'app/build.gradle'
s=p.read_text()
s=s.replace("versionCode 32\n        versionName '1.15.10'","versionCode 33\n        versionName '1.15.11'")
p.write_text(s)

# Privileged service: commands now carry explicit success/failure sentinels so an empty dumpsys result can never masquerade as a safe result.
(JAVA/'PrivilegedUserService.java').write_text(r'''package com.fer.a53performance;

import android.content.Context;
import android.os.RemoteException;
import androidx.annotation.Keep;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrivilegedUserService extends IPrivilegedService.Stub {
    private static final long COMMAND_TIMEOUT_MS=1800L,READ_TIMEOUT_MS=2600L;
    private static final Pattern PACKAGE=Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final String RUNNING_OK="__A53_RUNNING_OK__",RUNNING_ERROR="__A53_RUNNING_ERROR__",SENSITIVE_OK="__A53_SENSITIVE_OK__",SENSITIVE_ERROR="__A53_SENSITIVE_ERROR__";

    public PrivilegedUserService() {}
    @Keep public PrivilegedUserService(Context context) {}
    @Override public void destroy(){System.exit(0);}
    @Override public int ping(){return 0;}

    @Override public int setPeakRefreshRate(float value)throws RemoteException{return validRefresh(value)?runFixed("settings","put","system","peak_refresh_rate",Float.toString(value)):-4;}
    @Override public int setMinRefreshRate(float value)throws RemoteException{return validRefresh(value)?runFixed("settings","put","system","min_refresh_rate",Float.toString(value)):-4;}
    @Override public int setLowPower(boolean enabled)throws RemoteException{return runFixed("settings","put","global","low_power",enabled?"1":"0");}
    @Override public int setRestrictBackground(boolean enabled)throws RemoteException{return runFixed("cmd","netpolicy","set","restrict-background",enabled?"true":"false");}
    @Override public int forceStopPackage(String packageName)throws RemoteException{return validPackage(packageName)?runFixed("am","force-stop",packageName):-4;}

    @Override public String listRunningUserPackages()throws RemoteException{
        PackageSetResult packages=userPackagesResult();if(!packages.ok())return RUNNING_ERROR;ReadResult ps=readFixedResult("ps","-A","-o","NAME");if(!ps.ok())return RUNNING_ERROR;
        StringBuilder out=new StringBuilder(RUNNING_OK).append('\n');Set<String> seen=new HashSet<>();
        for(String line:ps.output().split("\\R")){String pkg=line.trim();int colon=pkg.indexOf(':');if(colon>0)pkg=pkg.substring(0,colon);if(packages.packages().contains(pkg)&&seen.add(pkg))out.append(pkg).append('\n');}
        return out.toString();
    }

    @Override public String listSensitiveUserPackages()throws RemoteException{
        PackageSetResult packages=userPackagesResult();if(!packages.ok())return SENSITIVE_ERROR;Set<String> user=packages.packages(),sensitive=new HashSet<>();
        ReadResult services=readFixedResult("dumpsys","activity","services"),media=readFixedResult("dumpsys","media_session"),activities=readFixedResult("dumpsys","activity","activities");
        if(!services.ok()||!media.ok()||!activities.ok())return SENSITIVE_ERROR;
        String current=null;
        for(String line:services.output().split("\\R")){
            if(line.contains("ServiceRecord{"))current=knownPackage(line,user);
            else if(current!=null&&(line.contains("isForeground=true")||line.matches(".*foregroundId=[1-9][0-9]*.*")))sensitive.add(current);
        }
        current=null;
        for(String line:media.output().split("\\R")){
            String pkg=knownPackage(line,user);if(line.contains("package=")&&pkg!=null)current=pkg;String low=line.toLowerCase();if(current!=null&&(low.contains("state=3")||low.contains("state=6")||low.contains("playing")))sensitive.add(current);
        }
        for(String line:activities.output().split("\\R"))if(line.contains("mResumedActivity")||line.contains("topResumedActivity")||line.contains("mFocusedApp")){String pkg=knownPackage(line,user);if(pkg!=null)sensitive.add(pkg);}
        StringBuilder out=new StringBuilder(SENSITIVE_OK).append('\n');for(String pkg:sensitive)out.append(pkg).append('\n');return out.toString();
    }

    private static PackageSetResult userPackagesResult(){
        ReadResult packages=readFixedResult("pm","list","packages","-3");if(!packages.ok())return new PackageSetResult(false,Set.of());Set<String> user=new HashSet<>();for(String line:packages.output().split("\\R"))if(line.startsWith("package:")){String p=line.substring(8).trim();if(validPackage(p))user.add(p);}return new PackageSetResult(true,user);
    }
    private static String knownPackage(String line,Set<String> known){Matcher m=PACKAGE.matcher(line);while(m.find()){String p=m.group();if(known.contains(p))return p;}return null;}

    @Override public float getPeakRefreshRate()throws RemoteException{return parseFloat(readFixed("settings","get","system","peak_refresh_rate"));}
    @Override public float getMinRefreshRate()throws RemoteException{return parseFloat(readFixed("settings","get","system","min_refresh_rate"));}
    @Override public int getLowPower()throws RemoteException{return parseBoolInt(readFixed("settings","get","global","low_power"));}
    @Override public int getRestrictBackground()throws RemoteException{String s=readFixed("cmd","netpolicy","get","restrict-background").toLowerCase();if(s.contains("enabled")||s.trim().equals("true")||s.trim().equals("1"))return 1;if(s.contains("disabled")||s.trim().equals("false")||s.trim().equals("0"))return 0;return -1;}

    private static float parseFloat(String s){try{return Float.parseFloat(s.trim());}catch(Throwable ignored){return -1f;}}
    private static int parseBoolInt(String s){String v=s.trim();if("1".equals(v)||"true".equalsIgnoreCase(v))return 1;if("0".equals(v)||"false".equalsIgnoreCase(v))return 0;return -1;}
    private static boolean validRefresh(float value){return value>=30f&&value<=144f&&Float.isFinite(value);}
    private static boolean validPackage(String pkg){return pkg!=null&&pkg.length()<=180&&pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");}

    private static String readFixed(String...args){ReadResult r=readFixedResult(args);return r.ok()?r.output():"";}
    private static ReadResult readFixedResult(String...args){
        Process p=null;try{p=new ProcessBuilder(args).redirectErrorStream(true).start();final Process proc=p;StringBuilder out=new StringBuilder();Thread reader=new Thread(()->{try(BufferedReader br=new BufferedReader(new InputStreamReader(proc.getInputStream()))){String line;while((line=br.readLine())!=null&&out.length()<131072)out.append(line).append('\n');}catch(Throwable ignored){}},"a53-read");reader.setDaemon(true);reader.start();boolean done=p.waitFor(READ_TIMEOUT_MS,TimeUnit.MILLISECONDS);if(!done){p.destroyForcibly();return new ReadResult(false,"",-2);}try{reader.join(300L);}catch(InterruptedException e){Thread.currentThread().interrupt();return new ReadResult(false,"",-3);}int code=p.exitValue();return new ReadResult(code==0,out.toString().trim(),code);}catch(Throwable ignored){return new ReadResult(false,"",-3);}finally{if(p!=null)try{p.destroy();}catch(Throwable ignored){}}
    }
    private static int runFixed(String...args){Process p=null;try{p=new ProcessBuilder(args).redirectErrorStream(true).start();if(!p.waitFor(COMMAND_TIMEOUT_MS,TimeUnit.MILLISECONDS)){try{p.destroy();}catch(Throwable ignored){}try{if(!p.waitFor(120L,TimeUnit.MILLISECONDS))p.destroyForcibly();}catch(Throwable ignored){}return -2;}return p.exitValue();}catch(Throwable ignored){return -3;}finally{if(p!=null)try{p.destroy();}catch(Throwable ignored){}}}
    private record ReadResult(boolean ok,String output,int code){}
    private record PackageSetResult(boolean ok,Set<String> packages){}
}
''')

# RAM cleaner: explicit sentinels, global time budget and transport-failure breaker.
(JAVA/'SystemOptimizer.java').write_text(r'''package com.fer.a53performance;

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
    private static final String RUNNING_OK="__A53_RUNNING_OK__",SENSITIVE_OK="__A53_SENSITIVE_OK__";
    private static final long RAM_BUDGET_MS=12000L;
    private static final int MAX_TRANSPORT_FAILURES=2;

    private final Context app;private final ShizukuShell shell;private final SharedPreferences prefs;
    private final ExecutorService ramExecutor=Executors.newSingleThreadExecutor(r->new Thread(r,"a53-ram"));
    private final ExecutorService profileExecutor=Executors.newSingleThreadExecutor(r->new Thread(r,"a53-profile"));
    private final AtomicInteger ramGeneration=new AtomicInteger(),profileGeneration=new AtomicInteger();

    public SystemOptimizer(Context context,ShizukuShell shell){app=context.getApplicationContext();this.shell=shell;prefs=app.getSharedPreferences("a53_ui",Context.MODE_PRIVATE);}

    public void cleanRam(Callback cb){
        int gen=ramGeneration.incrementAndGet();ramExecutor.execute(()->{
            if(gen!=ramGeneration.get())return;if(!shell.permissionGranted()){cb.onDone(false,"Shizuku es necesario para identificar y cerrar procesos de usuario con seguridad.");return;}if(!shell.selfTest()){cb.onDone(false,"Shizuku no respondió al autotest. No se cerró ninguna app.");return;}
            ShizukuShell.Result runningResult=shell.listRunningUserPackages(),sensitiveResult=shell.listSensitiveUserPackages();
            if(!taggedOk(runningResult,RUNNING_OK)||!taggedOk(sensitiveResult,SENSITIVE_OK)){cb.onDone(false,"No se pudo verificar de forma completa qué apps están activas/foreground. Por seguridad no se cerró ninguna app.");return;}
            long started=SystemClock.elapsedRealtime(),before=availableMemory();Set<String> running=parsePackages(runningResult),sensitive=parsePackages(sensitiveResult),candidates=new LinkedHashSet<>();
            for(String pkg:running)if(!sensitive.contains(pkg)&&!AppProtection.isProtected(app,pkg))candidates.add(pkg);
            int requested=0,timeouts=0,transportFailures=0;boolean budgetStop=false,transportStop=false;LinkedHashSet<String> attempted=new LinkedHashSet<>();
            for(String pkg:candidates){
                if(gen!=ramGeneration.get())return;if(requested>=40)break;if(SystemClock.elapsedRealtime()-started>=RAM_BUDGET_MS){budgetStop=true;break;}
                requested++;attempted.add(pkg);ShizukuShell.Result r=shell.forceStopPackage(pkg);if(transportFailure(r)){transportFailures++;if(r.code()==-2)timeouts++;if(transportFailures>=MAX_TRANSPORT_FAILURES){transportStop=true;break;}}else transportFailures=0;
            }
            SystemClock.sleep(650);ShizukuShell.Result afterResult=shell.listRunningUserPackages();int disappeared=-1;if(taggedOk(afterResult,RUNNING_OK)){Set<String> afterRunning=parsePackages(afterResult);disappeared=0;for(String pkg:attempted)if(!afterRunning.contains(pkg))disappeared++;}
            long after=availableMemory(),delta=after-before;String disappearedText=disappeared>=0?Integer.toString(disappeared):"no verificable";
            String stop=transportStop?" · detenida por fallos consecutivos de Shizuku":budgetStop?" · detenida al alcanzar el presupuesto seguro de 12 s":"";
            String msg="RAM disponible: "+FileAdapter.formatBytes(before)+" → "+FileAdapter.formatBytes(after)+". Solicitudes: "+requested+" · procesos que ya no aparecen: "+disappearedText+" · protegidos por actividad sensible: "+sensitive.size()+(timeouts>0?" · timeout: "+timeouts:"")+stop+". "+(delta>0?"Cambio medido: +"+FileAdapter.formatBytes(delta):"Sin liberación neta medible; Android puede recrear procesos cuando los necesita.");
            cb.onDone(!transportStop,msg);
        });
    }

    static boolean taggedOk(ShizukuShell.Result r,String tag){return r!=null&&r.ok()&&r.output()!=null&&(r.output().equals(tag)||r.output().startsWith(tag+"\n"));}
    private static boolean transportFailure(ShizukuShell.Result r){return r==null||r.code()==-2||r.code()==-3||r.code()==-5;}

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
    private static Set<String> parsePackages(ShizukuShell.Result r){LinkedHashSet<String> out=new LinkedHashSet<>();if(r!=null&&r.ok()&&r.output()!=null)for(String line:r.output().split("\\R")){String p=line.trim();if(!p.isBlank()&&!p.startsWith("__A53_"))out.add(p);}return out;}
    private long availableMemory(){ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();((ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(m);return m.availMem;}
    public static String label(Profile p){return switch(p){case CLASS->"Clases";case GAMING->"Gaming";case PERFORMANCE->"Rendimiento";case BALANCED->"Balanced";case COOL->"Cool";case BATTERY->"Batería";case DATA->"Datos";};}
    public static String description(Profile p){return switch(p){case CLASS->"60–120 Hz, ahorro y Data Saver desactivados. Conserva apps protegidas.";case GAMING->"120 Hz, ahorro y Data Saver desactivados. No altera CPU/GPU.";case PERFORMANCE->"120 Hz, ahorro y Data Saver desactivados para máxima fluidez disponible.";case BALANCED->"60–120 Hz, ahorro y Data Saver desactivados para uso diario.";case COOL->"60 Hz, ahorro y Data Saver desactivados; reduce carga de pantalla sin tocar CPU/GPU.";case BATTERY->"60 Hz, ahorro activado y Data Saver desactivado.";case DATA->"60–120 Hz, ahorro desactivado y Data Saver activado.";};}
    public void shutdown(){cancelRam();cancelProfiles();ramExecutor.shutdownNow();profileExecutor.shutdownNow();}
}
''')

# Cache v4 adds a compact color/contrast signature used as the third perceptual gate.
(JAVA/'AnalysisCacheDb.java').write_text(r'''package com.fer.a53performance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.Set;

public final class AnalysisCacheDb extends SQLiteOpenHelper {
    private static final String DB="analysis_cache.db";
    private static final int VERSION=4;
    private static final long MAX_LIVE_BYTES=32L*1024L*1024L;
    private static final long TARGET_LIVE_BYTES=24L*1024L*1024L;

    public AnalysisCacheDb(Context context){super(context.getApplicationContext(),DB,null,VERSION);}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE cache(k TEXT PRIMARY KEY,size INTEGER NOT NULL,modified INTEGER NOT NULL,quick TEXT,sha TEXT,dhash INTEGER,ahash INTEGER,aspect INTEGER,colorsig INTEGER,updated INTEGER NOT NULL)");db.execSQL("CREATE INDEX idx_cache_updated ON cache(updated)");}
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){if(oldVersion<2){try{db.execSQL("ALTER TABLE cache ADD COLUMN quick TEXT");}catch(Throwable ignored){}}if(oldVersion<3){try{db.execSQL("ALTER TABLE cache ADD COLUMN ahash INTEGER");}catch(Throwable ignored){}try{db.execSQL("ALTER TABLE cache ADD COLUMN aspect INTEGER");}catch(Throwable ignored){}}if(oldVersion<4){try{db.execSQL("ALTER TABLE cache ADD COLUMN colorsig INTEGER");}catch(Throwable ignored){}}}

    public synchronized String getQuick(StorageItem item){return getText(item,"quick");}public synchronized String getSha(StorageItem item){return getText(item,"sha");}
    private String getText(StorageItem item,String column){try(Cursor c=getReadableDatabase().query("cache",new String[]{column,"size","modified"},"k=?",new String[]{item.stableKey()},null,null,null)){if(!c.moveToFirst())return null;if(c.getLong(1)!=item.size||c.getLong(2)!=item.modified)return null;return c.isNull(0)?null:c.getString(0);}}
    public synchronized Long getDHash(StorageItem item){return getLong(item,"dhash");}public synchronized Long getAHash(StorageItem item){return getLong(item,"ahash");}public synchronized Long getColorSig(StorageItem item){return getLong(item,"colorsig");}public synchronized Integer getAspect(StorageItem item){Long v=getLong(item,"aspect");return v==null?null:v.intValue();}
    private Long getLong(StorageItem item,String column){try(Cursor c=getReadableDatabase().query("cache",new String[]{column,"size","modified"},"k=?",new String[]{item.stableKey()},null,null,null)){if(!c.moveToFirst())return null;if(c.getLong(1)!=item.size||c.getLong(2)!=item.modified)return null;return c.isNull(0)?null:c.getLong(0);}}

    public synchronized void putQuick(StorageItem item,String quick){upsert(item,quick,null,null,null,null,null);}public synchronized void putSha(StorageItem item,String sha){upsert(item,null,sha,null,null,null,null);}public synchronized void putDHash(StorageItem item,long hash){upsert(item,null,null,hash,null,null,null);}
    public synchronized void putVisual(StorageItem item,long dhash,long ahash,int aspect){putVisual(item,dhash,ahash,aspect,0L);}public synchronized void putVisual(StorageItem item,long dhash,long ahash,int aspect,long colorSig){upsert(item,null,null,dhash,ahash,aspect,colorSig);}

    private void upsert(StorageItem item,String quick,String sha,Long dhash,Long ahash,Integer aspect,Long colorSig){
        SQLiteDatabase db=getWritableDatabase();String eq=null,es=null;Long ed=null,ea=null,ep=null,ec=null;
        try(Cursor c=db.query("cache",new String[]{"quick","sha","dhash","ahash","aspect","colorsig","size","modified"},"k=?",new String[]{item.stableKey()},null,null,null)){
            if(c.moveToFirst()&&c.getLong(6)==item.size&&c.getLong(7)==item.modified){eq=c.isNull(0)?null:c.getString(0);es=c.isNull(1)?null:c.getString(1);ed=c.isNull(2)?null:c.getLong(2);ea=c.isNull(3)?null:c.getLong(3);ep=c.isNull(4)?null:c.getLong(4);ec=c.isNull(5)?null:c.getLong(5);}
        }
        ContentValues v=new ContentValues();v.put("k",item.stableKey());v.put("size",item.size);v.put("modified",item.modified);v.put("updated",System.currentTimeMillis());if(quick!=null)v.put("quick",quick);else if(eq!=null)v.put("quick",eq);else v.putNull("quick");if(sha!=null)v.put("sha",sha);else if(es!=null)v.put("sha",es);else v.putNull("sha");Long d=dhash!=null?dhash:ed;if(d!=null)v.put("dhash",d);else v.putNull("dhash");Long a=ahash!=null?ahash:ea;if(a!=null)v.put("ahash",a);else v.putNull("ahash");Long q=aspect!=null?Long.valueOf(aspect):ep;if(q!=null)v.put("aspect",q);else v.putNull("aspect");Long cs=colorSig!=null?colorSig:ec;if(cs!=null)v.put("colorsig",cs);else v.putNull("colorsig");db.insertWithOnConflict("cache",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void prune(){prune(Set.of());}
    public synchronized void prune(Set<String> existingKeys){SQLiteDatabase db=getWritableDatabase();long cutoff=System.currentTimeMillis()-75L*24L*60L*60L*1000L;db.delete("cache","updated<?",new String[]{Long.toString(cutoff)});if(existingKeys!=null&&!existingKeys.isEmpty()){ArrayList<String> stale=new ArrayList<>();try(Cursor c=db.query("cache",new String[]{"k"},null,null,null,null,null)){while(c.moveToNext()){String k=c.getString(0);if(!existingKeys.contains(k))stale.add(k);}}db.beginTransaction();try{for(String k:stale)db.delete("cache","k=?",new String[]{k});db.setTransactionSuccessful();}finally{db.endTransaction();}}long used=liveBytes(db);int guard=0;while(used>MAX_LIVE_BYTES&&guard++<20){long before=count(db);db.execSQL("DELETE FROM cache WHERE k IN (SELECT k FROM cache ORDER BY updated ASC LIMIT 2000)");long after=count(db);if(after>=before)break;used=liveBytes(db);if(used<=TARGET_LIVE_BYTES)break;}}
    public synchronized long estimatedLiveBytes(){return liveBytes(getReadableDatabase());}private static long count(SQLiteDatabase db){try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM cache",null)){return c.moveToFirst()?c.getLong(0):0L;}}private static long liveBytes(SQLiteDatabase db){long pages=pragma(db,"page_count"),free=pragma(db,"freelist_count"),pageSize=pragma(db,"page_size");return Math.max(0L,pages-free)*Math.max(1L,pageSize);}private static long pragma(SQLiteDatabase db,String name){try(Cursor c=db.rawQuery("PRAGMA "+name,null)){return c.moveToFirst()?c.getLong(0):0L;}catch(Throwable ignored){return 0L;}}
}
''')

# Storage repository: live-count validation removes stale deleted rows immediately after MediaStore changes; volume labels distinguish SD/USB/other external media.
p=JAVA/'StorageRepository.java';s=p.read_text()
s=s.replace('import java.util.concurrent.Executors;','import java.util.concurrent.Executors;\nimport java.util.concurrent.ConcurrentHashMap;')
s=s.replace('private static final String LAST_FULL="last_full_reconcile_v11510";','private static final String LAST_FULL="last_full_reconcile_v11510";\n    private static final ConcurrentHashMap<String,String> VOLUME_LABELS=new ConcurrentHashMap<>();')
s=s.replace('public StorageRepository(Context context){app=context.getApplicationContext();state=app.getSharedPreferences(STATE_PREFS,Context.MODE_PRIVATE);indexDb=new StorageIndexDb(app);try{master.addAll(indexDb.load());}catch(Throwable ignored){}}','public StorageRepository(Context context){app=context.getApplicationContext();state=app.getSharedPreferences(STATE_PREFS,Context.MODE_PRIVATE);indexDb=new StorageIndexDb(app);try{master.addAll(indexDb.load());}catch(Throwable ignored){}volumeStats();}')
old='''            if(currentGen>=0&&previous>=0&&!prior.isEmpty()){
                List<StorageItem> merged=forceFull?scanVolumeReconciled(volume,previous,generation,prior):scanVolumeDelta(volume,previous,generation,prior);
                if(merged!=null){out.addAll(merged);continue;}
            }'''
new='''            if(currentGen>=0&&previous>=0&&!prior.isEmpty()){
                List<StorageItem> merged=forceFull?scanVolumeReconciled(volume,previous,generation,prior):scanVolumeDelta(volume,previous,generation,prior);int liveCount=safeLiveCount(volume);
                if(merged!=null&&liveCount>=0&&merged.size()!=liveCount)merged=scanVolumeReconciled(volume,previous,generation,prior);
                if(merged!=null){out.addAll(merged);continue;}
            }'''
if old not in s: raise SystemExit('storage incremental block missing')
s=s.replace(old,new,1)
anchor='    private boolean scanVolumeFull(String volume,int generation,List<StorageItem> out){String selection=Build.VERSION.SDK_INT>=30?MediaStore.MediaColumns.IS_TRASHED+"=0":null;return scanVolumeQuery(volume,generation,out,selection,null);}'
insert='''    private int safeLiveCount(String volume){if(Build.VERSION.SDK_INT<30)return-1;ContentResolver cr=app.getContentResolver();Uri base=MediaStore.Files.getContentUri(volume);String alive=MediaStore.MediaColumns.IS_TRASHED+"=0";try(Cursor c=cr.query(base,new String[]{MediaStore.Files.FileColumns._ID},alive,null,null)){return c==null?-1:c.getCount();}catch(Throwable ignored){return-1;}}\n\n'''
if anchor not in s: raise SystemExit('storage full anchor missing')
s=s.replace(anchor,insert+anchor,1)
start=s.index('    public List<VolumeStats> volumeStats(){')
end=s.index('    public void cancelScan()',start)
replacement=r'''    public List<VolumeStats> volumeStats(){
        ArrayList<VolumeStats> out=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=30){
            try{StorageManager sm=(StorageManager)app.getSystemService(Context.STORAGE_SERVICE);for(StorageVolume sv:sm.getStorageVolumes()){File dir=sv.getDirectory();String volume=sv.getMediaStoreVolumeName();if(dir==null||volume==null||volume.isBlank())continue;String st=sv.getState();if(!Environment.MEDIA_MOUNTED.equals(st)&&!Environment.MEDIA_MOUNTED_READ_ONLY.equals(st))continue;StatFs fs=new StatFs(dir.getAbsolutePath());String label=labelFor(sv);VOLUME_LABELS.put(volume,label);out.add(new VolumeStats(volume,label,fs.getTotalBytes(),fs.getAvailableBytes(),sv.isRemovable()));}}catch(Throwable ignored){}
        }
        if(out.isEmpty())try{StatFs fs=new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());VOLUME_LABELS.put("external","Interno");VOLUME_LABELS.put(MediaStore.VOLUME_EXTERNAL_PRIMARY,"Interno");out.add(new VolumeStats("external","Interno",fs.getTotalBytes(),fs.getAvailableBytes(),false));}catch(Throwable ignored){}
        out.sort((a,b)->Boolean.compare(a.removable(),b.removable()));return out;
    }
    private String labelFor(StorageVolume sv){if(sv.isPrimary())return"Interno";String d="";try{d=sv.getDescription(app);}catch(Throwable ignored){}String low=d==null?"":d.toLowerCase(Locale.ROOT);if(low.contains("usb"))return"USB";if(low.contains("sd")||low.contains("tarjeta")||low.contains("card"))return"microSD";return d==null||d.isBlank()?"Externo":d;}
    public String spaceSummary(){List<VolumeStats> stats=volumeStats();if(stats.isEmpty())return"Espacio no disponible";StringBuilder b=new StringBuilder();for(VolumeStats s:stats){if(b.length()>0)b.append("\n");b.append(s.label()).append(": ").append(FileAdapter.formatBytes(s.used())).append(" usados · ").append(FileAdapter.formatBytes(s.free())).append(" libres / ").append(FileAdapter.formatBytes(s.total()));}return b.toString();}
    public static boolean isPrimaryVolumeName(String volume){return volume==null||volume.isBlank()||"external".equals(volume)||MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume);}
    public static String volumeLabel(StorageItem item){if(item==null||isPrimaryVolumeName(item.volume))return"Interno";return VOLUME_LABELS.getOrDefault(item.volume,"Externo");}
    public static boolean matchesVolume(StorageItem item,int mode){if(mode==1)return isPrimaryVolumeName(item.volume);if(mode==2)return !isPrimaryVolumeName(item.volume);return true;}

'''
s=s[:start]+replacement+s[end:]
p.write_text(s)

# Visual analyzer: EXIF normalization + cached color/contrast signature + third gate.
p=JAVA/'StorageAnalyzer.java';s=p.read_text()
s=s.replace('import android.graphics.Color;','import android.graphics.Color;\nimport android.graphics.Matrix;\nimport android.media.ExifInterface;')
s=s.replace('photos.add(new PhotoSig(x,sig.dhash,sig.ahash,sig.aspect))','photos.add(new PhotoSig(x,sig.dhash,sig.ahash,sig.aspect,sig.colorSig))')
s=s.replace('callback.onPhase("Agrupando "+photos.size()+" fotos con doble hash perceptual…")','callback.onPhase("Agrupando "+photos.size()+" fotos con triple verificación perceptual…")')
old='int ad=Long.bitCount(p.ahash^group.rep.ahash);if(ad>10)continue;int score=dd*2+ad+aspectDiff*2;'
new='int ad=Long.bitCount(p.ahash^group.rep.ahash);if(ad>10)continue;if(!colorCompatible(p.colorSig,group.rep.colorSig))continue;int score=dd*2+ad+aspectDiff*2+colorDistance(p.colorSig,group.rep.colorSig)/24;'
if old not in s: raise SystemExit('similar score block missing')
s=s.replace(old,new,1)
anchor='    private static long bucketKey(int seg,int value,int aspect){return(((long)aspect&0xffffL)<<16)|((seg&0xffL)<<8)|(value&0xffL);}'
helper='''    private static int colorDistance(long a,long b){int ar=(int)((a>>>24)&255),ag=(int)((a>>>16)&255),ab=(int)((a>>>8)&255),br=(int)((b>>>24)&255),bg=(int)((b>>>16)&255),bb=(int)((b>>>8)&255);return Math.abs(ar-br)+Math.abs(ag-bg)+Math.abs(ab-bb);}\n    private static boolean colorCompatible(long a,long b){int stdA=(int)(a&255),stdB=(int)(b&255);return colorDistance(a,b)<=140&&Math.abs(stdA-stdB)<=50;}\n'''
if anchor not in s: raise SystemExit('bucket anchor missing')
s=s.replace(anchor,helper+anchor,1)
old='private VisualSig visualSigCached(StorageItem item){Long d=cache.getDHash(item),a=cache.getAHash(item);Integer aspect=cache.getAspect(item);if(d!=null&&a!=null&&aspect!=null)return new VisualSig(d,a,aspect);VisualSig v=visualSig(item);if(v!=null)cache.putVisual(item,v.dhash,v.ahash,v.aspect);return v;}'
new='private VisualSig visualSigCached(StorageItem item){Long d=cache.getDHash(item),a=cache.getAHash(item),c=cache.getColorSig(item);Integer aspect=cache.getAspect(item);if(d!=null&&a!=null&&aspect!=null&&c!=null)return new VisualSig(d,a,aspect,c);VisualSig v=visualSig(item);if(v!=null)cache.putVisual(item,v.dhash,v.ahash,v.aspect,v.colorSig);return v;}'
if old not in s: raise SystemExit('visual cache block missing')
s=s.replace(old,new,1)
pattern=r'    private VisualSig visualSig\(StorageItem item\)\{.*?\}\n    private InputStream open\(StorageItem item\)'
m=re.search(pattern,s,flags=re.S)
if not m: raise SystemExit('visual method missing')
visual=r'''    private VisualSig visualSig(StorageItem item){
        Bitmap decoded=null,oriented=null,tiny9=null,tiny8=null;try{
            int orientation=ExifInterface.ORIENTATION_NORMAL;try(InputStream exifIn=open(item)){if(exifIn!=null)orientation=new ExifInterface(exifIn).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);}catch(Throwable ignored){}
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;try(InputStream in=open(item)){if(in==null)return null;BitmapFactory.decodeStream(in,null,bounds);}int w=bounds.outWidth,h=bounds.outHeight;if(w<=0||h<=0)return null;boolean swap=orientation==ExifInterface.ORIENTATION_TRANSPOSE||orientation==ExifInterface.ORIENTATION_ROTATE_90||orientation==ExifInterface.ORIENTATION_TRANSVERSE||orientation==ExifInterface.ORIENTATION_ROTATE_270;int ow=swap?h:w,oh=swap?w:h;int aspect=Math.round((ow/(float)oh)*100f);int max=Math.max(w,h),sample=1;while(max/sample>192&&sample<128)sample<<=1;
            BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=sample;opts.inPreferredConfig=Bitmap.Config.RGB_565;try(InputStream in=open(item)){if(in==null)return null;decoded=BitmapFactory.decodeStream(in,null,opts);}if(decoded==null)return null;oriented=applyOrientation(decoded,orientation);Bitmap src=oriented==null?decoded:oriented;
            tiny9=Bitmap.createScaledBitmap(src,9,8,true);long dh=0L;int bit=0;for(int y=0;y<8;y++)for(int x=0;x<8;x++){if(luminance(tiny9.getPixel(x,y))>luminance(tiny9.getPixel(x+1,y)))dh|=(1L<<bit);bit++;}
            tiny8=Bitmap.createScaledBitmap(src,8,8,true);int[] lum=new int[64];long sum=0,sumSq=0,sumR=0,sumG=0,sumB=0;for(int y=0;y<8;y++)for(int x=0;x<8;x++){int i=y*8+x,c=tiny8.getPixel(x,y),l=luminance(c);lum[i]=l;sum+=l;sumSq+=(long)l*l;sumR+=Color.red(c);sumG+=Color.green(c);sumB+=Color.blue(c);}int avg=(int)(sum/64L);long ah=0L;for(int i=0;i<64;i++)if(lum[i]>=avg)ah|=(1L<<i);double variance=Math.max(0d,sumSq/64d-avg*avg);int std=(int)Math.min(255,Math.round(Math.sqrt(variance)));int ar=(int)(sumR/64L),ag=(int)(sumG/64L),ab=(int)(sumB/64L);long colorSig=((long)ar<<24)|((long)ag<<16)|((long)ab<<8)|(std&255L);return new VisualSig(dh,ah,aspect,colorSig);
        }catch(Throwable ignored){return null;}finally{if(tiny9!=null&&tiny9!=decoded&&tiny9!=oriented&&!tiny9.isRecycled())tiny9.recycle();if(tiny8!=null&&tiny8!=decoded&&tiny8!=oriented&&!tiny8.isRecycled())tiny8.recycle();if(oriented!=null&&oriented!=decoded&&!oriented.isRecycled())oriented.recycle();if(decoded!=null&&!decoded.isRecycled())decoded.recycle();}}
    private static Bitmap applyOrientation(Bitmap src,int orientation){if(src==null)return null;Matrix m=new Matrix();switch(orientation){case ExifInterface.ORIENTATION_FLIP_HORIZONTAL->m.setScale(-1f,1f);case ExifInterface.ORIENTATION_ROTATE_180->m.setRotate(180f);case ExifInterface.ORIENTATION_FLIP_VERTICAL->m.setScale(1f,-1f);case ExifInterface.ORIENTATION_TRANSPOSE->{m.setRotate(90f);m.postScale(-1f,1f);}case ExifInterface.ORIENTATION_ROTATE_90->m.setRotate(90f);case ExifInterface.ORIENTATION_TRANSVERSE->{m.setRotate(-90f);m.postScale(-1f,1f);}case ExifInterface.ORIENTATION_ROTATE_270->m.setRotate(-90f);default->{return src;}}try{return Bitmap.createBitmap(src,0,0,src.getWidth(),src.getHeight(),m,true);}catch(Throwable ignored){return src;}}
    private InputStream open(StorageItem item)'''
s=s[:m.start()]+visual+s[m.end():]
s=s.replace('private record VisualSig(long dhash,long ahash,int aspect){}','private record VisualSig(long dhash,long ahash,int aspect,long colorSig){}')
s=s.replace('private record PhotoSig(StorageItem item,long dhash,long ahash,int aspect){}','private record PhotoSig(StorageItem item,long dhash,long ahash,int aspect,long colorSig){}')
p.write_text(s)

# UI text only; duplicate keeper remains intentionally session-only (requested: do NOT implement persistence point 7).
p=JAVA/'MainActivity.java';s=p.read_text()
s=s.replace('new String[]{"Todos los almacenamientos","Interno","microSD"}','new String[]{"Todos los almacenamientos","Interno","Externo (SD/USB)"}')
s=s.replace('Interno y microSD se miden por separado cuando están disponibles.','Interno y almacenamientos externos se identifican y miden por separado.')
s=s.replace('v1.15.10 DEVICE HARDENING','v1.15.11 SAFE CLEANER / VISUAL ACCURACY')
s=s.replace('RAM fail-safe, Shizuku con detección de caída, almacenamiento Interno/microSD visible y duplicados con copia a conservar elegible.','RAM fail-safe estricto, reconciliación de archivos borrados, SD/USB diferenciados y fotos similares con EXIF + triple verificación visual.')
p.write_text(s)

# Tests: cache color signature, external generic fallback, real visual cases.
p=TEST;s=p.read_text()
s=s.replace('import android.content.Context;','import android.content.Context;\nimport android.graphics.Bitmap;\nimport android.graphics.Canvas;\nimport android.graphics.Color;\nimport android.graphics.Matrix;\nimport android.graphics.Paint;\nimport android.media.ExifInterface;')
s=s.replace('db.putVisual(item,123456789L,987654321L,177);','db.putVisual(item,123456789L,987654321L,177,0x10203040L);')
s=s.replace('assertEquals(Integer.valueOf(177),db.getAspect(item));assertTrue(db.estimatedLiveBytes()>0);','assertEquals(Integer.valueOf(177),db.getAspect(item));assertEquals(Long.valueOf(0x10203040L),db.getColorSig(item));assertTrue(db.estimatedLiveBytes()>0);')
s=s.replace('assertEquals("microSD",StorageRepository.volumeLabel(sd));','assertEquals("Externo",StorageRepository.volumeLabel(sd));')
insert=r'''
    @Test public void similarAnalyzerNormalizesExifAndRecompression()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File exif=new File(context.getCacheDir(),"visual_exif.jpg"),physical=new File(context.getCacheDir(),"visual_physical.jpg"),recompressed=new File(context.getCacheDir(),"visual_recompressed.jpg");Bitmap base=patternBitmap(240,160,false),rotated=null;StorageAnalyzer analyzer=new StorageAnalyzer(context);
        try{writeJpeg(base,exif,92);ExifInterface ei=new ExifInterface(exif.getAbsolutePath());ei.setAttribute(ExifInterface.TAG_ORIENTATION,Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));ei.saveAttributes();Matrix m=new Matrix();m.setRotate(90f);rotated=Bitmap.createBitmap(base,0,0,base.getWidth(),base.getHeight(),m,true);writeJpeg(rotated,physical,88);writeJpeg(rotated,recompressed,62);ArrayList<StorageItem> items=new ArrayList<>();items.add(localImage(exif,1));items.add(localImage(physical,2));items.add(localImage(recompressed,3));StorageAnalyzer.DuplicateResult none=new StorageAnalyzer.DuplicateResult(Set.of(),List.of(),Set.of(),0L);CountDownLatch latch=new CountDownLatch(1);AtomicReference<StorageAnalyzer.SimilarResult> ref=new AtomicReference<>();analyzer.analyzeSimilarAsync(items,none,new StorageAnalyzer.Callback<>(){public void onPhase(String p){}public void onDone(StorageAnalyzer.SimilarResult r){ref.set(r);latch.countDown();}});assertTrue(latch.await(20,TimeUnit.SECONDS));StorageAnalyzer.SimilarResult r=ref.get();assertNotNull(r);assertTrue("expected EXIF/recompressed images to group",r.groups>=1);assertTrue(r.keys.size()>=2);}finally{analyzer.shutdown();if(rotated!=null&&!rotated.isRecycled())rotated.recycle();if(!base.isRecycled())base.recycle();exif.delete();physical.delete();recompressed.delete();}}

    @Test public void similarAnalyzerColorGateRejectsDifferentFlatImages()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File dark=new File(context.getCacheDir(),"flat_dark.jpg"),bright=new File(context.getCacheDir(),"flat_bright.jpg");Bitmap a=Bitmap.createBitmap(220,220,Bitmap.Config.ARGB_8888),b=Bitmap.createBitmap(220,220,Bitmap.Config.ARGB_8888);a.eraseColor(Color.rgb(25,28,32));b.eraseColor(Color.rgb(230,225,210));StorageAnalyzer analyzer=new StorageAnalyzer(context);
        try{writeJpeg(a,dark,90);writeJpeg(b,bright,90);List<StorageItem> items=List.of(localImage(dark,11),localImage(bright,12));StorageAnalyzer.DuplicateResult none=new StorageAnalyzer.DuplicateResult(Set.of(),List.of(),Set.of(),0L);CountDownLatch latch=new CountDownLatch(1);AtomicReference<StorageAnalyzer.SimilarResult> ref=new AtomicReference<>();analyzer.analyzeSimilarAsync(items,none,new StorageAnalyzer.Callback<>(){public void onPhase(String p){}public void onDone(StorageAnalyzer.SimilarResult r){ref.set(r);latch.countDown();}});assertTrue(latch.await(20,TimeUnit.SECONDS));assertNotNull(ref.get());assertEquals(0,ref.get().groups);assertTrue(ref.get().keys.isEmpty());}finally{analyzer.shutdown();a.recycle();b.recycle();dark.delete();bright.delete();}}

    private static Bitmap patternBitmap(int w,int h,boolean alternate){Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);c.drawColor(alternate?Color.rgb(22,70,120):Color.rgb(28,34,42));p.setColor(alternate?Color.YELLOW:Color.rgb(225,70,55));c.drawRect(w*0.08f,h*0.12f,w*0.62f,h*0.48f,p);p.setColor(alternate?Color.MAGENTA:Color.rgb(70,190,145));c.drawCircle(w*0.72f,h*0.68f,Math.min(w,h)*0.22f,p);p.setColor(Color.WHITE);p.setStrokeWidth(9f);c.drawLine(12,h-20,w-18,18,p);return b;}
    private static void writeJpeg(Bitmap b,File f,int quality)throws Exception{try(FileOutputStream out=new FileOutputStream(f)){assertTrue(b.compress(Bitmap.CompressFormat.JPEG,quality,out));}}
    private static StorageItem localImage(File f,long id){return new StorageItem(id,null,f.getName(),f.getAbsolutePath(),"image/jpeg",f.length(),f.lastModified());}
'''
pos=s.rfind('\n}')
if pos<0: raise SystemExit('test class end missing')
s=s[:pos]+insert+s[pos:]
p.write_text(s)

print('v1.15.11 patch complete')
