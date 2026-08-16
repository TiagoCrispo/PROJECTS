from pathlib import Path

p = Path('app/src/main/java/com/fer/wavault/GalleryExporter.java')
s = p.read_text(encoding='utf-8')
old = '        }catch(java.io.FileNotFoundException missing){return true;}catch(Throwable ignored){return false;}\n'
new = '        }catch(Throwable ignored){return false;}\n'
if old not in s:
    raise SystemExit('GalleryExporter compile-fix target not found')
p.write_text(s.replace(old, new), encoding='utf-8')
