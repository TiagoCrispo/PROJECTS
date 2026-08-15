from pathlib import Path
import sys
root=Path(sys.argv[1])
p=root/'app/src/main/java/com/fer/wavault/MainActivity.java'
s=p.read_text()
# Keep the media menu installable on this build payload. The dedicated storage manager
# exists in the newer source package; this payload falls back to Settings.
s=s.replace('else if(which==3){showStorageManager();}', 'else if(which==3){showSettings();}')
p.write_text(s)
print('compile fix 2 applied')
