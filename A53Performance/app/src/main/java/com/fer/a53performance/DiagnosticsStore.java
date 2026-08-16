package com.fer.a53performance;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.DateFormat;
import java.util.Date;

public final class DiagnosticsStore {
    private final SharedPreferences prefs;
    public DiagnosticsStore(Context context){prefs=context.getApplicationContext().getSharedPreferences("a53_ui",Context.MODE_PRIVATE);}
    public void record(String key,String value){prefs.edit().putString("diag_"+key,value).putLong("diag_"+key+"_at",System.currentTimeMillis()).apply();}
    public String read(String key,String fallback){String v=prefs.getString("diag_"+key,fallback);long at=prefs.getLong("diag_"+key+"_at",0L);return at>0?v+" · "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(at)):v;}
    public String summary(){
        String auto=prefs.getString("last_auto_status","Sin ejecución Auto todavía");
        long autoAt=prefs.getLong("last_auto_run",0L);
        String autoText=autoAt>0?auto+" · "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(autoAt)):auto;
        return "Auto: "+autoText+"\nAnálisis: "+read("analysis","Sin análisis registrado")+"\nOperación: "+read("operation","Sin fallos recientes");
    }
}
