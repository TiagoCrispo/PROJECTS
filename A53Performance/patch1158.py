from pathlib import Path
p=Path('A53Performance/app/src/main/java/com/fer/a53performance/MainActivity.java')
s=p.read_text()
old='''    private void startScan(){
        if(scanRunning){storage.cancelScan();scanRunning=false;}analyzer.cancel();analysisRunning=false;duplicateReady=false;similarReady=false;duplicateResult=null;duplicateKeys=Collections.emptySet();similarKeys=Collections.emptySet();duplicateGroups=0;similarGroups=0;'''
new='''    private void startScan(){
        if(fileAdapter!=null)fileAdapter.clearSelection();
        if(scanRunning){storage.cancelScan();scanRunning=false;}analyzer.cancel();analysisRunning=false;duplicateReady=false;similarReady=false;duplicateResult=null;duplicateKeys=Collections.emptySet();similarKeys=Collections.emptySet();duplicateGroups=0;similarGroups=0;'''
if old not in s: raise SystemExit('missing startScan anchor')
s=s.replace(old,new,1)
p.write_text(s)
print('patched selection reset')
