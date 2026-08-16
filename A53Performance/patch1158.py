from pathlib import Path

p=Path('A53Performance/app/src/main/java/com/fer/a53performance/MainActivity.java')
s=p.read_text()

def rep(old,new,name):
    global s
    if old not in s:
        raise SystemExit('missing patch anchor: '+name)
    s=s.replace(old,new,1)

rep('import android.app.Activity;\nimport android.app.ActivityManager;', 'import android.app.Activity;\nimport android.app.ActivityManager;\nimport android.app.AlertDialog;', 'alert import')
rep('import java.util.Comparator;\nimport java.util.List;', 'import java.util.Comparator;\nimport java.util.HashSet;\nimport java.util.List;', 'hashset import')
rep('    private Set<String> duplicateKeys=Collections.emptySet();\n    private Set<String> similarKeys=Collections.emptySet();', '    private Set<String> duplicateKeys=Collections.emptySet();\n    private Set<String> similarKeys=Collections.emptySet();\n    private StorageAnalyzer.DuplicateResult duplicateResult;', 'duplicate result field')
rep('    private Button loadMore,trashSelected,deleteSelected,scanButton;', '    private Button loadMore,trashSelected,deleteSelected,scanButton,safeDuplicates;', 'safe button field')
rep('    private boolean duplicateReady=false,similarReady=false,analysisRunning=false;', '    private boolean duplicateReady=false,similarReady=false,analysisRunning=false;\n    private int activeFilter=-1;', 'active filter')
rep('if(requestCode==SHIZUKU_REQUEST){toast(grantResult==PackageManager.PERMISSION_GRANTED?"Shizuku autorizado":"Shizuku no autorizado");if(currentPage.equals("home"))showHome();}', 'if(requestCode==SHIZUKU_REQUEST){boolean granted=grantResult==PackageManager.PERMISSION_GRANTED;toast(granted?"Shizuku autorizado":"Shizuku no autorizado");if(granted)AutoScheduler.scheduleDeferredRestore(this);if(currentPage.equals("home"))showHome();}', 'shizuku listener')
rep('loadMore=null;trashSelected=null;deleteSelected=null;scanButton=null;scanProgress=null;', 'loadMore=null;trashSelected=null;deleteSelected=null;scanButton=null;safeDuplicates=null;scanProgress=null;', 'clear safe button')
rep('        selectionSummary=text("Sin selección",12,MUTED,false);box.addView(selectionSummary);\n        LinearLayout actions=', '        selectionSummary=text("Sin selección",12,MUTED,false);box.addView(selectionSummary);\n        safeDuplicates=button("Seleccionar copias seguras");safeDuplicates.setVisibility(View.GONE);safeDuplicates.setOnClickListener(v->selectSafeDuplicates());box.addView(safeDuplicates,new LinearLayout.LayoutParams(-1,dp(46)));\n        LinearLayout actions=', 'safe duplicates button')
rep('        if(snapshot.isEmpty())startScan();else{visibleLimit=Math.max(PAGE_SIZE,savedVisible);handleFilterChange();restoreListAnchorLater();}', '        if(snapshot.isEmpty()||storage.needsRefresh())startScan();else{visibleLimit=Math.max(PAGE_SIZE,savedVisible);handleFilterChange();restoreListAnchorLater();}', 'generation refresh')
rep('analyzer.cancel();analysisRunning=false;duplicateReady=false;similarReady=false;duplicateKeys=Collections.emptySet();similarKeys=Collections.emptySet();duplicateGroups=0;similarGroups=0;', 'analyzer.cancel();analysisRunning=false;duplicateReady=false;similarReady=false;duplicateResult=null;duplicateKeys=Collections.emptySet();similarKeys=Collections.emptySet();duplicateGroups=0;similarGroups=0;', 'reset duplicate result')
rep('        int pos=filterSpinner==null?0:filterSpinner.getSelectedItemPosition();\n        if(pos==5){ensureDuplicates();return;}\n        if(pos==6){ensureSimilar();return;}\n        applyFilter(false);', '        int pos=filterSpinner==null?0:filterSpinner.getSelectedItemPosition();\n        if(pos!=activeFilter){fileAdapter.clearSelection();activeFilter=pos;}\n        if(safeDuplicates!=null)safeDuplicates.setVisibility(pos==5&&duplicateReady?View.VISIBLE:View.GONE);\n        if(pos==5){ensureDuplicates();return;}\n        if(pos==6){ensureSimilar();return;}\n        applyFilter(false);', 'filter safety')
rep('        if(duplicateReady){applyFilter(false);return;}', '        if(duplicateReady){updateDuplicateUi();applyFilter(false);return;}', 'duplicate ready UI')
rep('@Override public void onDone(StorageAnalyzer.DuplicateResult result){runOnUiThread(()->{analysisRunning=false;duplicateKeys=result.keys;duplicateGroups=result.groups;duplicateReady=true;if(currentPage.equals("cleaner")&&scanStatus!=null){diagnosticsStore.record("analysis",duplicateGroups+" grupos duplicados");scanStatus.setText(duplicateGroups+" grupos duplicados");if(filterSpinner!=null&&filterSpinner.getSelectedItemPosition()==5)applyFilter(true);}});}', '@Override public void onDone(StorageAnalyzer.DuplicateResult result){runOnUiThread(()->{analysisRunning=false;duplicateResult=result;duplicateKeys=result.keys;duplicateGroups=result.groups;duplicateReady=true;if(currentPage.equals("cleaner")&&scanStatus!=null){diagnosticsStore.record("analysis",duplicateGroups+" grupos duplicados · "+FileAdapter.formatBytes(result.recoverableBytes)+" recuperables");updateDuplicateUi();if(filterSpinner!=null&&filterSpinner.getSelectedItemPosition()==5)applyFilter(true);}});}', 'duplicate result UI')
rep('        analyzer.analyzeSimilarAsync(items,new StorageAnalyzer.Callback<StorageAnalyzer.SimilarResult>(){', '        analyzer.analyzeSimilarAsync(items,duplicateReady?duplicateResult:null,new StorageAnalyzer.Callback<StorageAnalyzer.SimilarResult>(){', 'reuse duplicates')
rep('analyzer.cancel();analysisRunning=false;duplicateReady=false;similarReady=false;duplicateKeys=Collections.emptySet();similarKeys=Collections.emptySet();duplicateGroups=0;similarGroups=0;', 'analyzer.cancel();analysisRunning=false;duplicateReady=false;similarReady=false;duplicateResult=null;duplicateKeys=Collections.emptySet();similarKeys=Collections.emptySet();duplicateGroups=0;similarGroups=0;', 'invalidate duplicate result')

