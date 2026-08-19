package com.fer.wavault;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Debug-only helper for physical-device acceptance. It never reads or exports message bodies.
 * Baselines live in SharedPreferences so clean close/reopen and device reboot can be measured.
 */
public final class AcceptanceActivity extends Activity {
    private static final String PREF = "wa_vault_real_device_acceptance";
    private static final String BASE = "baseline";
    private static final String MSG = "normal_message";
    private static final String DEL = "real_delete";
    private static final String MEDIA = "media";

    private SharedPreferences prefs;
    private VaultDb db;
    private TextView state;
    private final int bg=Color.rgb(7,10,13), card=Color.rgb(18,24,31), fg=Color.rgb(246,248,250), muted=Color.rgb(154,166,178);

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        prefs=getSharedPreferences(PREF,MODE_PRIVATE);
        db=new VaultDb(getApplicationContext());
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if(state!=null) refreshState();
    }

    private void render() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(bg);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(18),dp(18),dp(30));
        scroll.addView(root);

        TextView title=text("WA Vault Test",26,fg,true); root.addView(title);
        TextView sub=text("Aceptación física · v"+installedVersionName()+" ("+installedVersionCode()+")",13,muted,false); root.addView(sub);
        TextView privacy=text("No exporta contenido de mensajes. Solo conteos, deltas y códigos de eventos.",12,muted,false); privacy.setPadding(0,dp(6),0,dp(16)); root.addView(privacy);

        state=text("",13,fg,false); state.setBackgroundColor(card); state.setPadding(dp(14),dp(14),dp(14),dp(14)); root.addView(state,new LinearLayout.LayoutParams(-1,-2));

        addSection(root,"1 · Reinicio limpio","Sin enviar/recibir/borrar nada en WhatsApp: inicia la línea base, cierra WA Vault, vuelve a abrirla y luego reinicia el teléfono. Después toca Verificar.");
        root.addView(button("Iniciar línea base",v->{saveSnapshot(BASE); prefs.edit().putLong("started_at",System.currentTimeMillis()).apply(); toast("Línea base guardada"); refreshState();}));
        root.addView(button("Verificar reinicio limpio",v->verifyCleanRestart()));

        addSection(root,"2 · Mensaje normal","Guarda el checkpoint, recibe exactamente 1 mensaje normal de WhatsApp y NO lo borres. Vuelve aquí y verifica.");
        root.addView(button("Preparar mensaje normal",v->{saveSnapshot(MSG); toast("Checkpoint guardado"); refreshState();}));
        root.addView(button("Abrir WhatsApp",v->openWhatsApp()));
        root.addView(button("Verificar mensaje normal",v->verifyNormalMessage()));

        addSection(root,"3 · Borrado real","Guarda el checkpoint. Después borra exactamente 1 mensaje real en WhatsApp y vuelve aquí.");
        root.addView(button("Preparar borrado real",v->{saveSnapshot(DEL); toast("Checkpoint guardado"); refreshState();}));
        root.addView(button("Verificar 1 borrado real",v->verifyRealDelete()));

        addSection(root,"4 · Multimedia","Guarda el checkpoint y prueba, una por una, una foto, un video y un audio que deban quedar recuperados en WA Vault. Luego verifica los bytes por tipo.");
        root.addView(button("Preparar multimedia",v->{saveSnapshot(MEDIA); toast("Checkpoint multimedia guardado"); refreshState();}));
        root.addView(button("Verificar foto + video + audio",v->verifyMedia()));

        addSection(root,"5 · Evidencia","Comparte un reporte agregado para revisar los resultados. El detalle de los mensajes no se incluye.");
        root.addView(button("Compartir reporte",v->shareReport()));
        root.addView(button("Reiniciar prueba",v->{prefs.edit().clear().apply(); toast("Prueba reiniciada"); refreshState();}));

        setContentView(scroll);
        refreshState();
    }

    private void addSection(LinearLayout root,String title,String body){
        TextView t=text(title,17,fg,true); t.setPadding(0,dp(20),0,dp(4)); root.addView(t);
        TextView b=text(body,12,muted,false); b.setPadding(0,0,0,dp(8)); root.addView(b);
    }

    private Button button(String label,View.OnClickListener l){
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setOnClickListener(l);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(5),0,dp(5)); b.setLayoutParams(p); return b;
    }

    private TextView text(String s,int size,int color,boolean bold){
        TextView v=new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color); if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD); return v;
    }

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    private String installedVersionName(){
        try{
            PackageInfo pi=getPackageManager().getPackageInfo(getPackageName(),0);
            return pi.versionName==null?"?":pi.versionName;
        }catch(Throwable t){return "?";}
    }

    private long installedVersionCode(){
        try{
            PackageInfo pi=getPackageManager().getPackageInfo(getPackageName(),0);
            return Build.VERSION.SDK_INT>=28?pi.getLongVersionCode():pi.versionCode;
        }catch(Throwable t){return -1L;}
    }

    private void saveSnapshot(String prefix){
        VaultDb.Stats s=db.getStats();
        prefs.edit()
                .putLong(prefix+"_ts",System.currentTimeMillis())
                .putInt(prefix+"_messages",s.messages)
                .putInt(prefix+"_deleted",s.deletedMessages)
                .putInt(prefix+"_media",s.media)
                .putInt(prefix+"_pending",s.pendingMedia)
                .putLong(prefix+"_bytes",s.totalBytes)
                .putLong(prefix+"_image_bytes",s.imageBytes)
                .putLong(prefix+"_video_bytes",s.videoBytes)
                .putLong(prefix+"_audio_bytes",s.audioBytes)
                .apply();
    }

    private Snapshot load(String prefix){
        if(!prefs.contains(prefix+"_ts"))return null;
        Snapshot s=new Snapshot();
        s.ts=prefs.getLong(prefix+"_ts",0); s.messages=prefs.getInt(prefix+"_messages",0); s.deleted=prefs.getInt(prefix+"_deleted",0);
        s.media=prefs.getInt(prefix+"_media",0); s.pending=prefs.getInt(prefix+"_pending",0); s.bytes=prefs.getLong(prefix+"_bytes",0);
        s.imageBytes=prefs.getLong(prefix+"_image_bytes",0); s.videoBytes=prefs.getLong(prefix+"_video_bytes",0); s.audioBytes=prefs.getLong(prefix+"_audio_bytes",0);
        return s;
    }

    private void verifyCleanRestart(){
        Snapshot b=load(BASE); if(b==null){toast("Primero inicia la línea base");return;}
        VaultDb.Stats now=db.getStats();
        int deletedDelta=now.deletedMessages-b.deleted; int mediaDelta=now.media-b.media; long bytesDelta=now.totalBytes-b.bytes;
        boolean pass=deletedDelta==0&&mediaDelta==0&&bytesDelta==0;
        recordResult("restart",pass,"deleted_delta="+deletedDelta+", media_delta="+mediaDelta+", bytes_delta="+bytesDelta);
        toast(pass?"PASS · reinicio limpio":"FAIL · hubo cambios inesperados"); refreshState();
    }

    private void verifyNormalMessage(){
        Snapshot b=load(MSG); if(b==null){toast("Primero prepara mensaje normal");return;}
        VaultDb.Stats now=db.getStats();
        int msgDelta=now.messages-b.messages; int deletedDelta=now.deletedMessages-b.deleted;
        boolean pass=msgDelta>=1&&deletedDelta==0;
        recordResult("normal_message",pass,"messages_delta="+msgDelta+", deleted_delta="+deletedDelta);
        toast(pass?"PASS · mensaje normal no marcado como borrado":"FAIL · revisa deltas"); refreshState();
    }

    private void verifyRealDelete(){
        Snapshot b=load(DEL); if(b==null){toast("Primero prepara borrado real");return;}
        VaultDb.Stats now=db.getStats(); int delta=now.deletedMessages-b.deleted;
        boolean pass=delta==1;
        recordResult("real_delete",pass,"deleted_delta="+delta);
        toast(pass?"PASS · 1 borrado detectado":"FAIL · se esperaban exactamente 1"); refreshState();
    }

    private void verifyMedia(){
        Snapshot b=load(MEDIA); if(b==null){toast("Primero prepara multimedia");return;}
        VaultDb.Stats now=db.getStats();
        long image=now.imageBytes-b.imageBytes, video=now.videoBytes-b.videoBytes, audio=now.audioBytes-b.audioBytes;
        boolean pass=image>0&&video>0&&audio>0;
        recordResult("media",pass,"image_bytes_delta="+image+", video_bytes_delta="+video+", audio_bytes_delta="+audio);
        toast(pass?"PASS · foto/video/audio recuperados":"FAIL · falta al menos un tipo"); refreshState();
    }

    private void recordResult(String key,boolean pass,String detail){
        prefs.edit().putBoolean("result_"+key,true).putBoolean("pass_"+key,pass).putString("detail_"+key,detail).putLong("checked_"+key,System.currentTimeMillis()).apply();
    }

    private void refreshState(){
        VaultDb.Stats s=db.getStats();
        StringBuilder x=new StringBuilder();
        x.append("AHORA\nMensajes: ").append(s.messages).append(" · borrados confirmados: ").append(s.deletedMessages)
                .append("\nRecuperados: ").append(s.media).append(" · pendientes: ").append(s.pendingMedia)
                .append("\nImagen: ").append(human(s.imageBytes)).append(" · video: ").append(human(s.videoBytes)).append(" · audio: ").append(human(s.audioBytes)).append("\n\n");
        appendResult(x,"Reinicio limpio","restart"); appendResult(x,"Mensaje normal","normal_message"); appendResult(x,"Borrado real","real_delete"); appendResult(x,"Multimedia","media");
        state.setText(x.toString());
    }

    private void appendResult(StringBuilder x,String label,String key){
        if(!prefs.getBoolean("result_"+key,false)){x.append("○ ").append(label).append(": pendiente\n");return;}
        boolean pass=prefs.getBoolean("pass_"+key,false); x.append(pass?"✓ ":"✕ ").append(label).append(": ").append(pass?"PASS":"FAIL").append("\n  ").append(prefs.getString("detail_"+key,"")).append("\n");
    }

    private void openWhatsApp(){
        Intent i=getPackageManager().getLaunchIntentForPackage("com.whatsapp");
        if(i==null)i=getPackageManager().getLaunchIntentForPackage("com.whatsapp.w4b");
        if(i==null){toast("WhatsApp no encontrado");return;} startActivity(i);
    }

    private void shareReport(){
        String report=buildReport();
        Intent i=new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_SUBJECT,"WA Vault real-device acceptance"); i.putExtra(Intent.EXTRA_TEXT,report);
        startActivity(Intent.createChooser(i,"Compartir reporte WA Vault"));
    }

    private String buildReport(){
        VaultDb.Stats s=db.getStats(); long start=prefs.getLong("started_at",0);
        StringBuilder x=new StringBuilder();
        x.append("WA_VAULT_REAL_DEVICE_ACCEPTANCE\n");
        x.append("version=").append(installedVersionName()).append(" code=").append(installedVersionCode()).append('\n');
        x.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        x.append("generated_at=").append(System.currentTimeMillis()).append('\n');
        x.append("started_at=").append(start).append('\n');
        x.append("messages=").append(s.messages).append(" deleted=").append(s.deletedMessages).append(" media=").append(s.media).append(" pending=").append(s.pendingMedia).append('\n');
        x.append("image_bytes=").append(s.imageBytes).append(" video_bytes=").append(s.videoBytes).append(" audio_bytes=").append(s.audioBytes).append('\n');
        appendReportResult(x,"restart"); appendReportResult(x,"normal_message"); appendReportResult(x,"real_delete"); appendReportResult(x,"media");
        x.append("\nEVENT_CODES_SINCE_START\n");
        if(start>0){
            Map<String,Integer> counts=new LinkedHashMap<>(); int failures=0;
            List<VaultDb.Event> events=db.listEventsSince(start,1000);
            for(VaultDb.Event e:events){String code=e.code==null?"UNKNOWN":e.code; counts.put(code,counts.containsKey(code)?counts.get(code)+1:1); if(code.startsWith("ERROR_")||code.startsWith("CAPTURE_FAIL_"))failures++;}
            for(Map.Entry<String,Integer> e:counts.entrySet())x.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            x.append("error_or_capture_fail_events=").append(failures).append('\n');
        } else x.append("not_started\n");
        x.append("privacy=message_bodies_not_exported\n");
        return x.toString();
    }

    private void appendReportResult(StringBuilder x,String key){
        if(!prefs.getBoolean("result_"+key,false)){x.append("result_").append(key).append("=PENDING\n");return;}
        x.append("result_").append(key).append('=').append(prefs.getBoolean("pass_"+key,false)?"PASS":"FAIL").append(' ')
                .append(prefs.getString("detail_"+key,"")).append('\n');
    }

    private String human(long b){
        if(b<1024)return b+" B"; double kb=b/1024.0; if(kb<1024)return String.format(Locale.US,"%.1f KB",kb); return String.format(Locale.US,"%.1f MB",kb/1024.0);
    }

    static final class Snapshot {long ts,bytes,imageBytes,videoBytes,audioBytes; int messages,deleted,media,pending;}
}
