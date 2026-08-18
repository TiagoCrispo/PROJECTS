#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / 'app' / 'src' / 'main' / 'java'
CATCH = re.compile(r'catch\s*\(\s*(?:final\s+)?[\w.$<>?]+(?:\s*\|\s*[\w.$<>?]+)*\s+(\w+)\s*\)\s*\{')


def strip_non_code(text: str) -> str:
    out=[]; i=0; n=len(text); state='code'
    while i<n:
        c=text[i]; d=text[i+1] if i+1<n else ''
        if state=='code':
            if c=='/' and d=='/': out.extend('  '); i+=2; state='line'; continue
            if c=='/' and d=='*': out.extend('  '); i+=2; state='block'; continue
            if c=='"': out.append(' '); i+=1; state='string'; continue
            if c=="'": out.append(' '); i+=1; state='char'; continue
            out.append(c); i+=1; continue
        if state=='line':
            if c=='\n': out.append('\n'); state='code'
            else: out.append(' ')
            i+=1; continue
        if state=='block':
            if c=='*' and d=='/': out.extend('  '); i+=2; state='code'; continue
            out.append('\n' if c=='\n' else ' '); i+=1; continue
        quote='"' if state=='string' else "'"
        if c=='\\':
            out.append(' '); i+=1
            if i<n:
                out.append('\n' if text[i]=='\n' else ' '); i+=1
            continue
        if c==quote:
            out.append(' '); i+=1; state='code'; continue
        out.append('\n' if c=='\n' else ' '); i+=1
    return ''.join(out)


def matching_brace(code: str, open_at: int) -> int:
    depth=0
    for i in range(open_at, len(code)):
        if code[i]=='{': depth += 1
        elif code[i]=='}':
            depth -= 1
            if depth == 0: return i
    return -1


def find_problems(raw: str, label: str):
    code=strip_non_code(raw); problems=[]
    for outer in CATCH.finditer(code):
        name=outer.group(1); open_at=outer.end()-1; close_at=matching_brace(code,open_at)
        if close_at<0: continue
        for inner in CATCH.finditer(code,open_at+1,close_at):
            if inner.group(1)==name:
                line=raw.count('\n',0,outer.start())+1
                inner_line=raw.count('\n',0,inner.start())+1
                problems.append(f'{label}:{line}->{inner_line}: catch parameter {name!r} redeclared in enclosing catch scope')
    return problems

probe='class P { void x(){ try{} catch(Throwable ignored){ try{} catch(Throwable ignored){} } } }'
if not find_problems(probe,'self-test'):
    print('NESTED_CATCH_CHECKER_SELFTEST_FAIL'); sys.exit(2)

problems=[]
for path in sorted(JAVA_ROOT.rglob('*.java')):
    raw=path.read_text(errors='replace')
    problems.extend(find_problems(raw,str(path.relative_to(ROOT))))
if problems:
    print('NESTED_CATCH_REDECLARATION_FAIL')
    print('\n'.join(problems))
    sys.exit(1)
print('NESTED_CATCH_REDECLARATION_PASS')
