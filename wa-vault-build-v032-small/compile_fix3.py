from pathlib import Path
import sys
root=Path(sys.argv[1])
p=root/'app/src/main/java/com/fer/wavault/MainActivity.java'
s=p.read_text()
s=s.replace('String[] opts={"Borrar archivos >30 días","Borrar archivos >90 días","Limpiar caché de miniaturas","Borrar todos los archivos"};','String[] opts={"Borrar archivos >30 días","Borrar archivos >90 días","Borrar todos los archivos"};')
s=s.replace('else if(w==2){MediaThumbnailLoader.clearDiskCache(this);Toast.makeText(this,"Caché de miniaturas limpiada",Toast.LENGTH_SHORT).show();}\n            else if(w==3)confirmClear(true);','else if(w==2)confirmClear(true);')
p.write_text(s)
print('WA Vault v0.3.3 storage dialog fixed')