old='''    private void deleteSelection(){
        if(fileAdapter==null)return;List<StorageItem> selected=fileAdapter.selectedItems();if(selected.isEmpty())return;int beforeCount=fileAdapter.displayedCount();deleteSelected.setEnabled(false);if(trashSelected!=null)trashSelected.setEnabled(false);deleteSelected.setText("Eliminando…");
        storage.deleteAsync(selected,(deleted,failed)->runOnUiThread(()->{
            if(!currentPage.equals("cleaner")||fileAdapter==null)return;fileAdapter.removeAll(deleted);visibleLimit=Math.max(PAGE_SIZE,beforeCount);applyFilter(true);deleteSelected.setText("Eliminar");deleteSelected.setEnabled(false);if(trashSelected!=null)trashSelected.setEnabled(false);updateCleanerSummary();diagnosticsStore.record("operation",failed.isEmpty()?deleted.size()+" eliminados correctamente":failed.size()+" fallos al eliminar");scanStatus.setText(deleted.size()+" eliminados definitivamente"+(failed.isEmpty()?"":" · "+failed.size()+" no pudieron borrarse"));invalidateAnalysisAndMaybeRun();
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
'''
new='''    private void deleteSelection(){confirmCleanup(false);}
    private void moveSelectionToTrash(){if(Build.VERSION.SDK_INT<30){confirmCleanup(false);return;}confirmCleanup(true);}

    private void confirmCleanup(boolean trash){
        if(fileAdapter==null)return;List<StorageItem> selected=selectedForAction();if(selected.isEmpty()){scanStatus.setText("No hay archivos seleccionados.");return;}
        long bytes=0;for(StorageItem x:selected)bytes+=x.size;boolean duplicateMode=filterSpinner!=null&&filterSpinner.getSelectedItemPosition()==5&&duplicateResult!=null;
        String message=selected.size()+" archivos · "+FileAdapter.formatBytes(bytes)+(trash?" se moverán a Papelera.":" se eliminarán definitivamente.");
        if(duplicateMode)message+="\\n\\nProtección de duplicados activa: se conservará al menos una copia exacta de cada grupo.";
        new AlertDialog.Builder(this).setTitle(trash?"Revisar antes de mover":"Revisar antes de eliminar").setMessage(message).setNegativeButton("Cancelar",null).setPositiveButton(trash?"Continuar":"Eliminar",(d,w)->{if(trash)performTrash(selected);else performDelete(selected);}).show();
    }

    private List<StorageItem> selectedForAction(){
        List<StorageItem> universe=storage.snapshot(),selected=fileAdapter.selectedItems(universe);if(duplicateResult==null||filterSpinner==null||filterSpinner.getSelectedItemPosition()!=5)return selected;
        HashSet<String> chosen=new HashSet<>();for(StorageItem x:selected)chosen.add(x.stableKey());boolean changed=false;
        for(StorageAnalyzer.DuplicateGroup group:duplicateResult.groupList){boolean all=true;for(StorageItem x:group.items)if(!chosen.contains(x.stableKey())){all=false;break;}if(all&&chosen.remove(group.keeperKey))changed=true;}
        if(!changed)return selected;ArrayList<StorageItem> safe=new ArrayList<>();for(StorageItem x:universe)if(chosen.contains(x.stableKey()))safe.add(x);fileAdapter.setSelection(safe);toast("Protección activa: se conservó una copia por grupo.");return safe;
    }

    private void selectSafeDuplicates(){
        if(fileAdapter==null||duplicateResult==null)return;ArrayList<StorageItem> safe=new ArrayList<>();for(StorageItem x:storage.snapshot())if(duplicateResult.safeDeleteKeys.contains(x.stableKey()))safe.add(x);fileAdapter.setSelection(safe);if(scanStatus!=null)scanStatus.setText(safe.size()+" copias seleccionadas de forma segura · hasta "+FileAdapter.formatBytes(duplicateResult.recoverableBytes)+" recuperables");
    }

    private void updateDuplicateUi(){
        if(duplicateResult==null)return;if(safeDuplicates!=null)safeDuplicates.setVisibility(filterSpinner!=null&&filterSpinner.getSelectedItemPosition()==5?View.VISIBLE:View.GONE);if(scanStatus!=null)scanStatus.setText(duplicateResult.groups+" grupos duplicados · hasta "+FileAdapter.formatBytes(duplicateResult.recoverableBytes)+" recuperables");
    }

    private void performDelete(List<StorageItem> selected){
        if(selected.isEmpty())return;int beforeCount=fileAdapter==null?PAGE_SIZE:fileAdapter.displayedCount();if(deleteSelected!=null){deleteSelected.setEnabled(false);deleteSelected.setText("Eliminando…");}if(trashSelected!=null)trashSelected.setEnabled(false);
        storage.deleteAsync(selected,(deleted,failed)->runOnUiThread(()->{if(!currentPage.equals("cleaner")||fileAdapter==null)return;fileAdapter.removeAll(deleted);visibleLimit=Math.max(PAGE_SIZE,beforeCount);applyFilter(true);deleteSelected.setText("Eliminar");deleteSelected.setEnabled(false);if(trashSelected!=null)trashSelected.setEnabled(false);updateCleanerSummary();diagnosticsStore.record("operation",failed.isEmpty()?deleted.size()+" eliminados correctamente":failed.size()+" fallos al eliminar");scanStatus.setText(deleted.size()+" eliminados definitivamente"+(failed.isEmpty()?"":" · "+failed.size()+" no pudieron borrarse"));invalidateAnalysisAndMaybeRun();}));
    }

    private void performTrash(List<StorageItem> selected){
        ArrayList<Uri> uris=new ArrayList<>();pendingTrash.clear();for(StorageItem x:selected)if(x.uri!=null){uris.add(x.uri);pendingTrash.add(x);}if(uris.isEmpty()){scanStatus.setText("Android no expuso URI para mover esta selección a Papelera.");return;}
        try{PendingIntent request=MediaStore.createTrashRequest(getContentResolver(),uris,true);startIntentSenderForResult(request.getIntentSender(),TRASH_REQUEST,null,0,0,0);}catch(IntentSender.SendIntentException|RuntimeException e){pendingTrash.clear();scanStatus.setText("No se pudo abrir la Papelera de Android.");}
    }
'''
rep(old,new,'safe cleanup block')

