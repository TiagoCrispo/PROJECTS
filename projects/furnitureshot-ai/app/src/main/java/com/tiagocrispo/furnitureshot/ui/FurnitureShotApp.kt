package com.tiagocrispo.furnitureshot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tiagocrispo.furnitureshot.data.ImageStore
import com.tiagocrispo.furnitureshot.model.CatalogPreset
import com.tiagocrispo.furnitureshot.processing.LocalEnhancementEngine
import com.tiagocrispo.furnitureshot.processing.PromptPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FurnitureShotApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("startup", 0) }
    var originalPath by rememberSaveable { mutableStateOf<String?>(null) }
    var resultPath by rememberSaveable { mutableStateOf<String?>(null) }
    var prompt by rememberSaveable { mutableStateOf(PromptPolicy.defaultPrompt) }
    var presetName by rememberSaveable { mutableStateOf(CatalogPreset.CATALOG.name) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Listo") }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var permissionRevision by remember { mutableStateOf(0) }

    fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun missingPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.filterNot(::hasPermission).toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRevision++ }
    LaunchedEffect(Unit) {
        val missing = missingPermissions()
        if (!prefs.getBoolean("permission_gate_shown", false) && missing.isNotEmpty()) {
            prefs.edit().putBoolean("permission_gate_shown", true).apply()
            permissionLauncher.launch(missing)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                status = "Importando foto…"
                val file = withContext(Dispatchers.IO) { ImageStore.importUri(context, uri) }
                originalPath = file.absolutePath
                resultPath = null
                status = "Foto lista"
                message = null
            }.onFailure { message = "No se pudo importar la foto: ${it.message.orEmpty()}" }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = pendingCameraPath
        pendingCameraPath = null
        if (success && path != null) scope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) { ImageStore.importCameraFile(context, File(path)) }
                originalPath = file.absolutePath
                resultPath = null
                status = "Foto lista"
                message = null
            }.onFailure { message = "No se pudo conservar la foto de cámara: ${it.message.orEmpty()}" }
        }
    }

    val cameraGranted = remember(permissionRevision) { hasPermission(Manifest.permission.CAMERA) }
    val legacyStorageGranted = remember(permissionRevision) {
        Build.VERSION.SDK_INT > Build.VERSION_CODES.P || hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFFAF8F5)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("FurnitureShot AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF3B2A20))
                Text("Foto → catálogo profesional → guardar o compartir", color = Color(0xFF6C625C))

                Card(colors = CardDefaults.cardColors(containerColor = if (cameraGranted && legacyStorageGranted) Color(0xFFEAF4EA) else Color(0xFFFFF2DF))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Permisos", fontWeight = FontWeight.SemiBold)
                        Text("La app solicita al abrir solo Cámara y, en Android 9 o anterior, escritura para exportar. El selector de Galería no necesita acceso total a tus fotos.", style = MaterialTheme.typography.bodySmall)
                        if (!cameraGranted || !legacyStorageGranted) TextButton(onClick = { val m = missingPermissions(); if (m.isNotEmpty()) permissionLauncher.launch(m) }) { Text("Conceder permisos") }
                    }
                }

                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("1 · Elegir foto", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Text("Galería") }
                            OutlinedButton(onClick = {
                                if (!cameraGranted) permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                                else {
                                    val (uri, file) = ImageStore.createCameraUri(context)
                                    pendingCameraPath = file.absolutePath
                                    cameraLauncher.launch(uri)
                                }
                            }, modifier = Modifier.weight(1f)) { Text("Cámara") }
                        }
                    }
                }

                originalPath?.let { path ->
                    ImageCard("2 · Foto original", path)

                    Card {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("3 · Preset", fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CatalogPreset.entries.forEach { preset ->
                                    FilterChip(selected = preset.name == presetName, onClick = { presetName = preset.name }, label = { Text(preset.title) })
                                }
                            }
                        }
                    }

                    Card {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("4 · Instrucción", fontWeight = FontWeight.SemiBold)
                            Text("Fidelity Lock prioriza que el mueble siga siendo exactamente el mismo.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6C625C))
                            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 10, label = { Text("Prompt") })
                        }
                    }

                    Card {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("5 · Procesar", fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isProcessing) {
                                    CircularProgressIndicator(modifier = Modifier.width(22.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp))
                                }
                                Text(status, style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = {
                                if (isProcessing) {
                                    job?.cancel(); job = null
                                } else {
                                    job = scope.launch {
                                        isProcessing = true
                                        message = null
                                        status = "Segmentando mueble con IA…"
                                        runCatching {
                                            val settings = PromptPolicy.interpret(prompt, CatalogPreset.valueOf(presetName))
                                            val result = LocalEnhancementEngine.process(context, path, settings)
                                            resultPath = result.resultPath
                                            status = if (result.backgroundReplaced) "Resultado listo · fondo blanco de estudio" else "Resultado listo"
                                            message = result.warning
                                            withContext(Dispatchers.IO) { ImageStore.appendHistory(context, path, result.resultPath) }
                                        }.onFailure {
                                            status = if (it is kotlinx.coroutines.CancellationException) "Procesamiento cancelado" else "Error"
                                            if (it !is kotlinx.coroutines.CancellationException) message = "No se pudo procesar. La original no fue modificada. ${it.message.orEmpty()}"
                                        }
                                        isProcessing = false
                                    }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text(if (isProcessing) "Cancelar" else "Procesar") }
                        }
                    }
                }

                resultPath?.let { path ->
                    ImageCard("6 · Resultado procesado", path)
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F0EA))) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Guardar / Compartir", fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = {
                                    if (!legacyStorageGranted && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                                    else scope.launch {
                                        runCatching {
                                            status = "Guardando…"
                                            val uri = withContext(Dispatchers.IO) { ImageStore.exportToGallery(context, path) }
                                            status = if (uri != null) "Guardado en Pictures/FurnitureShot AI" else "No se pudo guardar"
                                        }.onFailure { message = it.message }
                                    }
                                }, modifier = Modifier.weight(1f)) { Text("Guardar") }
                                OutlinedButton(onClick = {
                                    runCatching {
                                        val uri = ImageStore.shareUriForResult(context, path)
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "image/jpeg"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Compartir resultado"))
                                    }.onFailure { message = it.message }
                                }, modifier = Modifier.weight(1f)) { Text("Compartir") }
                            }
                        }
                    }
                }

                message?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF5E9))) {
                        Text(it, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text("v0.2.0-alpha04 · ML Kit subject segmentation · fondo blanco estudio · sin gris claro", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6C625C), modifier = Modifier.padding(bottom = 20.dp))
            }
        }
    }
}

@Composable
private fun ImageCard(title: String, path: String) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            FileImage(path)
        }
    }
}

@Composable
private fun FileImage(path: String) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(path) { mutableStateOf(true) }
    LaunchedEffect(path) {
        bitmap = null
        loading = true
        bitmap = runCatching { withContext(Dispatchers.IO) { ImageStore.loadPreview(path) } }.getOrNull()
        loading = false
    }
    DisposableEffect(path) { onDispose { bitmap?.recycle(); bitmap = null } }
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 480.dp).background(Color(0xFFF1EFEC), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator()
            bitmap != null -> Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = titleFor(path), modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            else -> Text("No se pudo mostrar la imagen")
        }
    }
}

private fun titleFor(path: String): String = if (path.endsWith("result.jpg")) "Resultado procesado" else "Foto original"
