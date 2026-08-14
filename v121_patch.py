from pathlib import Path
root = Path("build-src/A53Performance")

# Version
p = root / "app/build.gradle.kts"
s = p.read_text()
assert 'versionCode = 3' in s and 'versionName = "1.2.0"' in s
s = s.replace('versionCode = 3', 'versionCode = 4').replace('versionName = "1.2.0"', 'versionName = "1.2.1"')
p.write_text(s)

# Package visibility for Shizuku
p = root / "app/src/main/AndroidManifest.xml"
s = p.read_text()
needle = '<package android:name="com.whatsapp" />'
assert needle in s
if 'moe.shizuku.privileged.api' not in s:
    s = s.replace(needle, needle + '\n        <package android:name="moe.shizuku.privileged.api" />')
p.write_text(s)

# Make the Shizuku action useful when the service is stopped/not installed
p = root / "app/src/main/java/com/fer/a53performance/shizuku/ShizukuController.kt"
s = p.read_text()
if 'import android.content.Intent' not in s:
    s = s.replace('import android.content.ServiceConnection\n', 'import android.content.ServiceConnection\nimport android.content.Intent\nimport android.net.Uri\n')
old = '''        if (!alive) {
            refresh("Iniciá Shizuku primero")
            return
        }'''
new = '''        if (!alive) {
            openShizukuOrInstall()
            return
        }'''
assert old in s
s = s.replace(old, new)
method = '''
    private fun openShizukuOrInstall() {
        val packageName = "moe.shizuku.privileged.api"
        val launch = runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }
                .onSuccess { refresh("Shizuku está detenido · abrilo, iniciá el servicio y volvé a A53 Performance") }
                .onFailure { refresh("No pude abrir Shizuku: ${it.message}") }
            return
        }

        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(market); true }.getOrElse {
            runCatching { context.startActivity(web); true }.getOrDefault(false)
        }
        refresh(if (opened) "Instalá Shizuku, iniciá su servicio y después volvé a A53 Performance" else "Shizuku no está instalado")
    }
'''
anchor = '\n    private fun hasPermission(): Boolean'
assert anchor in s
s = s.replace(anchor, method + anchor)
p.write_text(s)

# State-aware button labels
old_label = 'Text("Conectar / autorizar Shizuku")'
new_label = 'Text(if (!state.shizuku.binderAlive) "Abrir Shizuku" else if (!state.shizuku.permissionGranted) "Autorizar A53 Performance" else "Conectar servicio Shizuku")'
for rel in [
    "app/src/main/java/com/fer/a53performance/ui/screens/ModesScreen.kt",
    "app/src/main/java/com/fer/a53performance/ui/screens/CleanerScreen.kt",
]:
    p = root / rel
    s = p.read_text()
    assert old_label in s
    p.write_text(s.replace(old_label, new_label))

# final checks
assert 'versionName = "1.2.1"' in (root/'app/build.gradle.kts').read_text()
assert 'Abrir Shizuku' in (root/'app/src/main/java/com/fer/a53performance/ui/screens/ModesScreen.kt').read_text()
assert 'openShizukuOrInstall()' in (root/'app/src/main/java/com/fer/a53performance/shizuku/ShizukuController.kt').read_text()
print("v1.2.1 Shizuku onboarding patch applied")
