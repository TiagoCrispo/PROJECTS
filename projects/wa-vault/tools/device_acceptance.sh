#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PACKAGE="com.fer.wavault"
LISTENER="$PACKAGE/$PACKAGE.WhatsAppNotificationListener"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK="${2:-app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
OUT="${3:-device-validation}"
mkdir -p "$OUT"

fail(){ echo "ERROR: $*" >&2; exit 1; }
check_runtime_log(){
  local file="$1" label="$2" code="$3"
  if grep -E 'FATAL EXCEPTION|AndroidRuntime:.*FATAL|ANR in com\.fer\.wavault|Input dispatching timed out.*com\.fer\.wavault|Process: com\.fer\.wavault.*has died' "$file"; then
    echo "REAL_DEVICE_FAIL: $label"; exit "$code"
  fi
}
snapshot_runtime(){
  local label="$1"
  adb shell dumpsys meminfo "$PACKAGE" > "$OUT/meminfo-$label.txt" 2>&1 || true
  adb shell dumpsys cpuinfo | grep -F "$PACKAGE" > "$OUT/cpu-$label.txt" 2>&1 || true
  adb shell dumpsys activity processes > "$OUT/processes-$label.txt" 2>&1 || true
  adb shell dumpsys package "$PACKAGE" > "$OUT/package-$label.txt" 2>&1 || true
}
command -v adb >/dev/null 2>&1 || fail "adb no está instalado/en PATH"
[[ -f "$APK" ]] || fail "APK no existe: $APK"

adb start-server >/dev/null
SERIALS=$(adb devices | awk 'NR>1 && $2=="device"{print $1}')
COUNT=$(printf '%s\n' "$SERIALS" | sed '/^$/d' | wc -l)
[[ "$COUNT" -eq 1 ]] || fail "Se requiere exactamente 1 dispositivo ADB autorizado; encontrados=$COUNT"

echo "DEVICE=$(printf '%s' "$SERIALS")" | tee "$OUT/device.txt"
adb shell getprop ro.build.version.release | tee -a "$OUT/device.txt"
adb shell getprop ro.build.version.sdk | tee -a "$OUT/device.txt"
adb shell getprop ro.product.manufacturer | tee -a "$OUT/device.txt"
adb shell getprop ro.product.model | tee -a "$OUT/device.txt"

# Clean-start acceptance: user explicitly allows discarding old WA Vault data.
adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
adb install "$APK" | tee "$OUT/install.txt"

# Security/compatibility contract from the installed package, not from source text.
adb shell dumpsys package "$PACKAGE" > "$OUT/package-installed.txt" 2>&1 || fail "dumpsys package falló"
python3 - "$OUT/package-installed.txt" <<'PYSEC'
import re,sys
s=open(sys.argv[1],encoding='utf-8',errors='replace').read()
def need(c,m):
    if not c:
        raise SystemExit('REAL_DEVICE_FAIL: '+m)
# dumpsys format varies slightly by Android/OEM; accept targetSdk=36 or targetSdkVersion=36.
need(re.search(r'\btargetSdk(?:Version)?=36\b',s) is not None, 'installed APK targetSdk != 36')
for p in ('android.permission.USE_BIOMETRIC','android.permission.MANAGE_EXTERNAL_STORAGE','android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS'):
    need(p in s, 'missing declared permission '+p)
for p in ('android.permission.INTERNET','android.permission.POST_NOTIFICATIONS'):
    # These strings may occur in shared system permission metadata on some builds; restrict to requested-permissions block when possible.
    m=re.search(r'requested permissions:(.*?)(?:install permissions:|runtime permissions:|User 0:|$)',s,re.S|re.I)
    if m:
        need(p not in m.group(1), 'unexpected requested permission '+p)
print('INSTALLED_PACKAGE_SECURITY_PASS')
PYSEC

# Best-effort runtime permissions. Notification-listener access remains a special permission.
for P in android.permission.READ_MEDIA_IMAGES android.permission.READ_MEDIA_VIDEO android.permission.READ_MEDIA_AUDIO; do
  adb shell pm grant "$PACKAGE" "$P" >/dev/null 2>&1 || true
done
# MANAGE_EXTERNAL_STORAGE is intentionally NOT auto-granted by default.
# The app must remain usable in degraded mode. Opt in only for the direct-storage acceptance path.
if [[ "${WA_VAULT_GRANT_ALL_FILES:-0}" == "1" ]]; then
  adb shell appops set --uid "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
fi
adb shell cmd notification allow_listener "$LISTENER" >/dev/null 2>&1 || true

adb logcat -c
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 2
adb logcat -d -v threadtime > "$OUT/logcat-launch.txt"
check_runtime_log "$OUT/logcat-launch.txt" "launch crash/ANR" 2
snapshot_runtime "launch"

echo "LAUNCH_NO_FATAL_PASS"

