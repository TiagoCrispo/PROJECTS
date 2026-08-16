package com.fer.a53performance;

import android.content.Context;
import android.os.RemoteException;
import androidx.annotation.Keep;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class PrivilegedUserService extends IPrivilegedService.Stub {
    private static final long TIMEOUT_MS=1800L;

    public PrivilegedUserService() {}
    @Keep public PrivilegedUserService(Context context) {}
    @Override public void destroy(){System.exit(0);}

    @Override public int setPeakRefreshRate(float value)throws RemoteException{return validRefresh(value)?runFixed("settings","put","system","peak_refresh_rate",Float.toString(value)):-4;}
    @Override public int setMinRefreshRate(float value)throws RemoteException{return validRefresh(value)?runFixed("settings","put","system","min_refresh_rate",Float.toString(value)):-4;}
    @Override public int setLowPower(boolean enabled)throws RemoteException{return runFixed("settings","put","global","low_power",enabled?"1":"0");}
    @Override public int setRestrictBackground(boolean enabled)throws RemoteException{return runFixed("cmd","netpolicy","set","restrict-background",enabled?"true":"false");}
    @Override public int forceStopPackage(String packageName)throws RemoteException{return validPackage(packageName)?runFixed("am","force-stop",packageName):-4;}

    @Override public String listRunningUserPackages()throws RemoteException{
        Set<String> user=new HashSet<>();
        String packages=readFixed("pm","list","packages","-3");
        for(String line:packages.split("\\R"))if(line.startsWith("package:")){String p=line.substring(8).trim();if(validPackage(p))user.add(p);}
        if(user.isEmpty())return "";
        String ps=readFixed("ps","-A","-o","NAME");StringBuilder out=new StringBuilder();Set<String> seen=new HashSet<>();
        for(String line:ps.split("\\R")){
            String p=line.trim();int colon=p.indexOf(':');if(colon>0)p=p.substring(0,colon);
            if(user.contains(p)&&seen.add(p))out.append(p).append('\n');
        }
        return out.toString();
    }

    @Override public float getPeakRefreshRate()throws RemoteException{return parseFloat(readFixed("settings","get","system","peak_refresh_rate"));}
    @Override public float getMinRefreshRate()throws RemoteException{return parseFloat(readFixed("settings","get","system","min_refresh_rate"));}
    @Override public int getLowPower()throws RemoteException{return parseBoolInt(readFixed("settings","get","global","low_power"));}
    @Override public int getRestrictBackground()throws RemoteException{
        String s=readFixed("cmd","netpolicy","get","restrict-background").toLowerCase();
        if(s.contains("enabled")||s.trim().equals("true")||s.trim().equals("1"))return 1;
        if(s.contains("disabled")||s.trim().equals("false")||s.trim().equals("0"))return 0;
        return -1;
    }

    private static float parseFloat(String s){try{return Float.parseFloat(s.trim());}catch(Throwable ignored){return -1f;}}
    private static int parseBoolInt(String s){String v=s.trim();if("1".equals(v)||"true".equalsIgnoreCase(v))return 1;if("0".equals(v)||"false".equalsIgnoreCase(v))return 0;return -1;}
    private static boolean validRefresh(float value){return value>=30f&&value<=144f&&Float.isFinite(value);}
    private static boolean validPackage(String pkg){return pkg!=null&&pkg.length()<=180&&pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");}

    private static String readFixed(String...args){
        Process p=null;
        try{
            p=new ProcessBuilder(args).redirectErrorStream(true).start();final Process proc=p;StringBuilder out=new StringBuilder();
            Thread reader=new Thread(()->{try(BufferedReader br=new BufferedReader(new InputStreamReader(proc.getInputStream()))){String line;while((line=br.readLine())!=null&&out.length()<65536)out.append(line).append('\n');}catch(Throwable ignored){}},"a53-read");
            reader.setDaemon(true);reader.start();boolean done=p.waitFor(TIMEOUT_MS,TimeUnit.MILLISECONDS);if(!done){p.destroyForcibly();return "";}try{reader.join(250L);}catch(InterruptedException e){Thread.currentThread().interrupt();}return out.toString().trim();
        }catch(Throwable ignored){return "";}finally{if(p!=null)try{p.destroy();}catch(Throwable ignored){}}
    }
    private static int runFixed(String...args){
        Process p=null;try{p=new ProcessBuilder(args).redirectErrorStream(true).start();if(!p.waitFor(TIMEOUT_MS,TimeUnit.MILLISECONDS)){try{p.destroy();}catch(Throwable ignored){}try{if(!p.waitFor(120L,TimeUnit.MILLISECONDS))p.destroyForcibly();}catch(Throwable ignored){}return -2;}return p.exitValue();}catch(Throwable ignored){return -3;}finally{if(p!=null)try{p.destroy();}catch(Throwable ignored){}}
    }
}
