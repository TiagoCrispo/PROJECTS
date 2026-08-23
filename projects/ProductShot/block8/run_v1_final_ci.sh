#!/usr/bin/env bash
set -euo pipefail

ROOT="$GITHUB_WORKSPACE"
SRC="$RUNNER_TEMP/productshot-v1-src"
OUT="$RUNNER_TEMP/productshot-v1-final"
mkdir -p "$OUT/apk" "$OUT/source" "$OUT/diagnostics"
rm -rf "$SRC"
mkdir -p "$SRC"

# 1) Rebuild exact audited source base.
cd "$ROOT/projects/ProductShot/block8"
base_parts=(
  clean.b64.00 clean.b64.01 clean.b64.02 clean.b64.03
  clean.b64.04 clean.b64.05 clean.b64.06
)
for p in "${base_parts[@]}"; do test -s "$p"; done
BASE="$RUNNER_TEMP/ProductShot-v0.9.8-source.zip"
cat "${base_parts[@]}" | base64 -d > "$BASE"
echo "385f4e5f21cbca4194d891452969cfe44f40a667394caf799b688942745cf3ec  $BASE" | sha256sum -c -
unzip -t "$BASE" > "$OUT/diagnostics/base-integrity.txt"
unzip -q "$BASE" -d "$SRC"

# 2) Replay every accepted block in order.
python3 "$ROOT/projects/ProductShot/block8/compile_hotfix.py" "$SRC"
cd "$SRC"
patch -p1 < "$ROOT/projects/ProductShot/block8/block9-guided-local-install.patch"
python3 "$ROOT/projects/ProductShot/block8/block11_apply.py" "$SRC"
python3 "$ROOT/projects/ProductShot/block8/block12_apply.py" "$SRC"

cd "$ROOT/projects/ProductShot/block8"
block13_parts=(block13gz.b64.00 block13tail.b64.00 block13tail.b64.01 block13tail.b64.02 block13tail.b64.03)
echo "b8c1c2ef975acb585c6f0c3f5544c21bfa3d61e0b14dd636c23b2579f026f519  block13gz.b64.00" | sha256sum -c -
echo "84a81fe9b085cbf70893fedd72cafb4f05f0ceb6ec3755bab820813eae1c7b6d  block13tail.b64.00" | sha256sum -c -
echo "2ae9844e057e75842c9eff95b775f6fad584e7f76f47ece5ee1ec4610f6bf324  block13tail.b64.01" | sha256sum -c -
echo "7634758fe1e03710d83447bfba8717a08d7d1891ac996241de6a71326d3cff3c  block13tail.b64.02" | sha256sum -c -
echo "ca8df1c3f113d6145bcd4d5ee41fa3ccfea303ffc19be2a44e80c4e099f8142c  block13tail.b64.03" | sha256sum -c -
python3 -c 'import sys,base64,gzip; b=b"".join(open(p,"rb").read() for p in sys.argv[1:-1]); open(sys.argv[-1],"wb").write(gzip.decompress(base64.b64decode(b, validate=False)))' "${block13_parts[@]}" "$RUNNER_TEMP/block13_apply.py"
echo "cca3951f50f0d5ad3c857585d04a2407b2a6bb4bece128ef9c7b52fa68924ebb  $RUNNER_TEMP/block13_apply.py" | sha256sum -c -
python3 "$RUNNER_TEMP/block13_apply.py" "$SRC"

python3 "$ROOT/projects/ProductShot/block8/block14_apply.py" "$SRC"

TRANSPORT15="$ROOT/projects/ProductShot/block8/block15-final-robustness.patch.gz.b64"
echo "613c041e83edae33aab505592e1e54e396724e450ef8b00aee6d850bae9c211e  $TRANSPORT15" | sha256sum -c -
base64 -d "$TRANSPORT15" | gzip -d > "$RUNNER_TEMP/block15.patch"
echo "289065e019ae3ae177df5c97527eae75f236ffaf20c67f0652a59a62d9ca1cb9  $RUNNER_TEMP/block15.patch" | sha256sum -c -
cd "$SRC"
patch -p1 < "$RUNNER_TEMP/block15.patch"
WARNPATCH="$ROOT/projects/ProductShot/block8/block15-warning-cleanup.patch"
echo "467c226083311355b7a90c56839fdba84c6aeb049479d963620a4e20c18dd8ec  $WARNPATCH" | sha256sum -c -
patch -p1 < "$WARNPATCH"

# 3) Freeze final v1.0.0 against the CI-clean v0.9.15 baseline.
APPLIER16="$ROOT/projects/ProductShot/block8/block16_apply.py"
echo "3db360d769787d34690ef4461206a4431a482c2cd5e556aa5c04fdb3d083cee3  $APPLIER16" | sha256sum -c -
python3 "$APPLIER16" "$SRC"

# 4) Source-level release freeze checks.
cd "$SRC"
grep -F 'versionCode = 100' app/build.gradle.kts
grep -F 'versionName = "1.0.0"' app/build.gradle.kts
grep -F 'android:allowBackup="false"' app/src/main/AndroidManifest.xml
grep -F 'android:usesCleartextTraffic="false"' app/src/main/AndroidManifest.xml
grep -F 'ActivityResultContracts.TakePicture()' app/src/main/java/com/tiagocrispo/furnitureshot/ui/FurnitureShotApp.kt
grep -F 'ActivityResultContracts.GetContent()' app/src/main/java/com/tiagocrispo/furnitureshot/ui/FurnitureShotApp.kt
grep -F 'Text("Generar")' app/src/main/java/com/tiagocrispo/furnitureshot/ui/FurnitureShotApp.kt
grep -F 'Text("Descargar foto modelada")' app/src/main/java/com/tiagocrispo/furnitureshot/ui/FurnitureShotApp.kt
test ! -e app/src/main/java/com/tiagocrispo/furnitureshot/processing/ReferenceStyleAnalyzer.kt
test ! -e app/src/main/java/com/tiagocrispo/furnitureshot/processing/CatalogSheetComposer.kt
if grep -RniE 'qualityReferencePath|referenceImagePath|ReferenceStyleAnalyzer|PROCEDURAL_REFERENCE_GUIDED|CatalogReferenceStyle|CatalogSheetComposer' app/src/main/java; then
  echo 'Legacy model-photo/reference/collage architecture still ships' >&2
  exit 1