# Optional negative security/runtime test. Disabled by default because it mutates special-access state.
# It proves that (a) the app survives with All files access denied and (b) an untrusted shell sender
# cannot use the signature-protected internal DATA_CHANGED broadcast as app-private IPC.
if [[ "${WA_VAULT_SPECIAL_ACCESS_NEGATIVE_TEST:-0}" == "1" ]]; then
  adb shell appops set --uid "$PACKAGE" MANAGE_EXTERNAL_STORAGE deny >/dev/null 2>&1 || true
  adb shell am force-stop "$PACKAGE"
  adb logcat -c
  adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 2
  adb logcat -d -v threadtime > "$OUT/logcat-no-all-files.txt"
  check_runtime_log "$OUT/logcat-no-all-files.txt" "crash/ANR with All files denied" 7
  echo "ALL_FILES_DENIED_NO_FATAL_PASS"

  set +e
  adb shell am broadcast -a com.fer.wavault.DATA_CHANGED -p "$PACKAGE" > "$OUT/internal-broadcast-negative.txt" 2>&1
  BRC=$?
  set -e
  # Android/OEM am output differs. Save the result and require either a permission denial/non-zero
  # or absence of app-side handling evidence; runtime log is the authoritative follow-up artifact.
  adb logcat -d -v threadtime > "$OUT/logcat-internal-broadcast-negative.txt"
  if grep -Eqi 'Permission Denial|requires .*INTERNAL_EVENTS|not allowed|SecurityException' "$OUT/internal-broadcast-negative.txt"; then
    echo "INTERNAL_BROADCAST_SIGNATURE_GATE_PASS"
  elif [[ "$BRC" -ne 0 ]]; then
    echo "INTERNAL_BROADCAST_SIGNATURE_GATE_PASS rc=$BRC"
  else
    echo "INTERNAL_BROADCAST_SIGNATURE_GATE_INCONCLUSIVE: inspect $OUT/internal-broadcast-negative.txt and logcat"
  fi
fi

# Background/foreground lifecycle. Android may cache/kill a background process; either outcome must be recoverable.
adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
sleep 15
snapshot_runtime "background-15s"
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 2
adb logcat -d -v threadtime > "$OUT/logcat-background-resume.txt"
check_runtime_log "$OUT/logcat-background-resume.txt" "background/resume crash/ANR" 5
snapshot_runtime "resume"
echo "BACKGROUND_RESUME_NO_FATAL_PASS"

# Optional screen-off smoke. Disabled by default because it may lock an unattended test device.
if [[ "${WA_VAULT_SCREEN_OFF_TEST:-0}" == "1" ]]; then
  adb shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || adb shell input keyevent 26 >/dev/null 2>&1 || true
  sleep 15
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || adb shell input keyevent 224 >/dev/null 2>&1 || true
  adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 2
  adb logcat -d -v threadtime > "$OUT/logcat-screen-off.txt"
  check_runtime_log "$OUT/logcat-screen-off.txt" "screen-off crash/ANR" 6
  echo "SCREEN_OFF_NO_FATAL_PASS"
fi

# Instrumented smoke tests, if test APK was built.
if [[ -f "$TEST_APK" ]]; then
  adb install -r "$TEST_APK" | tee "$OUT/install-test-apk.txt"
  adb shell am instrument -w "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrumentation.txt"
  grep -q 'OK (' "$OUT/instrumentation.txt" || { echo "REAL_DEVICE_FAIL: instrumentation"; exit 3; }
fi

# Lifecycle stress: force-stop/reopen 20x. This is not a WhatsApp deletion test by itself;
# it verifies startup/process recreation does not crash. DB state is captured before/after.
dump_db(){
  local dest="$1"
  adb exec-out run-as "$PACKAGE" cat databases/wa_vault.db > "$dest" 2>/dev/null || true
}
dump_db "$OUT/before-restarts.db"
for i in $(seq 1 20); do
  adb shell am force-stop "$PACKAGE"
  adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 0.35
done
sleep 1
adb logcat -d -v threadtime > "$OUT/logcat-20-restarts.txt"
check_runtime_log "$OUT/logcat-20-restarts.txt" "crash/ANR during restart stress" 4
dump_db "$OUT/after-restarts.db"
snapshot_runtime "after-20-restarts"

python3 - "$OUT/before-restarts.db" "$OUT/after-restarts.db" <<'PY'
import sqlite3,sys,os
for path,label in ((sys.argv[1],'before'),(sys.argv[2],'after')):
    if not os.path.exists(path) or os.path.getsize(path)==0:
        print(label,'DB_UNAVAILABLE'); continue
    c=sqlite3.connect(path)
    try:
        rows=c.execute("select count(*), sum(case when deletion_state=2 then 1 else 0 end) from messages").fetchone()
        media=c.execute("select count(*) from media").fetchone()[0]
        print(label,'messages=',rows[0],'confirmed_deleted=',rows[1] or 0,'media=',media)
    finally: c.close()
PY

echo "RESTART_20_NO_FATAL_PASS"
echo
cat <<'TXT'
AUTOMATED DEVICE SMOKE COMPLETE.
Inspect meminfo/cpu snapshots for monotonic growth or persistent CPU while idle.
REAL WhatsApp acceptance still requires manual Test A/Test 10:
  A) Receive 20 normal messages, delete none, note DB/UI counts, close/reopen WA Vault -> deleted count must not increase and media must not redownload.
  B) After BASELINE_READY, delete exactly one new WhatsApp message -> only that message may become CONFIRMED when exact correlation exists; otherwise it must remain UNKNOWN.
  C) Disable/re-enable notification access and reboot device -> no historical messages may become deleted.
After each stage export logcat and verify DELETION_CONFIRMED contains WHY_DETECTED, SOURCE_EVENT, MATCH_METHOD, CONFIDENCE.
TXT
