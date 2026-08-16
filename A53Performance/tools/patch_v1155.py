from pathlib import Path
import re

p=Path('A53Performance/app/src/main/java/com/fer/a53performance/MainActivity.java')
s=p.read_text()
s=s.replace('import android.widget.TextView;\nimport android.widget.Toast;','import android.widget.TextView;\nimport android.widget.Toast;\nimport android.widget.Switch;')
s=s.replace('import java.util.ArrayList;\nimport java.util.Comparator;\nimport java.util.List;\nimport java.util.Locale;','import java.util.ArrayList;\nimport java.util.Collections;\nimport java.util.Comparator;\nimport java.util.List;\nimport java.util.Locale;\nimport java.util.Set;')
s=s.replace('    private StorageAnalyzer.Result analysis=StorageAnalyzer.Result.empty();','    private Set<String> duplicateKeys=Collections.emptySet();\n    private Set<String> similarKeys=Collections.emptySet();\n    private int duplicateGroups=0,similarGroups=0;')
s=s.replace('    private boolean scanRunning=false;\n    private boolean waitingForAllFiles=false;','    private boolean scanRunning=false;\n    private boolean waitingForAllFiles=false;\n    private boolean duplicateReady=false,similarReady=false,analysisRunning=false;')
s=s.replace('shell=new ShizukuShell();storage=new StorageRepository(this);','shell=new ShizukuShell(this);storage=new StorageRepository(this);')
old='filterSpinner.setOnItemSelectedListener(simpleSelection(()->{if(filterSpinner==null)return;prefs.edit().putInt("clean_filter",filterSpinner.getSelectedItemPosition()).apply();visibleLimit=PAGE_SIZE;applyFilter(false);}));'
new='filterSpinner.setOnItemSelectedListener(simpleSelection(()->{if(filterSpinner==null)return;prefs.edit().putInt("clean_filter",filterSpinner.getSelectedItemPosition()).apply();visibleLimit=PAGE_SIZE;handleFilterChange();}));'
assert old in s
s=s.replace(old,new,1)
old='if(snapshot.isEmpty())startScan();else{visibleLimit=Math.max(PAGE_SIZE,savedVisible);applyFilter(false);restoreListAnchorLater();startAnalysis(snapshot);}'
new='if(snapshot.isEmpty())startScan();else{visibleLimit=Math.max(PAGE_SIZE,savedVisible);handleFilterChange();restoreListAnchorLater();}'
assert old in s
s=s.replace(old,new,1)
old='if(scanRunning){storage.cancelScan();scanRunning=false;}analyzer.cancel();analysis=StorageAnalyzer.Result.empty();'
new='if(scanRunning){storage.cancelScan();scanRunning=false;}analyzer.cancel();analysisRunning=false;duplicateReady=false;similarReady=false;duplicateKeys=Collections.emptySet();similarKeys=Collections.emptySet();duplicateGroups=0;similarGroups=0;'
assert old in s
s=s.replace(old,new,1)
old='scanButton.setEnabled(true);scanButton.setText("Escanear almacenamiento");scanProgress.setVisibility(View.GONE);visibleLimit=Math.max(PAGE_SIZE,savedVisible);applyFilter(false);scanStatus.setText(items.size()+" archivos indexados"+(error==null?"":" · aviso: "+error));restoreListAnchorLater();startAnalysis(items);'
new='scanButton.setEnabled(true);scanButton.setText("Escanear almacenamiento");scanProgress.setVisibility(View.GONE);visibleLimit=Math.max(PAGE_SIZE,savedVisible);handleFilterChange();scanStatus.setText(items.size()+" archivos indexados"+(error==null?"":" · aviso: "+error));restoreListAnchorLater();'
assert old in s
s=s.replace(old,new,1)
pattern=r'    private void startAnalysis\(List<StorageItem> items\)\{.*?\n    \}\n\n    private void applyFilter'
repl='''    private void handleFilterChange(){
        if(fileAdapter==null)return;
        int pos=filterSpinner==null?0:filterSpinner.getSelectedItemPosition();
        if(pos==5){ensureDuplicates();return;}
        if(pos==6){ensureSimilar();return;}
        applyFilter(false);
    }

    private void ensureDuplicates(){
        if(duplicateReady){applyFilter(false);return;}
        List<StorageItem> items=storage.snapshot();
        if(items.isEmpty()){if(!scanRunning)startScan();return;}
        analyzer.cancel();analysisRunning=true;
        analyzer.analyzeDuplicatesAsync(items,new StorageAnalyzer.Callback<StorageAnalyzer.DuplicateResult>(){
            @Override public void onPhase(String phase){runOnUiThread(()->{if(currentPage.equals("cleaner")&&scanStatus!=null)scanStatus.setText(phase+" Resultado guardado en caché.");});}
            @Override public void onDone(StorageAnalyzer.DuplicateResult result){runOnUiThread(()->{analysisRunning=false;duplicateKeys=result.keys;duplicateGroups=result.groups;duplicateReady=true;if(currentPage.equals("cleaner")&&scanStatus!=null){scanStatus.setText(duplicateGroups+" grupos duplicados");if(filterSpinner!=null&&filterSpinner.getSelectedItemPosition()==5)applyFilter(true);}});}
        });
    }

    private void ensureSimilar(){
        if(similarReady){applyFilter(false);return;}
        List<StorageItem> items=storage.snapshot();
        if(items.isEmpty()){if(!scanRunning)startScan();return;}
        analyzer.cancel();analysisRunning=true;
        analyzer.analyzeSimilarAsync(items,new StorageAnalyzer.Callback<StorageAnalyzer.SimilarResult>(){
            @Override public void onPhase(String phase){runOnUiThread(()->{if(currentPage.equals("cleaner")&&scanStatus!=null)scanStatus.setText(phase+" Solo se ejecuta al abrir este filtro.");});}
            @Override public void onDone(StorageAnalyzer.SimilarResult result){runOnUiThread(()->{analysisRunning=false;similarKeys=result.keys;similarGroups=result.groups;similarReady=true;if(currentPage.equals("cleaner")&&scanStatus!=null){scanStatus.setText(similarGroups+" grupos de fotos similares");if(filterSpinner!=null&&filterSpinner.getSelectedItemPosition()==6)applyFilter(true);}});}
        });
    }

    private void invalidateAnalysisAndMaybeRun(){
        analyzer.cancel();analysisRunning=false;duplicateReady=false;similarReady=false;duplicateKeys=Collections.emptySet();similarKeys=Collections.emptySet();duplicateGroups=0;similarGroups=0;
        if(currentPage.equals("cleaner")&&filterSpinner!=null&&filterSpinner.getSelectedItemPosition()>=5)handleFilterChange();
    }

    private void applyFilter'''
