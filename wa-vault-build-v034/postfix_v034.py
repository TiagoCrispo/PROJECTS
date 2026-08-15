from pathlib import Path
import re

root = Path('wa-vault-v034-project')
p = root / 'app/src/main/java/com/fer/wavault/MainActivity.java'
s = p.read_text()

# Recover the one rejected MainActivity settings hunk, if patch created a .rej file.
rej = p.with_suffix('.java.rej')
if rej.exists():
    lines = rej.read_text().splitlines(True)
    desired = []
    in_hunk = False
    for line in lines:
        if line.startswith('@@'):
            in_hunk = True
            continue
        if not in_hunk or line.startswith(('---', '+++')):
            continue
        if line.startswith('+') or line.startswith(' '):
            desired.append(line[1:])
    block = ''.join(desired)
    anchor = '        LinearLayout capture=card();'
    start = s.index(anchor, s.index('    private void showSettings(){'))
    end = s.index('    private boolean hasAllMediaPermissions()', start)
    s = s[:start] + block + s[end:]

# Normalize Samsung-return flags.
s = s.replace(
    '    private boolean openedBatterySettings = false;\n        openedSamsungSettings = false;\n    private boolean openedSamsungSettings = false;',
    '    private boolean openedBatterySettings = false;\n    private boolean openedSamsungSettings = false;'
)
s = s.replace(
    '        openedBatterySettings = false;\n        if (cameBack) {',
    '        openedBatterySettings = false;\n        openedSamsungSettings = false;\n        if (cameBack) {'
)

# Replace diagnostic row using method boundaries so literal-newline corruption is impossible.
a = s.index('    private TextView diagRow(')
b = s.index('    private String friendlyEventCode(', a)
diag = '    private TextView diagRow(String label,String value){return text(label+"\\n"+(value==null||value.isEmpty()?"Sin datos":value),12,muted,false);}\n'
s = s[:a] + diag + s[b:]

# Replace storage manager with the final unified recovered-files version.
start = s.index('    private void showStorageManager(){')
end = s.index('    private void confirmDeleteUnlinkedMedia(){', start)
storage = '''    private void showStorageManager(){
        VaultDb.Stats st;try{st=db.getStats();}catch(Throwable t){st=new VaultDb.Stats();}
        String msg="WA Vault usa "+human(st.totalBytes)+"\\n\\n"
                +"Fotos  "+human(st.imageBytes)+"\\n"
                +"Videos  "+human(st.videoBytes)+"\\n"
                +"Audio  "+human(st.audioBytes)+"\\n\\n"
                +st.media+" archivos guardados"
                +(st.unlinkedMedia>0?"\\n"+st.unlinkedMedia+" sin mensaje asociado":"")
                +"\\n\\nEstas limpiezas solo borran copias de WA Vault; no modifican WhatsApp.";
        String[] opts={"Borrar archivos sin mensaje asociado","Borrar archivos >30 días","Borrar archivos >90 días","Limpiar caché de miniaturas","Borrar todos los archivos"};
        new AlertDialog.Builder(this).setTitle("Almacenamiento recuperado").setMessage(msg).setItems(opts,(d,w)->{
            if(w==0)confirmDeleteUnlinkedMedia();
            else if(w==1)confirmDeleteOldMedia(30);
            else if(w==2)confirmDeleteOldMedia(90);
            else if(w==3){MediaThumbnailLoader.clearDiskCache(this);Toast.makeText(this,"Caché de miniaturas limpiada",Toast.LENGTH_SHORT).show();}
            else if(w==4)confirmClear(true);
        }).setNegativeButton("Cerrar",null).show();
    }

'''
s = s[:start] + storage + s[end:]

# Remove every previous onDestroy implementation, regardless of spacing, and insert one canonical lifecycle cleanup.
s = re.sub(
    r'\n\s*@Override\s+protected\s+void\s+onDestroy\s*\(\s*\)\s*\{.*?\n\s{4}\}\n',
    '\n',
    s,
    flags=re.S,
)
canonical_destroy = '''    @Override protected void onDestroy(){
        try{if(uiReceiverRegistered)unregisterReceiver(dataChangedReceiver);}catch(Throwable ignored){}
        uiReceiverRegistered=false;
        uiRefreshHandler.removeCallbacksAndMessages(null);
        try { if (biometricCancel != null) biometricCancel.cancel(); } catch (Throwable ignored) {}
        releaseAudioPlayer(false);
        super.onDestroy();
    }

'''
resume_anchor = '    @Override protected void onResume() {'
pos = s.index(resume_anchor)
s = s[:pos] + canonical_destroy + s[pos:]

p.write_text(s)

# Hard checks before invoking Gradle.
assert 'versionCode = 34' in (root / 'app/build.gradle.kts').read_text()
assert 'versionName = "0.3.4"' in (root / 'app/build.gradle.kts').read_text()
assert s.count('protected void onDestroy()') == 1, s.count('protected void onDestroy()')
assert 'private void showDiagnostics()' in s
assert 'private void confirmDeleteUnlinkedMedia()' in s
assert 'private TextView diagRow(String label,String value)' in s
assert 'openedSamsungSettings' in s
print('v0.3.4 post-fix checks passed')
