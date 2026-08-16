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
    private final Context app;
    private final ExecutorService timeoutPool=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"a53-shizuku-call");t.setDaemon(true);return t;});
    private final Object bindLock=new Object();
    private volatile IPrivilegedService service;
    private volatile CountDownLatch bindLatch=new CountDownLatch(1);
    private final AtomicBoolean binding=new AtomicBoolean(false);

    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){service=IPrivilegedService.Stub.asInterface(binder);binding.set(false);bindLatch.countDown();}
        @Override public void onServiceDisconnected(ComponentName name){service=null;binding.set(false);}
    };

    public ShizukuShell(Context context){app=context.getApplicationContext();}

    private Shizuku.UserServiceArgs args(){
        return new Shizuku.UserServiceArgs(new ComponentName(app.getPackageName(),PrivilegedUserService.class.getName()))
                .daemon(false).processNameSuffix("privileged").debuggable(false).version(28);
    }

    public boolean available(){try{return Shizuku.pingBinder()&&!Shizuku.isPreV11();}catch(Throwable ignored){return false;}}
    public boolean permissionGranted(){try{return available()&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}catch(Throwable ignored){return false;}}
    public void requestPermission(int requestCode){try{if(available()&&Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED&&!Shizuku.shouldShowRequestPermissionRationale())Shizuku.requestPermission(requestCode);}catch(Throwable ignored){}}

    public boolean warmUp(long timeoutMs){
        if(!permissionGranted())return false;
        IPrivilegedService local=service;
        if(local!=null&&local.asBinder().pingBinder())return true;
        bindIfNeeded();
        try{return bindLatch.await(Math.max(300L,timeoutMs),TimeUnit.MILLISECONDS)&&service!=null&&service.asBinder().pingBinder();}
        catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
    }

    private void bindIfNeeded(){
        IPrivilegedService local=service;if(local!=null&&local.asBinder().pingBinder())return;
        if(!binding.compareAndSet(false,true))return;
        synchronized(bindLock){bindLatch=new CountDownLatch(1);try{Shizuku.bindUserService(args(),connection);}catch(Throwable t){binding.set(false);bindLatch.countDown();}}
    }

    public Result setPeakRefreshRate(float value){return callInt(()->service.setPeakRefreshRate(value),1800L);}
    public Result setMinRefreshRate(float value){return callInt(()->service.setMinRefreshRate(value),1800L);}
    public Result setLowPower(boolean enabled){return callInt(()->service.setLowPower(enabled),1800L);}
    public Result setRestrictBackground(boolean enabled){return callInt(()->service.setRestrictBackground(enabled),1800L);}
    public Result forceStopPackage(String pkg){return callInt(()->service.forceStopPackage(pkg),1400L);}

    public Result listProcessNames(){
        if(!permissionGranted())return new Result(false,"Shizuku sin permiso",-1);
        if(!warmUp(1800L))return new Result(false,"UserService no disponible",-5);
        Future<Result> f=timeoutPool.submit(()->{
            try{String out=service.listProcessNames();return new Result(out!=null&&!out.isBlank(),out==null?"":out,0);}catch(Throwable t){service=null;return new Result(false,t.getClass().getSimpleName(),-3);}
        });
        try{return f.get(2600L,TimeUnit.MILLISECONDS);}catch(Throwable e){f.cancel(true);return new Result(false,"timeout",-2);}
    }

    private Result callInt(Callable<Integer> call,long timeoutMs){
        if(!permissionGranted())return new Result(false,"Shizuku sin permiso",-1);
        if(!warmUp(Math.min(1800L,timeoutMs+400L)))return new Result(false,"UserService no disponible",-5);
        Future<Result> f=timeoutPool.submit(()->{
            try{int code=call.call();return new Result(code==0,"",code);}catch(Throwable t){service=null;return new Result(false,t.getClass().getSimpleName(),-3);}
        });
        try{return f.get(Math.max(900L,timeoutMs+500L),TimeUnit.MILLISECONDS);}catch(Throwable e){f.cancel(true);return new Result(false,"timeout",-2);}
    }

    public void shutdown(){timeoutPool.shutdownNow();try{if(available())Shizuku.unbindUserService(args(),connection,true);}catch(Throwable ignored){}service=null;}
    public record Result(boolean ok,String output,int code){}
}
