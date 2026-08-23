#!/usr/bin/env python3
from pathlib import Path
import sys
root = Path(sys.argv[1]).resolve()

def read(rel): return (root/rel).read_text(encoding='utf-8')
def write(rel,s): (root/rel).write_text(s,encoding='utf-8')
def rep(rel,old,new):
    s=read(rel)
    if old not in s: raise SystemExit(f'missing fragment in {rel}: {old[:80]!r}')
    write(rel,s.replace(old,new,1))

# identity
rep('app/build.gradle.kts','versionCode = 57','versionCode = 58')
rep('app/build.gradle.kts','versionName = "0.9.11-local-ai-quality"','versionName = "0.9.12-block1-one-photo-ux"')

# no camera in upload-only UX
m=read('app/src/main/AndroidManifest.xml')
m=m.replace('    <uses-permission android:name="android.permission.CAMERA" />\n','')
m=m.replace('''    <uses-feature\n        android:name="android.hardware.camera.any"\n        android:required="false" />\n\n''','')
write('app/src/main/AndroidManifest.xml',m)

rel='app/src/main/java/com/tiagocrispo/furnitureshot/ui/FurnitureShotApp.kt'
s=read(rel)
s=s.replace('import java.io.File\n','')
s=s.replace('    val prefs = remember { context.getSharedPreferences("startup", 0) }\n','')
s=s.replace('    var referencePath by rememberSaveable { mutableStateOf<String?>(null) }\n','')
s=s.replace('    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }\n','')
s=s.replace('    var showModelHelp by rememberSaveable { mutableStateOf(false) }\n','    var showModelHelp by rememberSaveable { mutableStateOf(false) }\n    var showMotorSettings by rememberSaveable { mutableStateOf(false) }\n')

# remove startup camera permission gate
start=s.index('    fun missingPermissions(): Array<String> = buildList {')
end=s.index('    val galleryLauncher = rememberLauncherForActivityResult(',start)
s=s[:start]+'''    val permissionLauncher = rememberLauncherForActivityResult(\n        ActivityResultContracts.RequestMultiplePermissions(),\n    ) { permissionRevision++ }\n\n'''+s[end:]

# remove quality-reference picker
start=s.index('    val referenceLauncher = rememberLauncherForActivityResult(')
end=s.index('    val modelZipLauncher = rememberLauncherForActivityResult(',start)
s=s[:start]+s[end:]

# remove camera capture launcher and cameraGranted
start=s.index('    val cameraLauncher = rememberLauncherForActivityResult(')
end=s.index('    val storageGranted = remember(permissionRevision)',start)
s=s[:start]+s[end:]

s=s.replace('settings = PromptPolicy.automaticSettings(referencePath),','settings = PromptPolicy.automaticSettings(),')

# replace main public flow from ProductShot title through pre-progress section
start=s.index('                Text(\n                    text = "ProductShot",')
end=s.index('                if (processing) {',start)
public='''                Row(\n                    modifier = Modifier.fillMaxWidth(),\n                    horizontalArrangement = Arrangement.SpaceBetween,\n                    verticalAlignment = Alignment.CenterVertically,\n                ) {\n                    Text(\n                        text = "ProductShot",\n                        style = MaterialTheme.typography.headlineMedium,\n                        fontWeight = FontWeight.Bold,\n                        color = Color(0xFF3B2A20),\n                    )\n                    TextButton(onClick = { showMotorSettings = true }) {\n                        Text("Motor local")\n                    }\n                }\n\n                Text(\n                    text = "Sube una foto del producto. ProductShot se encarga del resto.",\n                    style = MaterialTheme.typography.bodyMedium,\n                    color = Color(0xFF6C625C),\n                )\n\n                Card {\n                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {\n                        Button(\n                            onClick = { galleryLauncher.launch("image/*") },\n                            enabled = !processing,\n                            modifier = Modifier.fillMaxWidth(),\n                        ) {\n                            Text(if (originalPath == null) "Subir foto" else "Cambiar foto")\n                        }\n                    }\n                }\n\n                originalPath?.let { path ->\n                    ImageCard("Foto del producto", path) { viewerPath = it }\n                }\n\n                if (originalPath != null) {\n                    Button(\n                        onClick = {\n                            if (processing) job?.cancel() else processSinglePhoto()\n                        },\n                        modifier = Modifier.fillMaxWidth(),\n                    ) {\n                        if (processing) {\n                            CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)\n                            Spacer(Modifier.width(10.dp))\n                            Text("Cancelar")\n                        } else {\n                            Text("Generar")\n                        }\n                    }\n                }\n\n'''
s=s[:start]+public+s[end:]