rep('LinearLayout protectedCard=card();protectedCard.addView(text("Apps protegidas por defecto",16,Color.WHITE,true));protectedCard.addView(text("Gmail · Mensajes Google/Samsung · Reloj Google/Samsung · Brave · ChatGPT · Samsung Voice Recorder · Teléfono/Contactos · servicios críticos del sistema",13,MUTED,false));protectedCard.addView(text("La protección se aplica a acciones por app. Battery Saver, Data Saver y frecuencia de pantalla son ajustes globales de Android.",12,MUTED,false));box.addView(protectedCard);', 'LinearLayout protectedCard=card();protectedCard.addView(text("Apps protegidas",16,Color.WHITE,true));protectedCard.addView(text("Protección base: Gmail · Mensajes · Reloj · Brave · ChatGPT · Grabadora · Teléfono/Contactos · servicios críticos.",13,MUTED,false));protectedCard.addView(text("Personalizadas: "+AppProtection.userProtected(this).size()+". Las apps marcadas como Nunca cerrar quedan fuera del limpiador de RAM.",12,MUTED,false));Button manageProtected=button("Elegir apps Nunca cerrar");manageProtected.setOnClickListener(v->showProtectedAppsDialog());protectedCard.addView(manageProtected);box.addView(protectedCard);', 'protected apps UI')
rep('LinearLayout about=card();about.addView(text("v1.15.7 THERMAL / ANALYSIS",16,ACCENT,true));about.addView(text("Análisis completo por bloques, doble hash perceptual, pausa térmica con progreso guardado, Shizuku con reconexión controlada y diagnóstico local.",13,MUTED,false));box.addView(about);', 'LinearLayout about=card();about.addView(text("v1.15.8 SAFETY / SMART CLEANER",16,ACCENT,true));about.addView(text("Rollback verificado de perfiles, Auto con reintentos limitados, duplicados seguros, índice persistente y apps Nunca cerrar personalizables.",13,MUTED,false));box.addView(about);', 'about v1158')
rep('@Override protected void onResume(){super.onResume();if(waitingForAllFiles){waitingForAllFiles=false;main.postDelayed(this::requestShizukuIfNeeded,300);}}', '@Override protected void onResume(){super.onResume();if(waitingForAllFiles){waitingForAllFiles=false;main.postDelayed(this::requestShizukuIfNeeded,300);}if(shell!=null&&shell.permissionGranted())AutoScheduler.scheduleDeferredRestore(this);}', 'resume deferred auto')

