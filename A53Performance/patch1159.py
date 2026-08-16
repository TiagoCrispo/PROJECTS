from pathlib import Path
p=Path('A53Performance/app/src/main/java/com/fer/a53performance/MainActivity.java')
s=p.read_text()

def rep(old,new,name):
    global s
    if old not in s:
        raise SystemExit('missing '+name)
    s=s.replace(old,new,1)

rep('    private StorageAnalyzer.DuplicateResult duplicateResult;','    private StorageAnalyzer.DuplicateResult duplicateResult;\n    private StorageAnalyzer.SimilarResult similarResult;','similar result field')
rep('    private Button loadMore,trashSelected,deleteSelected,scanButton,safeDuplicates;','    private Button loadMore,trashSelected,deleteSelected,scanButton,safeDuplicates,reviewSimilar;','button field')
rep('scanButton=null;safeDuplicates=null;scanProgress=null;','scanButton=null;safeDuplicates=null;reviewSimilar=null;scanProgress=null;','clear buttons')
rep('        safeDuplicates=button("Seleccionar copias seguras");safeDuplicates.setVisibility(View.GONE);safeDuplicates.setOnClickListener(v->selectSafeDuplicates());box.addView(safeDuplicates,new LinearLayout.LayoutParams(-1,dp(46)));\n        LinearLayout actions=', '        safeDuplicates=button("Seleccionar copias seguras");safeDuplicates.setVisibility(View.GONE);safeDuplicates.setOnClickListener(v->selectSafeDuplicates());box.addView(safeDuplicates,new LinearLayout.LayoutParams(-1,dp(46)));\n        reviewSimilar=button("Revisar grupos similares");reviewSimilar.setVisibility(View.GONE);reviewSimilar.setOnClickListener(v->showSimilarGroups());box.addView(reviewSimilar,new LinearLayout.LayoutParams(-1,dp(46)));\n        LinearLayout actions=', 'similar review button')
rep('duplicateReady=false;similarReady=false;duplicateResult=null;duplicateKeys=', 'duplicateReady=false;similarReady=false;duplicateResult=null;similarResult=null;duplicateKeys=', 'scan reset')
rep('        if(safeDuplicates!=null)safeDuplicates.setVisibility(pos==5&&duplicateReady?View.VISIBLE:View.GONE);\n        if(pos==5)', '        if(safeDuplicates!=null)safeDuplicates.setVisibility(pos==5&&duplicateReady?View.VISIBLE:View.GONE);\n        if(reviewSimilar!=null)reviewSimilar.setVisibility(pos==6&&similarReady?View.VISIBLE:View.GONE);\n        if(pos==5)', 'filter buttons')
rep('        if(similarReady){applyFilter(false);return;}', '        if(similarReady){updateSimilarUi();applyFilter(false);return;}', 'similar ready')
old='@Override public void onDone(StorageAnalyzer.SimilarResult result){runOnUiThread(()->{analysisRunning=false;similarKeys=result.keys;similarGroups=result.groups;similarReady=true;if(currentPage.equals("cleaner")&&scanStatus!=null){diagnosticsStore.record("analysis",similarGroups+" grupos de fotos similares");scanStatus.setText(similarGroups+" grupos de fotos similares");if(filterSpinner!=null&&filterSpinner.getSelectedItemPosition()==6)applyFilter(true);}});}'
new='@Override public void onDone(StorageAnalyzer.SimilarResult result){runOnUiThread(()->{analysisRunning=false;similarResult=result;similarKeys=result.keys;similarGroups=result.groups;similarReady=true;if(currentPage.equals("cleaner")&&scanStatus!=null){diagnosticsStore.record("analysis",similarGroups+" grupos de fotos similares");updateSimilarUi();if(filterSpinner!=null&&filterSpinner.getSelectedItemPosition()==6)applyFilter(true);}});}'
rep(old,new,'similar done')
rep('duplicateReady=false;similarReady=false;duplicateResult=null;duplicateKeys=', 'duplicateReady=false;similarReady=false;duplicateResult=null;similarResult=null;duplicateKeys=', 'invalidate reset')
rep('    private void performDelete(List<StorageItem> selected){\n        if(selected.isEmpty())return;', '    private void performDelete(List<StorageItem> selected){\n        analyzer.cancel();analysisRunning=false;\n        if(selected.isEmpty())return;', 'delete coordination')
rep('    private void performTrash(List<StorageItem> selected){\n        ArrayList<Uri>', '    private void performTrash(List<StorageItem> selected){\n        analyzer.cancel();analysisRunning=false;\n        ArrayList<Uri>', 'trash coordination')
rep('protectedCard.addView(text("Personalizadas: "+AppProtection.userProtected(this).size()+". Las apps marcadas como Nunca cerrar quedan fuera del limpiador de RAM.",12,MUTED,false));', 'protectedCard.addView(text("Personalizadas: "+AppProtection.userProtected(this).size()+". También se preservan automáticamente apps activas con foreground/media detectables.",12,MUTED,false));', 'protected text')
rep('LinearLayout about=card();about.addView(text("v1.15.8 SAFETY / SMART CLEANER",16,ACCENT,true));about.addView(text("Rollback verificado de perfiles, Auto con reintentos limitados, duplicados seguros, índice persistente y apps Nunca cerrar personalizables.",13,MUTED,false));box.addView(about);', 'LinearLayout about=card();about.addView(text("v1.15.9 STORAGE / SAFE RAM",16,ACCENT,true));about.addView(text("Multi-volumen/microSD, índice incremental, perfiles deterministas, RAM sensible y revisión por grupos de fotos similares.",13,MUTED,false));box.addView(about);', 'about')
anchor='''    private String permissionStatus(){return permissionController.status();}

    private void showProtectedAppsDialog()'''
insert='''    private void updateSimilarUi(){if(similarResult==null)return;if(reviewSimilar!=null)reviewSimilar.setVisibility(filterSpinner!=null&&filterSpinner.getSelectedItemPosition()==6?View.VISIBLE:View.GONE);if(scanStatus!=null)scanStatus.setText(similarResult.groups+" grupos de fotos similares · revisión manual, sin borrado automático");}

    private void showSimilarGroups(){
        if(similarResult==null||similarResult.groupList.isEmpty()){toast("No hay grupos similares para revisar.");return;}
        int n=Math.min(80,similarResult.groupList.size());String[] labels=new String[n];
        for(int i=0;i<n;i++){StorageAnalyzer.SimilarGroup g=similarResult.groupList.get(i);StringBuilder b=new StringBuilder("Grupo ").append(i+1).append(" · ").append(g.items.size()).append(" fotos");for(int j=0;j<Math.min(3,g.items.size());j++)b.append("\\n").append(g.items.get(j).name);if(g.items.size()>3)b.append("\\n…");labels[i]=b.toString();}
        new AlertDialog.Builder(this).setTitle("Fotos similares · solo revisión").setItems(labels,(d,which)->{StorageAnalyzer.SimilarGroup g=similarResult.groupList.get(which);fileAdapter.setSelection(g.items);if(scanStatus!=null)scanStatus.setText("Grupo "+(which+1)+" seleccionado para revisar · "+g.items.size()+" fotos. No se elimina nada automáticamente.");}).setNegativeButton("Cerrar",null).show();
    }

    private String permissionStatus(){return permissionController.status();}

    private void showProtectedAppsDialog()'''
rep(anchor,insert,'similar dialog')
p.write_text(s)
print('patched MainActivity v1159',len(s))
