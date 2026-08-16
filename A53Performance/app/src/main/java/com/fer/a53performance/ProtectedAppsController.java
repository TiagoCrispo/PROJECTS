package com.fer.a53performance;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ProtectedAppsController {
    public record AppEntry(String label,String packageName,boolean protectedByUser){}
    private ProtectedAppsController(){}

    public static List<AppEntry> launcherApps(Context context){
        PackageManager pm=context.getPackageManager();
        Intent intent=new Intent(Intent.ACTION_MAIN);intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos;
        try{infos=pm.queryIntentActivities(intent,0);}catch(Throwable t){infos=List.of();}
        Set<String> user=AppProtection.userProtected(context);HashSet<String> seen=new HashSet<>();ArrayList<AppEntry> out=new ArrayList<>();
        for(ResolveInfo info:infos){
            if(info.activityInfo==null||info.activityInfo.packageName==null)continue;String pkg=info.activityInfo.packageName;
            if(pkg.equals(context.getPackageName())||!seen.add(pkg))continue;
            CharSequence labelCs=info.loadLabel(pm);String label=labelCs==null||labelCs.toString().isBlank()?pkg:labelCs.toString();
            out.add(new AppEntry(label,pkg,user.contains(pkg)));
        }
        out.sort(Comparator.comparing(AppEntry::label,String.CASE_INSENSITIVE_ORDER));return out;
    }
}
