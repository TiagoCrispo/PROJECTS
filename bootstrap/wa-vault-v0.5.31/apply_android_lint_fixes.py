#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()

downloads = root / "app/src/main/java/com/fer/wavault/DownloadsExporter.java"
s = downloads.read_text(encoding="utf-8")
old = "            created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);"
new = "            created = insertScopedDownload(resolver, values);"
if old not in s and new not in s:
    raise SystemExit("DownloadsExporter expected insertion point not found")
if old in s:
    s = s.replace(old, new, 1)
helper = '''\n    @android.annotation.TargetApi(Build.VERSION_CODES.Q)\n    private static Uri insertScopedDownload(ContentResolver resolver, ContentValues values) {\n        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);\n    }\n'''
anchor = "\n    private static File uniqueFile(File dir,String name)"
if "private static Uri insertScopedDownload" not in s:
    if anchor not in s:
        raise SystemExit("DownloadsExporter helper anchor not found")
    s = s.replace(anchor, helper + anchor, 1)
downloads.write_text(s, encoding="utf-8")

main = root / "app/src/main/java/com/fer/wavault/MainActivity.java"
s = main.read_text(encoding="utf-8")
old = "            else registerReceiver(dataChangedReceiver,f,VaultUiNotifier.INTERNAL_PERMISSION,null);"
new = "            else registerUiReceiverPre33(f);"
if old not in s and new not in s:
    raise SystemExit("MainActivity pre-33 receiver registration point not found")
if old in s:
    s = s.replace(old, new, 1)
helper = '''\n    // API 26-32 has no platform receiver-flags overload. This receiver is still protected\n    // by WA Vault's signature permission; the suppression is deliberately scoped here.\n    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")\n    private void registerUiReceiverPre33(IntentFilter filter) {\n        registerReceiver(dataChangedReceiver, filter, VaultUiNotifier.INTERNAL_PERMISSION, null);\n    }\n'''
anchor = "\n    private void scheduleVisibleDataRefresh(String kind)"
if "private void registerUiReceiverPre33" not in s:
    if anchor not in s:
        raise SystemExit("MainActivity receiver helper anchor not found")
    s = s.replace(anchor, helper + anchor, 1)
main.write_text(s, encoding="utf-8")

security_test = root / "tools/v0530_block6_security_android16_regression_test.py"
s = security_test.read_text(encoding="utf-8")
old = "need('registerReceiver(dataChangedReceiver,f,VaultUiNotifier.INTERNAL_PERMISSION,null)' in main, 'API26-32 internal receiver not permission-protected')"
new = "need('registerUiReceiverPre33(f)' in main and 'registerReceiver(dataChangedReceiver, filter, VaultUiNotifier.INTERNAL_PERMISSION, null)' in main and '@android.annotation.SuppressLint(\"UnspecifiedRegisterReceiverFlag\")' in main, 'API26-32 internal receiver helper not permission-protected')"
if old not in s and new not in s:
    raise SystemExit("Android16 regression assertion point not found")
if old in s:
    s = s.replace(old, new, 1)
security_test.write_text(s, encoding="utf-8")

# Fail closed if either original lint trigger remains.
assert "resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI" in downloads.read_text(encoding="utf-8")
assert "created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI" not in downloads.read_text(encoding="utf-8")
assert "else registerReceiver(dataChangedReceiver,f,VaultUiNotifier.INTERNAL_PERMISSION,null);" not in main.read_text(encoding="utf-8")
assert "registerUiReceiverPre33(f)" in main.read_text(encoding="utf-8")
assert "API26-32 internal receiver helper not permission-protected" in security_test.read_text(encoding="utf-8")
print("ANDROID_LINT_FIXES_APPLIED")
