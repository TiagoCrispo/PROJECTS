from pathlib import Path
root = Path('build-src/A53Performance')

# Version bump
p = root / 'app/build.gradle.kts'
s = p.read_text()
assert 'versionCode = 5' in s and 'versionName = "1.2.2"' in s
s = s.replace('versionCode = 5', 'versionCode = 6').replace('versionName = "1.2.2"', 'versionName = "1.2.3"')
p.write_text(s)

# Add first-run permission assistant to the app shell.
p = root / 'app/src/main/java/com/fer/a53performance/MainActivity.kt'
s = p.read_text()
needle = '''            composable("settings") { SettingsScreen(state, vm::setAdvancedMode) }
        }
    }
}
'''
replacement = '''            composable("settings") { SettingsScreen(state, vm::setAdvancedMode) }
        }
    }

    PermissionOnboarding(
        state = state,
        onRequestShizuku = vm::requestShizuku
    )
}
'''
assert needle in s
s = s.replace(needle, replacement)
p.write_text(s)

# Make version display future-proof.
p = root / 'app/src/main/java/com/fer/a53performance/ui/screens/SettingsScreen.kt'
s = p.read_text()
if 'import com.fer.a53performance.BuildConfig' not in s:
    s = s.replace('import com.fer.a53performance.model.AppUiState\n', 'import com.fer.a53performance.BuildConfig\nimport com.fer.a53performance.model.AppUiState\n')
s = s.replace('Info("Versión app", "1.2.0")', 'Info("Versión app", BuildConfig.VERSION_NAME)')
p.write_text(s)

onboarding = r'''package com.fer.a53performance

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fer.a53performance.model.AppUiState

data class RequiredAccess(
    val id: String,
    val label: String,
    val granted: Boolean
)

@Composable
fun PermissionOnboarding(
    state: AppUiState,
    onRequestShizuku: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var dismissedThisSession by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(setOf<String>()) }
    var currentStep by remember { mutableStateOf<String?>(null) }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshTick++
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshTick++
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val accesses = requiredAccesses(context, state, refreshTick)
    val missing = accesses.filterNot { it.granted }

    LaunchedEffect(running, refreshTick, attempted, state.shizuku.binderAlive, state.shizuku.permissionGranted) {
        if (!running) return@LaunchedEffect

        val nowMissing = requiredAccesses(context, state, refreshTick).filterNot { it.granted }
        val next = nowMissing.firstOrNull { it.id !in attempted }
        if (next == null) {
            currentStep = null
            running = false
            return@LaunchedEffect
        }

        attempted = attempted + next.id
        currentStep = next.label

        when (next.id) {
            "media" -> {
                val permissions = if (Build.VERSION.SDK_INT >= 33) {
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                } else {
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                runtimeLauncher.launch(permissions)
            }

            "all_files" -> {
                val appUri = Uri.parse("package:${context.packageName}")
                val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, appUri)
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                runCatching { settingsLauncher.launch(direct) }
                    .onFailure { runCatching { settingsLauncher.launch(fallback) }.onFailure { refreshTick++ } }
            }

            "usage" -> {
                runCatching { settingsLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    .onFailure { refreshTick++ }
            }

            "write_settings" -> {
                val intent = Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
                runCatching { settingsLauncher.launch(intent) }.onFailure { refreshTick++ }
            }

            "dnd" -> {
                runCatching { settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                    .onFailure { refreshTick++ }
            }

            "shizuku" -> onRequestShizuku()
        }
    }

    if (missing.isEmpty() || dismissedThisSession) return

    AlertDialog(
        onDismissRequest = {
            if (!running) dismissedThisSession = true
        },
        icon = { Icon(Icons.Rounded.AdminPanelSettings, null) },
        title = { Text("Permisos de A53 Performance") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Un solo asistente para configurar todo. Android obliga a abrir algunas pantallas por separado; A53 Performance las recorrerá automáticamente una detrás de otra.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                accesses.forEach { item ->
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (item.granted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            null
                        )
                        Text(if (item.granted) "${item.label} · concedido" else item.label)
                    }
                }
                currentStep?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("Configurando: $it", style = MaterialTheme.typography.labelLarge)
                }
                if (!running && attempted.isNotEmpty() && missing.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Quedaron ${missing.size} permisos pendientes. Podés reintentar solo los que faltan.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    attempted = emptySet()
                    currentStep = null
                    running = true
                    refreshTick++
                },
                enabled = !running
            ) {
                Text(if (attempted.isEmpty()) "Configurar todo" else "Reintentar faltantes")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { dismissedThisSession = true },
                enabled = !running
            ) { Text("Ahora no") }
        }
    )
}

private fun requiredAccesses(context: Context, state: AppUiState, refreshTick: Int): List<RequiredAccess> {
    @Suppress("UNUSED_VARIABLE") val tick = refreshTick
    val mediaGranted = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    val allFilesGranted = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
    val usageGranted = hasUsageAccess(context)
    val writeGranted = Settings.System.canWrite(context)
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val dndGranted = notificationManager?.isNotificationPolicyAccessGranted == true
    val shizukuGranted = state.shizuku.binderAlive && state.shizuku.permissionGranted

    return listOf(
        RequiredAccess("media", "Fotos y videos", mediaGranted),
        RequiredAccess("all_files", "Administrar todos los archivos", allFilesGranted),
        RequiredAccess("usage", "Estadísticas de uso de apps", usageGranted),
        RequiredAccess("write_settings", "Modificar ajustes del sistema", writeGranted),
        RequiredAccess("dnd", "Acceso a No molestar", dndGranted),
        RequiredAccess("shizuku", "Shizuku / funciones avanzadas", shizukuGranted)
    )
}

private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        context.applicationInfo.uid,
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}
'''

p = root / 'app/src/main/java/com/fer/a53performance/PermissionOnboarding.kt'
p.write_text(onboarding)

assert 'versionName = "1.2.3"' in (root/'app/build.gradle.kts').read_text()
assert 'PermissionOnboarding(' in (root/'app/src/main/java/com/fer/a53performance/MainActivity.kt').read_text()
assert 'Configurar todo' in p.read_text()
print('v1.2.3 permission onboarding patch applied')
