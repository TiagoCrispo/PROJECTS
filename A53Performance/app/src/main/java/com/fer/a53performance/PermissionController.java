package com.fer.a53performance;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import java.util.ArrayList;

public final class PermissionController {
    private final Activity activity;private final ShizukuShell shell;
    public PermissionController(Activity activity,ShizukuShell shell){this.activity=activity;this.shell=shell;}
    public String status(){
        ArrayList<String> missing=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=33){if(activity.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)!=PackageManager.PERMISSION_GRANTED)missing.add("imágenes");if(activity.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)!=PackageManager.PERMISSION_GRANTED)missing.add("videos");if(activity.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)!=PackageManager.PERMISSION_GRANTED)missing.add("audio");}
        else if(activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED)missing.add("archivos");
        if(Build.VERSION.SDK_INT>=30&&!Environment.isExternalStorageManager())missing.add("acceso completo a archivos");
        if(!shell.available())missing.add("Shizuku activo");else if(!shell.permissionGranted())missing.add("permiso Shizuku");
        return missing.isEmpty()?"Todo listo. · "+shell.health():"Falta: "+String.join(" · ",missing)+". · "+shell.health();
    }
}
