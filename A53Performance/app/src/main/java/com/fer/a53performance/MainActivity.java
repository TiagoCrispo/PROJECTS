package com.fer.a53performance;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int BG=Color.rgb(7,10,13), CARD=Color.rgb(14,19,24), MUTED=Color.rgb(166,177,185), ACCENT=Color.rgb(92,224,164), RED=Color.rgb(255,110,110);
    private static final int PAGE_SIZE=60, SHIZUKU_REQUEST=5401, PERMISSION_REQUEST=5402, TRASH_REQUEST=5403;
    private final Handler main=new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout root,content,nav;
    private String currentPage="home";
    private StorageRepository storage;
    private StorageAnalyzer analyzer;
    private StorageAnalyzer.Result analysis=StorageAnalyzer.Result.empty();
    private ThumbnailLoader thumbnails;
    private FileAdapter fileAdapter;
    private ShizukuShell shell;
    private SystemOptimizer optimizer;
    private RecyclerView fileList;
    private TextView storageSummary,selectionSummary,scanStatus;
    private EditText search;
    private Spinner filterSpinner,sortSpinner;
    private Button loadMore,trashSelected,deleteSelected,scanButton;
    private ProgressBar scanProgress;
    private final ArrayList<StorageItem> filtered=new ArrayList<>();
    private final ArrayList<StorageItem> pendingTrash=new ArrayList<>();
    private int visibleLimit=PAGE_SIZE;
    private Runnable searchRunnable;
    private int savedAnchor=-1,savedAnchorOffset=0,savedVisible=PAGE_SIZE;
    private boolean scanRunning=false;
    private boolean waitingForAllFiles=false;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener=(requestCode,grantResult)->{
        if(requestCode==SHIZUKU_REQUEST){toast(grantResult==PackageManager.PERMISSION_GRANTED?"Shizuku autorizado":"Shizuku no autorizado");if(currentPage.equals("home"))showHome();}
    };

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        prefs=getSharedPreferences("a53_ui",MODE_PRIVATE);
        shell=new ShizukuShell();storage=new StorageRepository(this);analyzer=new StorageAnalyzer(this);thumbnails=new ThumbnailLoader(this);optimizer=new SystemOptimizer(this,shell);
        try{Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);}catch(Throwable ignored){}
        buildShell();
        if(state!=null){currentPage=state.getString("page","home");savedAnchor=state.getInt("anchor",-1);savedAnchorOffset=state.getInt("anchorOffset",0);savedVisible=state.getInt("visible",PAGE_SIZE);visibleLimit=savedVisible;}
        else currentPage=prefs.getString("page","home");
        navigate(currentPage);
        main.postDelayed(this::startPermissionFlow,500);
    }

    private void buildShell(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setFitsSystemWindows(true);
        TextView title=text("A53 Performance",22,Color.WHITE,true);title.setPadding(dp(18),dp(14),dp(18),dp(10));root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);root.addView(content,new LinearLayout.LayoutParams(-1,0,1f));
        nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);nav.setPadding(dp(6),dp(6),dp(6),dp(8));
        addNav("Inicio","home");addNav("Rendimiento","performance");addNav("Limpiar","cleaner");addNav("Ajustes","settings");root.addView(nav,new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);
    }

    private void addNav(String label,String page){Button b=button(label);b.setTextSize(11);b.setOnClickListener(v->navigate(page));nav.addView(b,new LinearLayout.LayoutParams(0,dp(52),1f));}
    private void navigate(String page){
        if(fileList!=null && !"cleaner".equals(page)){saveListAnchor();thumbnails.cancelVisualWork();analyzer.cancel();}
        currentPage=page;prefs.edit().putString("page",page).apply();
        switch(page){case "performance"->showPerformance();case "cleaner"->showCleaner();case "settings"->showSettings();default->showHome();}
    }
    private void clearContent(){content.removeAllViews();fileList=null;search=null;filterSpinner=null;sortSpinner=null;loadMore=null;trashSelected=null;deleteSelected=null;scanButton=null;scanProgress=null;selectionSummary=null;storageSummary=null;scanStatus=null;}

    private void showHome(){
        clearContent();ScrollView sv=new ScrollView(this);LinearLayout box=vertical();box.setPadding(dp(14),0,dp(14),dp(18));sv.addView(box);content.addView(sv,new LinearLayout.LayoutParams(-1,-1));
        box.addView(sectionTitle("Estado del Galaxy A53"));
        LinearLayout shCard=card();TextView sh=text("Shizuku: "+(shell.permissionGranted()?"Activo":"Sin autorización"),16,shell.permissionGranted()?ACCENT:Color.WHITE,true);shCard.addView(sh);shCard.addView(text(shell.permissionGranted()?"Los ajustes avanzados pueden aplicarse sin bloquear la interfaz.":"Autorízalo para perfiles y cierre real de procesos.",13,MUTED,false));if(!shell.permissionGranted()){Button grant=button("Autorizar Shizuku");grant.setOnClickListener(v->shell.requestPermission(SHIZUKU_REQUEST));shCard.addView(grant);}box.addView(shCard);
        box.addView(metricCard("Almacenamiento libre",FileAdapter.formatBytes(StorageRepository.freeBytes()),"Lectura en vivo del espacio físico disponible."));
        box.addView(metricCard("RAM disponible",FileAdapter.formatBytes(availableRam()),"Medición real de Android; no se muestran porcentajes inventados."));
        box.addView(metricCard("Estado térmico",thermalLabel(),"No se presentan valores ficticios de CPU/GPU."));
        LinearLayout ramCard=card();ramCard.addView(sectionTitle("Limpiar RAM"));ramCard.addView(text("Cierra procesos de apps de usuario en segundo plano. No borra datos ni toca apps del sistema/protegidas. Android puede reabrir procesos cuando los necesite.",13,MUTED,false));Button clean=button("Limpiar RAM ahora");TextView result=text("",13,MUTED,false);clean.setOnClickListener(v->{clean.setEnabled(false);clean.setText("Limpiando…");result.setText("Midiendo y cerrando procesos no protegidos…");optimizer.cleanRam((ok,msg)->runOnUiThread(()->{if(isFinishing()||isDestroyed())return;clean.setEnabled(true);clean.setText("Limpiar RAM ahora");result.setText(msg);}));});ramCard.addView(clean);ramCard.addView(result);box.addView(ramCard);
    }

    private void showPerformance(){
        clearContent();ScrollView sv=new ScrollView(this);LinearLayout box=vertical();box.setPadding(dp(14),0,dp(14),dp(18));sv.addView(box);content.addView(sv,new LinearLayout.LayoutParams(-1,-1));
        box.addView(sectionTitle("Perfiles fluidos"));box.addView(text("Cada perfil se aplica en una cola de fondo. Si eliges otro mientras uno se aplica, el anterior deja de continuar y la pantalla no vuelve a Inicio.",13,MUTED,false));
        for(SystemOptimizer.Profile p:SystemOptimizer.Profile.values()){
            LinearLayout c=card();c.addView(text(SystemOptimizer.label(p),17,Color.WHITE,true));c.addView(text(SystemOptimizer.description(p),13,MUTED,false));TextView result=text("",12,MUTED,false);Button apply=button("Aplicar "+SystemOptimizer.label(p));apply.setOnClickListener(v->{apply.setEnabled(false);apply.setText("Aplicando…");optimizer.applyProfile(p,(ok,msg)->runOnUiThread(()->{if(currentPage.equals("performance")&&!isFinishing()){apply.setEnabled(true);apply.setText("Aplicar "+SystemOptimizer.label(p));result.setText(msg);result.setTextColor(ok?ACCENT:RED);}}));});c.addView(apply);c.addView(result);box.addView(c);
        }
    }

    private void showCleaner(){
        clearContent();LinearLayout box=vertical();box.setPadding(dp(12),0,dp(12),dp(8));content.addView(box,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout top=card();storageSummary=text("Libre: "+FileAdapter.formatBytes(StorageRepository.freeBytes()),14,Color.WHITE,true);top.addView(storageSummary);
        scanStatus=text("Lista virtualizada · páginas de "+PAGE_SIZE,12,MUTED,false);top.addView(scanStatus);scanProgress=new ProgressBar(this);scanProgress.setVisibility(View.GONE);top.addView(scanProgress,new LinearLayout.LayoutParams(-1,dp(3)));
        scanButton=button("Escanear almacenamiento");scanButton.setOnClickListener(v->startScan());top.addView(scanButton);box.addView(top);
        search=new EditText(this);search.setHint("Buscar archivo…");search.setHintTextColor(MUTED);search.setTextColor(Color.WHITE);search.setSingleLine(true);search.setBackgroundColor(CARD);search.setPadding(dp(12),0,dp(12),0);search.setText(prefs.getString("clean_query",""));box.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout filters=new LinearLayout(this);filters.setOrientation(LinearLayout.HORIZONTAL);filterSpinner=spinner(new String[]{"Todo","Imágenes","Videos","Audio","Grandes","Duplicados","Fotos similares"});sortSpinner=spinner(new String[]{"Más recientes","Más grandes","Más pequeños","Nombre"});filters.addView(filterSpinner,new LinearLayout.LayoutParams(0,dp(48),1f));filters.addView(sortSpinner,new LinearLayout.LayoutParams(0,dp(48),1f));box.addView(filters);
        filterSpinner.setSelection(Math.min(6,prefs.getInt("clean_filter",0)));sortSpinner.setSelection(Math.min(3,prefs.getInt("clean_sort",0)));
        thumbnails.cancelVisualWork();fileAdapter=new FileAdapter(thumbnails,(count,bytes)->{if(selectionSummary!=null){selectionSummary.setText(count==0?"Sin selección":count+" seleccionados · "+FileAdapter.formatBytes(bytes));if(deleteSelected!=null)deleteSelected.setEnabled(count>0);if(trashSelected!=null)trashSelected.setEnabled(count>0);}});
        fileList=new RecyclerView(this);LinearLayoutManager lm=new LinearLayoutManager(this);fileList.setLayoutManager(lm);fileList.setAdapter(fileAdapter);fileList.setItemViewCacheSize(8);fileList.setHasFixedSize(true);box.addView(fileList,new LinearLayout.LayoutParams(-1,0,1f));
        selectionSummary=text("Sin selección",12,MUTED,false);box.addView(selectionSummary);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);loadMore=button("Mostrar más");trashSelected=button("Papelera");deleteSelected=button("Eliminar");trashSelected.setEnabled(false);deleteSelected.setEnabled(false);actions.addView(loadMore,new LinearLayout.LayoutParams(0,dp(48),1f));actions.addView(trashSelected,new LinearLayout.LayoutParams(0,dp(48),1f));actions.addView(deleteSelected,new LinearLayout.LayoutParams(0,dp(48),1f));box.addView(actions);
        loadMore.setOnClickListener(v->appendPage());trashSelected.setOnClickListener(v->moveSelectionToTrash());deleteSelected.setOnClickListener(v->deleteSelection());
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){if(searchRunnable!=null)main.removeCallbacks(searchRunnable);searchRunnable=()->{if(search==null)return;prefs.edit().putString("clean_query",search.getText().toString()).apply();visibleLimit=PAGE_SIZE;applyFilter(false);};main.postDelayed(searchRunnable,260);}public void afterTextChanged(android.text.Editable e){}});
        filterSpinner.setOnItemSelectedListener(simpleSelection(()->{if(filterSpinner==null)return;prefs.edit().putInt("clean_filter",filterSpinner.getSelectedItemPosition()).apply();visibleLimit=PAGE_SIZE;applyFilter(false);}));
        sortSpinner.setOnItemSelectedListener(simpleSelection(()->{if(sortSpinner==null)return;prefs.edit().putInt("clean_sort",sortSpinner.getSelectedItemPosition()).apply();applyFilter(true);}));
        List<StorageItem> snapshot=storage.snapshot();
        if(snapshot.isEmpty())startScan();else{visibleLimit=Math.max(PAGE_SIZE,savedVisible);applyFilter(false);restoreListAnchorLater();startAnalysis(snapshot);}
    }

    private android.widget.AdapterView.OnItemSelectedListener simpleSelection(Runnable r){return new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?>p,View v,int pos,long id){r.run();}public void onNothingSelected(android.widget.AdapterView<?>p){}};}

    private void startScan(){
        if(scanRunning){storage.cancelScan();scanRunning=false;}analyzer.cancel();analysis=StorageAnalyzer.Result.empty();
        scanRunning=true;if(scanButton!=null){scanButton.setEnabled(false);scanButton.setText("Escaneando…");scanProgress.setVisibility(View.VISIBLE);scanStatus.setText("Leyendo metadatos en segundo plano…");}
        storage.scanAsync((gen,items,error)->runOnUiThread(()->{
            scanRunning=false;if(!currentPage.equals("cleaner")||scanButton==null)return;
            scanButton.setEnabled(true);scanButton.setText("Escanear almacenamiento");scanProgress.setVisibility(View.GONE);visibleLimit=Math.max(PAGE_SIZE,savedVisible);applyFilter(false);scanStatus.setText(items.size()+" archivos indexados"+(error==null?"":" · aviso: "+error));restoreListAnchorLater();startAnalysis(items);
        }));
    }

    private void startAnalysis(List<StorageItem> items){
        if(items==null||items.isEmpty())return;
        analyzer.analyzeAsync(items,new StorageAnalyzer.Callback(){
            @Override public void onPhase(String phase){runOnUiThread(()->{if(currentPage.equals("cleaner")&&scanStatus!=null)scanStatus.setText(phase+" Puedes seguir usando la app.");});}
            @Override public void onDone(StorageAnalyzer.Result result){runOnUiThread(()->{analysis=result;if(currentPage.equals("cleaner")&&scanStatus!=null){scanStatus.setText(result.duplicateGroups+" grupos duplicados · "+result.similarGroups+" grupos de fotos similares");if(filterSpinner!=null&&filterSpinner.getSelectedItemPosition()>=5)applyFilter(true);}});}
        });
    }

    private void applyFilter(boolean preserveAnchor){
        if(fileAdapter==null)return;int anchor=-1,offset=0;if(preserveAnchor&&fileList!=null){LinearLayoutManager lm=(LinearLayoutManager)fileList.getLayoutManager();anchor=lm.findFirstVisibleItemPosition();View v=lm.findViewByPosition(anchor);offset=v==null?0:v.getTop();}
        String q=search==null?"":search.getText().toString().trim().toLowerCase(Locale.ROOT);int f=filterSpinner==null?0:filterSpinner.getSelectedItemPosition();int sort=sortSpinner==null?0:sortSpinner.getSelectedItemPosition();
        filtered.clear();for(StorageItem x:storage.snapshot()){
            boolean type=switch(f){case 1->x.isImage();case 2->x.isVideo();case 3->x.isAudio();case 4->x.isLarge();case 5->analysis.duplicateKeys.contains(x.stableKey());case 6->analysis.similarKeys.contains(x.stableKey());default->true;};
            if(!type)continue;if(!q.isEmpty()&&!x.name.toLowerCase(Locale.ROOT).contains(q)&&!x.path.toLowerCase(Locale.ROOT).contains(q))continue;filtered.add(x);
        }
        Comparator<StorageItem> cmp=switch(sort){case 1->Comparator.comparingLong((StorageItem x)->x.size).reversed();case 2->Comparator.comparingLong(x->x.size);case 3->Comparator.comparing(x->x.name.toLowerCase(Locale.ROOT));default->Comparator.comparingLong((StorageItem x)->x.modified).reversed();};filtered.sort(cmp);
        int n=Math.min(Math.max(PAGE_SIZE,visibleLimit),filtered.size());fileAdapter.replace(new ArrayList<>(filtered.subList(0,n)));visibleLimit=n;updateCleanerSummary();
        if(preserveAnchor&&anchor>=0&&fileAdapter.getItemCount()>0){int a=Math.min(anchor,fileAdapter.getItemCount()-1);int off=offset;fileList.post(()->((LinearLayoutManager)fileList.getLayoutManager()).scrollToPositionWithOffset(a,off));}
    }

    private void appendPage(){if(fileAdapter==null)return;int start=fileAdapter.displayedCount();int end=Math.min(start+PAGE_SIZE,filtered.size());if(end<=start)return;fileAdapter.append(new ArrayList<>(filtered.subList(start,end)));visibleLimit=end;updateCleanerSummary();}
    private void updateCleanerSummary(){if(fileAdapter==null)return;if(storageSummary!=null)storageSummary.setText("Libre: "+FileAdapter.formatBytes(StorageRepository.freeBytes())+"  ·  "+fileAdapter.displayedCount()+"/"+filtered.size()+" visibles");if(loadMore!=null)loadMore.setEnabled(fileAdapter.displayedCount()<filtered.size());savedVisible=Math.max(PAGE_SIZE,fileAdapter.displayedCount());}

    private void deleteSelection(){
        if(fileAdapter==null)return;List<StorageItem> selected=fileAdapter.selectedItems();if(selected.isEmpty())return;int beforeCount=fileAdapter.displayedCount();deleteSelected.setEnabled(false);if(trashSelected!=null)trashSelected.setEnabled(false);deleteSelected.setText("Eliminando…");
        storage.deleteAsync(selected,(deleted,failed)->runOnUiThread(()->{
            if(!currentPage.equals("cleaner")||fileAdapter==null)return;fileAdapter.removeAll(deleted);visibleLimit=Math.max(PAGE_SIZE,beforeCount);applyFilter(true);deleteSelected.setText("Eliminar");deleteSelected.setEnabled(false);if(trashSelected!=null)trashSelected.setEnabled(false);updateCleanerSummary();scanStatus.setText(deleted.size()+" eliminados definitivamente"+(failed.isEmpty()?"":" · "+failed.size()+" no pudieron borrarse"));startAnalysis(storage.snapshot());
        }));
    }

    private void moveSelectionToTrash(){
        if(fileAdapter==null)return;List<StorageItem> selected=fileAdapter.selectedItems();if(selected.isEmpty())return;
        if(Build.VERSION.SDK_INT<30){deleteSelection();return;}
        ArrayList<Uri> uris=new ArrayList<>();pendingTrash.clear();
        for(StorageItem x:selected)if(x.uri!=null){uris.add(x.uri);pendingTrash.add(x);}
        if(uris.isEmpty()){scanStatus.setText("Android no expuso URI para mover esta selección a Papelera.");return;}
        try{
            PendingIntent request=MediaStore.createTrashRequest(getContentResolver(),uris,true);
            startIntentSenderForResult(request.getIntentSender(),TRASH_REQUEST,null,0,0,0);
        }catch(IntentSender.SendIntentException|RuntimeException e){pendingTrash.clear();scanStatus.setText("No se pudo abrir la Papelera de Android.");}
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=TRASH_REQUEST)return;
        ArrayList<StorageItem> moved=new ArrayList<>(pendingTrash);pendingTrash.clear();
        if(resultCode==RESULT_OK&&!moved.isEmpty()){
            storage.removeFromIndex(moved);
            if(currentPage.equals("cleaner")&&fileAdapter!=null){fileAdapter.removeAll(moved);visibleLimit=Math.max(PAGE_SIZE,savedVisible);applyFilter(true);updateCleanerSummary();scanStatus.setText(moved.size()+" movidos a Papelera. El espacio físico puede liberarse recién al vaciarla.");startAnalysis(storage.snapshot());}
        }else if(currentPage.equals("cleaner")&&scanStatus!=null)scanStatus.setText("Movimiento a Papelera cancelado.");
    }

    private void showSettings(){
        clearContent();ScrollView sv=new ScrollView(this);LinearLayout box=vertical();box.setPadding(dp(14),0,dp(14),dp(18));sv.addView(box);content.addView(sv,new LinearLayout.LayoutParams(-1,-1));
        box.addView(sectionTitle("Permisos y seguridad"));
        LinearLayout sh=card();sh.addView(text("Shizuku",16,Color.WHITE,true));sh.addView(text(shell.permissionGranted()?"Autorizado":"Necesario para perfiles y force-stop real",13,MUTED,false));Button b=button(shell.permissionGranted()?"Shizuku autorizado":"Autorizar Shizuku");b.setOnClickListener(v->shell.requestPermission(SHIZUKU_REQUEST));sh.addView(b);box.addView(sh);
        LinearLayout files=card();files.addView(text("Acceso a archivos",16,Color.WHITE,true));files.addView(text(Environment.isExternalStorageManager()?"Acceso completo activo":"Necesario para gestionar archivos fuera de las colecciones estándar",13,MUTED,false));Button all=button(Environment.isExternalStorageManager()?"Acceso activo":"Conceder acceso");all.setOnClickListener(v->openAllFilesSettings());files.addView(all);box.addView(files);
        LinearLayout protectedCard=card();protectedCard.addView(text("Apps protegidas por defecto",16,Color.WHITE,true));protectedCard.addView(text("Gmail · Mensajes Google/Samsung · Reloj Google/Samsung · Brave · ChatGPT · Samsung Voice Recorder · Teléfono/Contactos · servicios críticos del sistema",13,MUTED,false));protectedCard.addView(text("La protección se aplica a acciones por app. Battery Saver, Data Saver y frecuencia de pantalla son ajustes globales de Android.",12,MUTED,false));box.addView(protectedCard);
        LinearLayout about=card();about.addView(text("v1.15.4 PERFORMANCE / STABILITY",16,ACCENT,true));about.addView(text("RecyclerView virtualizado, páginas de 60, miniaturas limitadas, anti-OOM, búsqueda con debounce, duplicados/similitud por fases y tareas pesadas serializadas.",13,MUTED,false));box.addView(about);
    }

    private void startPermissionFlow(){
        if(prefs.getBoolean("permission_flow_v1154",false))return;prefs.edit().putBoolean("permission_flow_v1154",true).apply();
        ArrayList<String> need=new ArrayList<>();if(Build.VERSION.SDK_INT>=33){if(checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)!=PackageManager.PERMISSION_GRANTED)need.add(Manifest.permission.READ_MEDIA_IMAGES);if(checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)!=PackageManager.PERMISSION_GRANTED)need.add(Manifest.permission.READ_MEDIA_VIDEO);if(checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)!=PackageManager.PERMISSION_GRANTED)need.add(Manifest.permission.READ_MEDIA_AUDIO);if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)need.add(Manifest.permission.POST_NOTIFICATIONS);}else if(checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED)need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if(!need.isEmpty())requestPermissions(need.toArray(new String[0]),PERMISSION_REQUEST);else continuePermissionFlow();
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);if(requestCode==PERMISSION_REQUEST)main.postDelayed(this::continuePermissionFlow,250);}
    private void continuePermissionFlow(){if(Build.VERSION.SDK_INT>=30&&!Environment.isExternalStorageManager()){waitingForAllFiles=true;openAllFilesSettings();return;}requestShizukuIfNeeded();}
    private void requestShizukuIfNeeded(){if(!shell.permissionGranted())shell.requestPermission(SHIZUKU_REQUEST);}
    @Override protected void onResume(){super.onResume();if(waitingForAllFiles){waitingForAllFiles=false;main.postDelayed(this::requestShizukuIfNeeded,300);}}
    private void openAllFilesSettings(){try{Intent i=new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName()));startActivity(i);}catch(Throwable e){startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));}}

    private void saveListAnchor(){if(fileList==null)return;LinearLayoutManager lm=(LinearLayoutManager)fileList.getLayoutManager();savedAnchor=lm.findFirstVisibleItemPosition();View v=lm.findViewByPosition(savedAnchor);savedAnchorOffset=v==null?0:v.getTop();savedVisible=fileAdapter==null?PAGE_SIZE:Math.max(PAGE_SIZE,fileAdapter.displayedCount());}
    private void restoreListAnchorLater(){if(fileList==null||savedAnchor<0||fileAdapter==null||fileAdapter.getItemCount()==0)return;int a=Math.min(savedAnchor,fileAdapter.getItemCount()-1),o=savedAnchorOffset;fileList.post(()->((LinearLayoutManager)fileList.getLayoutManager()).scrollToPositionWithOffset(a,o));savedAnchor=-1;}
    @Override protected void onSaveInstanceState(Bundle out){saveListAnchor();out.putString("page",currentPage);out.putInt("anchor",savedAnchor);out.putInt("anchorOffset",savedAnchorOffset);out.putInt("visible",savedVisible);super.onSaveInstanceState(out);}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);thumbnails.trim(level);if(level>=TRIM_MEMORY_RUNNING_LOW){analyzer.cancel();if(fileAdapter!=null&&fileAdapter.displayedCount()>PAGE_SIZE*2){saveListAnchor();visibleLimit=Math.max(PAGE_SIZE,Math.min(fileAdapter.displayedCount(),PAGE_SIZE*2));applyFilter(true);}}}
    @Override protected void onDestroy(){if(searchRunnable!=null)main.removeCallbacks(searchRunnable);try{Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);}catch(Throwable ignored){}storage.shutdown();analyzer.shutdown();thumbnails.shutdown();optimizer.shutdown();shell.shutdown();super.onDestroy();}

    private long availableRam(){ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(m);return m.availMem;}
    private String thermalLabel(){if(Build.VERSION.SDK_INT<29)return "No disponible";int s=((PowerManager)getSystemService(POWER_SERVICE)).getCurrentThermalStatus();return switch(s){case 0->"Normal";case 1->"Leve";case 2->"Moderado";case 3->"Severo";case 4->"Crítico";case 5->"Emergencia";default->"Apagado térmico";};}
    private LinearLayout metricCard(String name,String value,String desc){LinearLayout c=card();c.addView(text(name,13,MUTED,false));c.addView(text(value,22,Color.WHITE,true));c.addView(text(desc,12,MUTED,false));return c;}
    private LinearLayout card(){LinearLayout c=vertical();c.setPadding(dp(14),dp(12),dp(14),dp(12));c.setBackgroundColor(CARD);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(6),0,dp(6));c.setLayoutParams(lp);return c;}
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView sectionTitle(String s){TextView t=text(s,18,Color.WHITE,true);t.setPadding(0,dp(8),0,dp(6));return t;}
    private TextView text(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);t.setLineSpacing(0,1.08f);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setPadding(0,dp(3),0,dp(3));return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(13);b.setAllCaps(false);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(27,35,42)));return b;}
    private Spinner spinner(String[] values){Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);s.setAdapter(a);s.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MUTED));return s;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}