# remove duplicate old input/reference/result section after progress, replace result only
start=s.index('                originalPath?.let { path ->', s.index('                if (processing) {'))
end=s.index('                message?.let {',start)
result='''                resultPath?.let { path ->\n                    ImageCard("Foto modelada", path) { viewerPath = it }\n                    Button(\n                        onClick = { save(path) },\n                        modifier = Modifier.fillMaxWidth(),\n                    ) {\n                        Text("Descargar foto modelada")\n                    }\n                }\n\n'''
s=s[:start]+result+s[end:]

# motor controls become secondary dialog, inserted before model-help dialog
anchor='''            if (showModelHelp) {\n                ModelInstallHelpDialog('''
if anchor not in s: raise SystemExit('model help anchor missing')
motor='''            if (showMotorSettings) {\n                Dialog(onDismissRequest = { showMotorSettings = false }) {\n                    Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {\n                        Column(\n                            modifier = Modifier\n                                .heightIn(max = 720.dp)\n                                .verticalScroll(rememberScrollState())\n                                .padding(20.dp),\n                            verticalArrangement = Arrangement.spacedBy(10.dp),\n                        ) {\n                            Row(\n                                modifier = Modifier.fillMaxWidth(),\n                                horizontalArrangement = Arrangement.SpaceBetween,\n                                verticalAlignment = Alignment.CenterVertically,\n                            ) {\n                                Text(\n                                    text = "Motor local",\n                                    style = MaterialTheme.typography.titleMedium,\n                                    fontWeight = FontWeight.Bold,\n                                    color = Color(0xFF3B2A20),\n                                )\n                                TextButton(onClick = { showMotorSettings = false }) { Text("Cerrar") }\n                            }\n                            Text(\n                                text = localAiDiagnostics.publicSummary(),\n                                style = MaterialTheme.typography.bodySmall,\n                                color = Color(0xFF6C625C),\n                            )\n                            if (!localAiDiagnostics.modelStatus.usable) {\n                                Text(\n                                    text = if (localAiDiagnostics.modelStatus.installed) {\n                                        "La instalación actual no quedó usable. Puedes volver a seleccionar el ZIP correcto del motor local."\n                                    } else {\n                                        "Todavía no hay un motor local instalado. Selecciona el ZIP compatible para habilitar la generación local."\n                                    },\n                                    style = MaterialTheme.typography.bodySmall,\n                                    color = Color(0xFF6C625C),\n                                )\n                                Button(\n                                    enabled = !installingModel,\n                                    onClick = { modelZipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },\n                                    modifier = Modifier.fillMaxWidth(),\n                                ) {\n                                    if (installingModel) {\n                                        CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)\n                                        Spacer(Modifier.width(8.dp))\n                                        Text("Instalando…")\n                                    } else {\n                                        Text("Instalar desde ZIP")\n                                    }\n                                }\n                                OutlinedButton(\n                                    enabled = !installingModel,\n                                    onClick = { showMotorSettings = false; showModelHelp = true },\n                                    modifier = Modifier.fillMaxWidth(),\n                                ) { Text("Guía del motor") }\n                                if (localAiDiagnostics.modelStatus.installed) {\n                                    TextButton(onClick = {\n                                        val removed = LocalModelManager.uninstall(context)\n                                        localAiRevision++\n                                        message = if (removed) "Se eliminó la instalación anterior del motor local." else "No se pudo eliminar la instalación anterior."\n                                    }) { Text("Eliminar instalación") }\n                                }\n                            } else {\n                                Button(\n                                    enabled = !testingLocalAi && !processing,\n                                    onClick = {\n                                        scope.launch {\n                                            testingLocalAi = true\n                                            message = "Ejecutando autoprueba del motor local…"\n                                            val test = LocalAiSelfTest.run(context)\n                                            testingLocalAi = false\n                                            localAiRevision++\n                                            message = test.message\n                                        }\n                                    },\n                                    modifier = Modifier.fillMaxWidth(),\n                                ) {\n                                    if (testingLocalAi) {\n                                        CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)\n                                        Spacer(Modifier.width(8.dp))\n                                        Text("Probando…")\n                                    } else {\n                                        Text("Probar motor local")\n                                    }\n                                }\n                                OutlinedButton(\n                                    onClick = { showMotorSettings = false; showModelHelp = true },\n                                    modifier = Modifier.fillMaxWidth(),\n                                ) { Text("Guía del motor") }\n                            }\n                        }\n                    }\n                }\n            }\n\n'''
s=s.replace(anchor,motor+anchor,1)
write(rel,s)

