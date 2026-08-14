from pathlib import Path
root = Path('build-src/A53Performance')

# Version
p = root / 'app/build.gradle.kts'
s = p.read_text()
assert 'versionCode = 4' in s and 'versionName = "1.2.1"' in s
s = s.replace('versionCode = 4', 'versionCode = 5').replace('versionName = "1.2.1"', 'versionName = "1.2.2"')
p.write_text(s)

# Improve Shizuku permission flow when service is alive but dialog does not appear / permission is blocked.
p = root / 'app/src/main/java/com/fer/a53performance/shizuku/ShizukuController.kt'
s = p.read_text()
if 'import android.widget.Toast' not in s:
    s = s.replace('import android.os.IBinder\n', 'import android.os.IBinder\nimport android.widget.Toast\n')

old = '''        if (hasPermission()) bind()
        else runCatching { Shizuku.requestPermission(3001) }
            .onFailure { refresh("No se pudo solicitar permiso: ${it.message}") }
'''
new = '''        if (hasPermission()) {
            bind()
            return
        }

        val blocked = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        if (blocked) {
            Toast.makeText(
                context,
                "Shizuku → Aplicaciones autorizadas → activá A53 Performance",
                Toast.LENGTH_LONG
            ).show()
            refresh("Permiso bloqueado · abrí Shizuku → Aplicaciones autorizadas → A53 Performance")
            openShizukuOrInstall()
            return
        }

        runCatching { Shizuku.requestPermission(3001) }
            .onSuccess {
                refresh("Solicitud enviada · si no aparece el permiso, abrí Shizuku → Aplicaciones autorizadas")
            }
            .onFailure {
                Toast.makeText(
                    context,
                    "Abrí Shizuku → Aplicaciones autorizadas → activá A53 Performance",
                    Toast.LENGTH_LONG
                ).show()
                refresh("No se pudo abrir el permiso · autorizá A53 Performance manualmente en Shizuku")
                openShizukuOrInstall()
            }
'''
assert old in s
s = s.replace(old, new)

s = s.replace(
    '"Shizuku está activo · falta autorizar A53 Performance"',
    '"Shizuku activo · autorizá A53 Performance (si no sale popup: Shizuku → Aplicaciones autorizadas)"'
)

p.write_text(s)

assert 'versionName = "1.2.2"' in (root/'app/build.gradle.kts').read_text()
sc = (root/'app/src/main/java/com/fer/a53performance/shizuku/ShizukuController.kt').read_text()
assert 'Shizuku.shouldShowRequestPermissionRationale()' in sc
assert 'Aplicaciones autorizadas' in sc
print('v1.2.2 Shizuku permission fallback patch applied')
