from pathlib import Path

p = Path('app/src/main/java/com/fer/wavault/VaultDb.java')
s = p.read_text(encoding='utf-8')
old = 'if(m==null){clearPendingPhysicalDelete(mediaId);return false;}'
new = 'if(m==null){clearPendingPhysicalDelete(mediaId);return true;}'
if old not in s:
    raise SystemExit('v0.5.24 delete-retry correction target not found')
p.write_text(s.replace(old, new), encoding='utf-8')