# rewrite validation tail for Block 12 while preserving Block 11 engine guarantees
valrel='scripts/static_validate.py'
v=read(valrel)
start=v.index("req('versionCode = 57'")
v=v[:start]+'''req('versionCode = 58' in build, 'versionCode 58 missing')
req('versionName = "0.9.12-block1-one-photo-ux"' in build, 'versionName mismatch')
req('compileSdk = 36' in build and 'targetSdk = 36' in build, 'SDK 36 contract missing')
req('android.permission.INTERNET' not in manifest, 'INTERNET permission must remain absent')
req('android.permission.ACCESS_NETWORK_STATE' not in manifest, 'ACCESS_NETWORK_STATE must remain absent')
req('android.permission.CAMERA' not in manifest, 'camera permission must be absent')
ET.parse(root / 'app/src/main/AndroidManifest.xml')
req('DigestInputStream' in manager and 'archiveSha256' in manager, 'secure model installer digest missing')
req('ImageGenerator.createFromOptions' in selftest, 'model initialization self-test missing')
req('generator.generate(' in selftest and 'BitmapExtractor.extract' in selftest, 'real inference probe missing')
req('saveJpeg(originalPath, output)' in engine, 'single hero output missing')
req('CatalogSheetComposer.compose(' not in engine, 'repeated catalog sheet still wired as main result')
req('MAX_ATTEMPTS = 3' in generator, 'bounded retry policy missing')
req('buildPromptVariants' in generator and 'neutralizeStudioPlate' in generator, 'MediaPipe quality path missing')
req('LocalAiReadinessStore' in readiness, 'readiness persistence missing')
req('Text(if (originalPath == null) "Subir foto" else "Cambiar foto")' in ui, 'single upload action missing')
req('Text("Generar")' in ui, 'Generate action missing')
req('Text("Descargar foto modelada")' in ui, 'modeled-photo download action missing')
req('ImageCard("Foto modelada"' in ui, 'modeled result card missing')
req('PromptPolicy.automaticSettings()' in ui, 'generation still depends on reference')
req('referencePath' not in ui and 'referenceLauncher' not in ui, 'reference flow still present')
req('TakePicture()' not in ui and 'Cámara' not in ui, 'camera flow still present')
req('showMotorSettings' in ui and 'Probar motor local' in ui and 'Instalar desde ZIP' in ui, 'secondary motor dialog missing')
print('PRODUCTSHOT_BLOCK12_UI_STATIC_VALIDATION_OK')
'''
write(valrel,v)
print('PRODUCTSHOT_BLOCK12_UI_APPLIED')