s,n=re.subn(pattern,repl,s,flags=re.S)
assert n==1,n
s=s.replace('case 5->analysis.duplicateKeys.contains(x.stableKey());case 6->analysis.similarKeys.contains(x.stableKey());','case 5->duplicateKeys.contains(x.stableKey());case 6->similarKeys.contains(x.stableKey());')
s=s.replace('startAnalysis(storage.snapshot());','invalidateAnalysisAndMaybeRun();')
s=s.replace('if(level>=TRIM_MEMORY_RUNNING_LOW){analyzer.cancel();if(fileAdapter!=null','if(level>=TRIM_MEMORY_RUNNING_LOW){analyzer.cancel();analysisRunning=false;if(fileAdapter!=null')
marker='        LinearLayout about=card();about.addView(text("v1.15.4 PERFORMANCE / STABILITY",16,ACCENT,true));about.addView(text("RecyclerView virtualizado, páginas de 60, miniaturas limitadas, anti-OOM, búsqueda con debounce, duplicados/similitud por fases y tareas pesadas serializadas.",13,MUTED,false));box.addView(about);'
assert marker in s
replacement='''        LinearLayout auto=card();auto.addView(text("Auto después de reiniciar",16,Color.WHITE,true));auto.addView(text("Opcional: reaplica el último perfil exitoso mediante trabajo persistente. No borra archivos ni cierra apps automáticamente.",13,MUTED,false));Switch autoSwitch=new Switch(this);autoSwitch.setText("Reaplicar último perfil");autoSwitch.setTextColor(Color.WHITE);autoSwitch.setChecked(prefs.getBoolean("auto_restore_profile",false));autoSwitch.setOnCheckedChangeListener((button,checked)->prefs.edit().putBoolean("auto_restore_profile",checked).apply());auto.addView(autoSwitch);box.addView(auto);
        LinearLayout about=card();about.addView(text("v1.15.5 STABILITY CORE",16,ACCENT,true));about.addView(text("Shizuku UserService, Auto persistente opcional, análisis bajo demanda con caché, agrupación de similares más estricta y pruebas de arranque Android.",13,MUTED,false));box.addView(about);'''
s=s.replace(marker,replacement,1)
p.write_text(s)
