#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit('usage: apply_block7_compile_fix.py <project-root>')
root = Path(sys.argv[1]).resolve()
path = root / 'app/src/main/java/com/fer/wavault/MediaArchiver.java'
text = path.read_text(encoding='utf-8')
old = '}catch(Throwable ignored){try{tmp.delete();}catch(Throwable ignored){}}'
new = '}catch(Throwable stagingFailure){try{tmp.delete();}catch(Throwable cleanupFailure){}}'
count = text.count(old)
if count != 1:
    raise SystemExit(f'expected exactly one nested-catch compile defect, found {count}')
path.write_text(text.replace(old, new), encoding='utf-8')
print('BLOCK7_MEDIA_ARCHIVER_COMPILE_FIX_APPLIED')
