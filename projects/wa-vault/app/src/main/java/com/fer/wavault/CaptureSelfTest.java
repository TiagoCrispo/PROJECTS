package com.fer.wavault;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;

/** Simple guided end-to-end self test based on real capture events produced after START. */
public final class CaptureSelfTest {
    private CaptureSelfTest() {}
    private static final String PREF="wa_vault_selftest";
    public static void start(Context c){if(c!=null)c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putLong("start",System.currentTimeMillis()).apply();}
    public static long startedAt(Context c){return c==null?0L:c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getLong("start",0L);}
    public static String summary(Context c){
        if(c==null)return "No iniciada";long start=startedAt(c);if(start<=0)return "No iniciada";
        boolean msg=false,img=false,vid=false,aud=false,doc=false;
        try{List<VaultDb.Event> ev=new VaultDb(c).listEventsSince(start,300);for(VaultDb.Event e:ev){String x=e.code==null?"":e.code;
            if(x.contains("MESSAGE_CAPTURED"))msg=true;
            if((x.contains("MEDIA")||x.contains("PREOPEN")||x.contains("FILE_OBSERVER")||x.contains("FAST_CAPTURE"))&&e.detail!=null&&e.detail.toLowerCase().contains("image"))img=true;
            if((x.contains("MEDIA")||x.contains("PREOPEN")||x.contains("FILE_OBSERVER")||x.contains("FAST_CAPTURE"))&&e.detail!=null&&e.detail.toLowerCase().contains("video"))vid=true;
            if(x.contains("AUDIO"))aud=true;
            if((x.contains("MEDIA")||x.contains("FILE_OBSERVER")||x.contains("FAST_CAPTURE")||x.contains("STAGING"))&&e.detail!=null&&e.detail.toLowerCase().contains("document"))doc=true;}}
        catch(Throwable t){try{new VaultDb(c).logInternalError("SELFTEST",t);}catch(Throwable ignored){}}
        return "Texto "+ok(msg)+"   Foto "+ok(img)+"   Video "+ok(vid)+"   Audio "+ok(aud)+"   Documento "+ok(doc);
    }
    private static String ok(boolean v){return v?"✓":"…";}
}