anchor='''    private String permissionStatus(){return permissionController.status();}

    private void saveListAnchor()'''
insert='''    private String permissionStatus(){return permissionController.status();}

    private void showProtectedAppsDialog(){
        List<ProtectedAppsController.AppEntry> apps=ProtectedAppsController.launcherApps(this);if(apps.isEmpty()){toast("No se encontraron apps con lanzador visibles.");return;}
        String[] labels=new String[apps.size()];boolean[] checked=new boolean[apps.size()];HashSet<String> chosen=new HashSet<>(AppProtection.userProtected(this));
        for(int i=0;i<apps.size();i++){ProtectedAppsController.AppEntry e=apps.get(i);labels[i]=e.label()+"\\n"+e.packageName();checked[i]=chosen.contains(e.packageName());}
        new AlertDialog.Builder(this).setTitle("Apps Nunca cerrar").setMultiChoiceItems(labels,checked,(d,which,on)->{String pkg=apps.get(which).packageName();if(on)chosen.add(pkg);else chosen.remove(pkg);}).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",(d,w)->{AppProtection.setUserProtected(this,chosen);toast(chosen.size()+" apps personalizadas protegidas");if(currentPage.equals("settings"))showSettings();}).show();
    }

    private void saveListAnchor()'''
rep(anchor,insert,'protected app dialog')
p.write_text(s)
print('patched MainActivity', len(s))
