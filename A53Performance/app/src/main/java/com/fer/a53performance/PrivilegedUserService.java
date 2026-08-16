package com.fer.a53performance;

import android.content.Context;
import android.os.RemoteException;
import androidx.annotation.Keep;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public final class PrivilegedUserService extends IPrivilegedService.Stub {
    private static final long TIMEOUT_MS=1800L;

    public PrivilegedUserService() {}
    @Keep public PrivilegedUserService(Context context) {}

    @Override public void destroy(){System.exit(0);}

    @Override public int setPeakRefreshRate(float value)throws RemoteException{
        return validRefresh(value)?runFixed("settings","put","system","peak_refresh_rate",Float.toString(value)):-4;
    }
    @Override public int setMinRefreshRate(float value)throws RemoteException{
        return validRefresh(value)?runFixed("settings","put","system","min_refresh_rate",Float.toString(value)):-4;
    }
    @Override public int setLowPower(boolean enabled)throws RemoteException{
        return runFixed("settings","put","global","low_power",enabled?"1":"0");
    }
    @Override public int setRestrictBackground(boolean enabled)throws RemoteException{
        return runFixed("cmd","netpolicy","set","restrict-background",enabled?"true":"false");
    }
    @Override public int forceStopPackage(String packageName)throws RemoteException{
        if(!validPackage(packageName))return -4;
        return runFixed("am","force-stop",packageName);
    }
    @Override public String listProcessNames()throws RemoteException{
        Process p=null;
        try{
            p=new ProcessBuilder("ps","-A","-o","NAME").redirectErrorStream(true).start();
            final Process proc=p;
            final StringBuilder out=new StringBuilder();
            Thread reader=new Thread(()->{
                try(BufferedReader br=new BufferedReader(new InputStreamReader(proc.getInputStream()))){
                    String line;
                    while((line=br.readLine())!=null&&out.length()<32768)out.append(line).append('\n');
                }catch(Throwable ignored){}
            },"a53-ps-reader");
            reader.setDaemon(true);reader.start();
            boolean done=p.waitFor(TIMEOUT_MS,TimeUnit.MILLISECONDS);
            if(!done){try{p.destroyForcibly();}catch(Throwable ignored){}return "";}
            try{reader.join(250L);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            return out.toString();
        }catch(Throwable ignored){return "";}
        finally{if(p!=null)try{p.destroy();}catch(Throwable ignored){}}
    }

    private static boolean validRefresh(float value){return value>=30f&&value<=144f&&Float.isFinite(value);}
    private static boolean validPackage(String pkg){return pkg!=null&&pkg.length()<=180&&pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");}
    private static int runFixed(String...args){
        Process p=null;
        try{
            p=new ProcessBuilder(args).redirectErrorStream(true).start();
            if(!p.waitFor(TIMEOUT_MS,TimeUnit.MILLISECONDS)){
                try{p.destroy();}catch(Throwable ignored){}
                try{if(!p.waitFor(120L,TimeUnit.MILLISECONDS))p.destroyForcibly();}catch(Throwable ignored){}
                return -2;
            }
            return p.exitValue();
        }catch(Throwable ignored){return -3;}
        finally{if(p!=null)try{p.destroy();}catch(Throwable ignored){}}
    }
}
