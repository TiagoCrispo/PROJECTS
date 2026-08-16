package com.fer.a53performance;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import rikka.shizuku.Shizuku;

public final class ShizukuShell {
    private static final long BREAKER_MS=8000L;
    private final Context app;
    private final ExecutorService timeoutPool=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"a53-shizuku-call");t.setDaemon(true);return t;});
    private final Object bindLock=new Object();
    private volatile IPrivilegedService service;
    private volatile CountDownLatch bindLatch=new CountDownLatch(1);
    private final AtomicBoolean binding=new AtomicBoolean(false);
    private volatile long breakerUntil=0L;

    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){service=IPrivilegedService.Stub.asInterface(binder);binding.set(false);breakerUntil=0L;bindLatch.countDown();}
        @Override public void onServiceDisconnected(ComponentName name){service=null;binding.set(false);breakerUntil=System.currentTimeMillis()+BREAKER_MS;}
    };

    public ShizukuShell(Context context){app=context.getApplicationContext();}
    private Shizuku.UserServiceArgs args(){return new Shizuku.UserServiceArgs(new ComponentName(app.getPackageName(),PrivilegedUserService.class.getName())).daemon(false).processNameSuffix("privileged").debuggable(false).version(30);}
    public boolean available(){try{return Shizuku.pingBinder()&&!Shizuku.isPreV11();}catch(Throwable ignored){return false;}}
    public boolean permissionGranted(){try{return available()&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}catch(Throwable ignored){return false;}}
    public void requestPermission(int requestCode){try{if(available()&&Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED&&!Shizuku.shouldShowRequestPermissionRationale())Shizuku.requestPermission(requestCode);}catch(Throwable ignored){}}

    public boolean warmUp(long timeoutMs){
        if(!permissionGranted()||System.currentTimeMillis()<breakerUntil)return false;
        IPrivilegedService local=service;if(local!=null&&local.asBinder().pingBinder())return true;
        bindIfNeeded();
        try{return bindLatch.await(Math.max(300L,timeoutMs),TimeUnit.MILLISECONDS)&&service!=null&&service.asBinder().pingBinder();}
        catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
    }

    private void bindIfNeeded(){
        IPrivilegedService local=service;if(local!=null&&local.asBinder().pingBinder())return;
        if(System.currentTimeMillis()<breakerUntil||!binding.compareAndSet(false,true))return;
        synchronized(bindLock){bindLatch=new CountDownLatch(1);try{Shizuku.bindUserService(args(),connection);}catch(Throwable t){binding.set(false);breakerUntil=System.currentTimeMillis()+BREAKER_MS;bindLatch.countDown();}}
    }

    private void invalidateBinder(){service=null;binding.set(false);breakerUntil=0L;}
    private boolean reconnectOnce(long timeoutMs){invalidateBinder();return warmUp(timeoutMs);}
    private static boolean transportFailure(Result r){return r.code()==-2||r.code()==-3||r.code()==-5;}

    public Result setPeakRefreshRate(float value){return callInt(()->service.setPeakRefreshRate(value),1800L,true);}
    public Result setMinRefreshRate(float value){return callInt(()->service.setMinRefreshRate(value),1800L,true);}
    public Result setLowPower(boolean enabled){return callInt(()->service.setLowPower(enabled),1800L,true);}
    public Result setRestrictBackground(boolean enabled){return callInt(()->service.setRestrictBackground(enabled),1800L,true);}
    public Result forceStopPackage(String pkg){return callInt(()->service.forceStopPackage(pkg),1400L,true);}
    public Result listRunningUserPackages(){return callString(()->service.listRunningUserPackages(),2400L,true);}
    public float getPeakRefreshRate(){return callValue(()->service.getPeakRefreshRate(),-1f);}
    public float getMinRefreshRate(){return callValue(()->service.getMinRefreshRate(),-1f);}
    public int getLowPower(){return callValue(()->service.getLowPower(),-1);}
    public int getRestrictBackground(){return callValue(()->service.getRestrictBackground(),-1);}

    private Result callString(Callable<String> call,long timeoutMs,boolean retry){
        if(!permissionGranted())return new Result(false,"Shizuku sin permiso",-1);
        if(!warmUp(1800L))return new Result(false,"UserService no disponible",-5);
        Future<Result> f=timeoutPool.submit(()->{try{String out=call.call();return new Result(out!=null,out==null?"":out,0);}catch(Throwable t){return new Result(false,t.getClass().getSimpleName(),-3);}});
        try{
            Result r=f.get(timeoutMs,TimeUnit.MILLISECONDS);
            if(transportFailure(r)&&retry&&reconnectOnce(1600L))return callString(call,timeoutMs,false);
            if(transportFailure(r))breakerUntil=System.currentTimeMillis()+BREAKER_MS;
            return r;
        }catch(Throwable e){
            f.cancel(true);if(retry&&reconnectOnce(1600L))return callString(call,timeoutMs,false);breakerUntil=System.currentTimeMillis()+BREAKER_MS;return new Result(false,"timeout",-2);
        }
    }

    private Result callInt(Callable<Integer> call,long timeoutMs,boolean retry){
        if(!permissionGranted())return new Result(false,"Shizuku sin permiso",-1);
        if(!warmUp(Math.min(1800L,timeoutMs+400L)))return new Result(false,"UserService no disponible",-5);
        Future<Result> f=timeoutPool.submit(()->{try{int code=call.call();return new Result(code==0,"",code);}catch(Throwable t){return new Result(false,t.getClass().getSimpleName(),-3);}});
        try{
            Result r=f.get(Math.max(900L,timeoutMs+500L),TimeUnit.MILLISECONDS);
            if(transportFailure(r)&&retry&&reconnectOnce(1600L))return callInt(call,timeoutMs,false);
            if(transportFailure(r))breakerUntil=System.currentTimeMillis()+BREAKER_MS;
            return r;
        }catch(Throwable e){
            f.cancel(true);if(retry&&reconnectOnce(1600L))return callInt(call,timeoutMs,false);breakerUntil=System.currentTimeMillis()+BREAKER_MS;return new Result(false,"timeout",-2);
        }
    }

    private <T>T callValue(Callable<T> call,T fallback){
        if(!permissionGranted()||!warmUp(1600L))return fallback;
        Future<T> f=timeoutPool.submit(call);
        try{return f.get(1800L,TimeUnit.MILLISECONDS);}catch(Throwable e){
            f.cancel(true);if(reconnectOnce(1400L)){Future<T> r=timeoutPool.submit(call);try{return r.get(1800L,TimeUnit.MILLISECONDS);}catch(Throwable ignored){r.cancel(true);}}
            breakerUntil=System.currentTimeMillis()+BREAKER_MS;return fallback;
        }
    }

    public String health(){if(!available())return"Shizuku no disponible";if(!permissionGranted())return"Shizuku sin permiso";if(System.currentTimeMillis()<breakerUntil)return"Shizuku en pausa de reconexión";return warmUp(700L)?"Shizuku conectado":"Shizuku pendiente";}
    public void shutdown(){timeoutPool.shutdownNow();try{if(available())Shizuku.unbindUserService(args(),connection,true);}catch(Throwable ignored){}service=null;}
    public record Result(boolean ok,String output,int code){}
}