fi
python3 scripts/static_validate.py . | tee "$OUT/diagnostics/static-validation.txt"

# Optional exact-source snapshot for engineering inspection. This does not alter release behavior.
if [[ "${PRODUCTSHOT_SNAPSHOT_ONLY:-0}" == "1" ]]; then
  zip -qr "$OUT/source/ProductShot-v1.0.0-SNAPSHOT-SOURCE.zip" . -x '.gradle/*' '.kotlin/*' 'app/build/*' 'build/*'
  unzip -t "$OUT/source/ProductShot-v1.0.0-SNAPSHOT-SOURCE.zip" > "$OUT/source/source-integrity.txt"
  sha256sum "$OUT/source/ProductShot-v1.0.0-SNAPSHOT-SOURCE.zip" > "$OUT/source/ProductShot-v1.0.0-SNAPSHOT-SOURCE.sha256"
  echo 'PRODUCTSHOT_V1_SOURCE_SNAPSHOT_OK'
  exit 0
fi

# 5) Gradle gates.
gradle --no-daemon :app:dependencies --configuration debugRuntimeClasspath > "$OUT/diagnostics/dependency-report.txt"
set +e
gradle --no-daemon --stacktrace :app:testDebugUnitTest 2>&1 | tee "$OUT/diagnostics/unit-test.log"
status=${PIPESTATUS[0]}
set -e
[ "$status" -eq 0 ]
set +e
gradle --no-daemon --stacktrace :app:lintDebug 2>&1 | tee "$OUT/diagnostics/lint.log"
status=${PIPESTATUS[0]}
set -e
[ "$status" -eq 0 ]
set +e
gradle --no-daemon --stacktrace :app:assembleDebug 2>&1 | tee "$OUT/diagnostics/build.log"
status=${PIPESTATUS[0]}
set -e
[ "$status" -eq 0 ]
if grep -nE '(^|[[:space:]])w: ' "$OUT/diagnostics/build.log"; then
  echo 'Kotlin compiler warning detected in final build' >&2
  exit 1
fi

# 6) Binary-level APK acceptance.
APK="app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
ZIPALIGN="$ANDROID_HOME/build-tools/35.0.0/zipalign"
"$ZIPALIGN" -c -v 4 "$APK" | tee "$OUT/diagnostics/zipalign.log"
"$APKSIGNER" verify --verbose --print-certs "$APK" | tee "$OUT/diagnostics/apksigner.log"
"$AAPT" dump badging "$APK" | tee "$OUT/diagnostics/badging.txt"
"$AAPT" dump permissions "$APK" | tee "$OUT/diagnostics/permissions.txt"
grep -F "package: name='com.tiagocrispo.furnitureshot' versionCode='100' versionName='1.0.0'" "$OUT/diagnostics/badging.txt"
grep -F 'android.permission.CAMERA' "$OUT/diagnostics/permissions.txt"
if grep -E 'android.permission.(INTERNET|ACCESS_NETWORK_STATE)' "$OUT/diagnostics/permissions.txt"; then
  echo 'Network permission unexpectedly present in final merged APK' >&2
  exit 1
fi
unzip -t "$APK" | tee "$OUT/diagnostics/apk-integrity.txt"
cp "$APK" "$OUT/apk/ProductShot-v1.0.0-FINAL.apk"
sha256sum "$OUT/apk/ProductShot-v1.0.0-FINAL.apk" > "$OUT/apk/ProductShot-v1.0.0-FINAL.sha256"

# 7) Clean recovery source, no build/cache junk.
zip -qr "$OUT/source/ProductShot-v1.0.0-FINAL-SOURCE.zip" . -x '.gradle/*' '.kotlin/*' 'app/build/*' 'build/*'
unzip -t "$OUT/source/ProductShot-v1.0.0-FINAL-SOURCE.zip" > "$OUT/source/source-integrity.txt"
sha256sum "$OUT/source/ProductShot-v1.0.0-FINAL-SOURCE.zip" > "$OUT/source/ProductShot-v1.0.0-FINAL-SOURCE.sha256"

cat > "$OUT/apk/ProductShot-v1.0.0-FINAL-VALIDATION.txt" <<'EOF'
ProductShot v1.0.0 FINAL
versionCode: 100
package: com.tiagocrispo.furnitureshot

Passed gates:
- verified base-source checksum and archive integrity
- replayed every accepted implementation block
- verified deterministic v1 freeze applier hash
- removed legacy model-photo/reference architecture
- removed legacy collage composer
- source static validation passed
- debug runtime dependency snapshot generated
- testDebugUnitTest task passed
- Android lintDebug passed
- assembleDebug passed
- no Kotlin compiler warnings in final build
- APK zipalign verification passed
- APK signature verification passed
- APK package/version verification passed
- CAMERA permission present
- INTERNET and ACCESS_NETWORK_STATE absent from merged APK
- APK ZIP integrity passed
- clean recovery-source ZIP integrity passed
EOF

echo 'PRODUCTSHOT_V1_FINAL_CI_OK'
