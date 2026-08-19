#!/usr/bin/env python3
from pathlib import Path
import re,sys
ROOT=Path(__file__).resolve().parents[1]
VERSION='0.5.31'
def read(p): return (ROOT/p).read_text(encoding='utf-8')
def need(c,m):
    if not c: print('VERSION_CONSISTENCY_FAIL:',m,file=sys.stderr); raise SystemExit(1)
b=read('app/build.gradle.kts')
need(re.search(r'versionCode\s*=\s*81\b',b),'versionCode 81')
need('versionName = "0.5.31"' in b,'versionName 0.5.31')
for p in ('README.md','ARCHITECTURE.md','SECURITY.md','TESTING.md','RELEASE.md','TEST_MATRIX.md'):
    need(VERSION in read(p),f'{p} current version')
need(read('CHANGELOG.md').startswith('## 0.5.31'),'CHANGELOG current version first')
print('VERSION_CONSISTENCY_PASS version=0.5.31 code=81')
