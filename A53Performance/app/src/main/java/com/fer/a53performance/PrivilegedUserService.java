package com.fer.a53performance;

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
