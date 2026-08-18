#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"

echo "[1/19] v0.5.31 release/version regression"
python3 tools/version_consistency_check.py
python3 tools/v0531_block7_release_regression_test.py

echo "[2/19] v0.5.30 production behavior regression"
TMP530="tools/.compat_530.py"; cp tools/v0530_production_regression_test.py "$TMP530"
sed -i 's/versionCode = 80/versionCode = 81/g; s/versionName = "0.5.30"/versionName = "0.5.31"/g' "$TMP530"
python3 "$TMP530"; rm -f "$TMP530"

echo "[3/19] Block 2 NotificationListener regression"; python3 tools/v0530_block2_listener_regression_test.py
echo "[4/19] Block 3 persistence/idempotency regression"; python3 tools/v0530_block3_persistence_regression_test.py
echo "[5/19] Block 3 DB v13->v14 migration"; python3 tools/v0530_block3_db_migration_test.py
echo "[6/19] Block 4 media/storage regression"; python3 tools/v0530_block4_media_regression_test.py
echo "[7/19] Block 4 DB v14->v15 migration"; python3 tools/v0530_block4_db_migration_test.py
echo "[8/19] Block 4 media/storage stress"; python3 tools/v0530_block4_media_stress_test.py
echo "[9/19] Block 5 concurrency/background regression"; python3 tools/v0530_block5_concurrency_background_regression_test.py
echo "[10/19] Block 6 security/Android 16 regression"; python3 tools/v0530_block6_security_android16_regression_test.py

echo "[11/19] lifecycle regression suite"; python3 -m unittest -v tools/test_deletion_state_machine.py

echo "[12/19] DeletionGuard JVM self-test"
TMP="${TMPDIR:-/tmp}/wavault-java-selftest"; rm -rf "$TMP"; mkdir -p "$TMP"
javac -d "$TMP" app/src/main/java/com/fer/wavault/DeletionGuard.java tools/DeletionGuardSelfTest.java
java -cp "$TMP" DeletionGuardSelfTest

echo "[13/19] Java delimiter/lexical smoke"
python3 - <<'PYLEX'
from pathlib import Path
import re
pat=re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/', re.S)
files=[]
for folder in ('app/src/main/java','app/src/test/java','app/src/androidTest/java'):
 p=Path(folder)
 if p.exists(): files += sorted(p.rglob('*.java'))
bad=[]
for p in files:
 s=pat.sub('',p.read_text(encoding='utf-8')); stack=[]; pairs={'}':'{',')':'(',']':'['}
 for idx,c in enumerate(s):
  if c in '{([': stack.append((c,idx))
  elif c in pairs:
   if not stack or stack[-1][0]!=pairs[c]: bad.append((str(p),f'unmatched {c}',idx)); break
   stack.pop()
 else:
  if stack: bad.append((str(p),f'unclosed {stack[-1][0]}',stack[-1][1]))
print(f'JAVA_LEXICAL_BALANCE files={len(files)} bad={len(bad)}')
for item in bad: print('BAD',item)
if bad: raise SystemExit(1)
PYLEX

echo "[14/19] javac parser smoke"
set +e
javac -proc:none -Xmaxerrs 10000 $(find app/src/main/java app/src/test/java app/src/androidTest/java -name '*.java' -print) >"$TMP/javac-parser.log" 2>&1
set -e
python3 - "$TMP/javac-parser.log" <<'PYPARSE'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text(errors='replace')
patterns=['; expected','illegal start of','reached end of file while parsing','not a statement','class, interface, enum, or record expected','unclosed string literal','identifier expected',")' expected", "'}' expected"]
h=[line for line in s.splitlines() if any(p in line for p in patterns)]
print(f'JAVAC_PARSE_SMOKE syntax_errors={len(h)}')
for x in h[:40]: print(x)
if h: raise SystemExit(1)
PYPARSE

echo "[15/19] frozen v0.5.26/v0.5.27 invariants"
for v in 26 27; do
 SRC="tools/v05${v}_regression_test.py"; TMPPY="tools/.compat_${v}.py"; cp "$SRC" "$TMPPY"
 if [[ "$v" == 26 ]]; then
  sed -i 's/versionCode = 76/versionCode = 81/g; s/versionName = "0.5.26"/versionName = "0.5.31"/g; s/null, 13/null, 15/g; s/SQLite v13/SQLite v15/g' "$TMPPY"
 else
  sed -i 's/versionCode = 77/versionCode = 81/g; s/versionName = "0.5.27"/versionName = "0.5.31"/g; s/null, 13/null, 15/g; s/SQLite v13/SQLite v15/g' "$TMPPY"
 fi
 python3 "$TMPPY"; rm -f "$TMPPY"
done

echo "[16/19] security/privacy source audit"; python3 tools/security_source_audit.py

echo "[17/19] host JUnit signature/type smoke"
JTMP="${TMPDIR:-/tmp}/wavault-junit-stub"; rm -rf "$JTMP"; mkdir -p "$JTMP/src/org/junit" "$JTMP/out"
cat > "$JTMP/src/org/junit/Test.java" <<'JTEST'
package org.junit; public @interface Test {}
JTEST
cat > "$JTMP/src/org/junit/Assert.java" <<'JASSERT'
package org.junit; public final class Assert { public static void assertFalse(boolean v){} public static void assertTrue(boolean v){} public static void assertEquals(int a,int b){} public static void assertEquals(Object a,Object b){} }
JASSERT
javac -d "$JTMP/out" "$JTMP/src/org/junit/Test.java" "$JTMP/src/org/junit/Assert.java" app/src/main/java/com/fer/wavault/DeletionGuard.java app/src/test/java/com/fer/wavault/DeletionGuardTest.java
echo "HOST_JUNIT_SIGNATURE_TYPECHECK_PASS"

echo "[18/19] bounded executor runtime self-test"; python3 tools/v0530_block5_executor_backpressure_selftest.py

echo "[19/19] CI YAML / shell syntax"
python3 - <<'PYYAML'
from pathlib import Path
import yaml
p=Path('.github/workflows/android-ci.yml'); yaml.safe_load(p.read_text()); print('CI_YAML_PARSE_PASS')
PYYAML
for s in tools/*.sh; do bash -n "$s"; done
echo "SHELL_SYNTAX_PASS"
echo "STATIC_PASS"
