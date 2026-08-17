package com.tiagocrispo.furnitureshot.ui

import android.Manifest
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
import com.tiagocrispo.furnitureshot.model.HistoryItem
import com.tiagocrispo.furnitureshot.model.PreviewMode
import com.tiagocrispo.furnitureshot.processing.LocalEnhancementEngine
import com.tiagocrispo.furnitureshot.processing.PromptPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun FurnitureShotApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences("startup", 0) }

    var originalPath by rememberSaveable { mutableStateOf<String?>(null) }
    var resultPath by rememberSaveable { mutableStateOf<String?>(null) }
    var previewMode by rememberSaveable { mutableStateOf(PreviewMode.ORIGINAL.name) }
    var prompt by rememberSaveable { mutableStateOf(PromptPolicy.defaultPrompt) }
    var presetName by rememberSaveable { mutableStateOf(CatalogPreset.CATALOG.name) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Listo") }
    var message by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(ImageStore.loadHistory(context)) }
    var processingJob by remember { mutableStateOf<Job?>(null) }
    var permissionRevision by remember { mutableStateOf(0) }

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun requiredRuntimePermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.filterNot(::hasPermission).toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRevision++
    }

    LaunchedEffect(Unit) {
        val missing = requiredRuntimePermissions()
        if (!preferences.getBoolean("permission_gate_shown", false) && missing.isNotEmpty()) {
            preferences.edit().putBoolean("permission_gate_shown", true).apply()
            permissionLauncher.launch(missing)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    status = "Importando foto…"
                    val imported = withContext(Dispatchers.IO) { ImageStore.importUri(context, uri) }
                    originalPath = imported.absolutePath
                    resultPath = null
                    previewMode = PreviewMode.ORIGINAL.name
                    status = "Foto lista"
                    message = null
                }.onFailure {
                    status = "Error"
                    message = "No se pudo importar la foto: ${it.message ?: "error desconocido"}"
                }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val capturedPath = pendingCameraPath
        pendingCameraPath = null
        if (success && capturedPath != null) {
            scope.launch {
                runCatching {
                    status = "Guardando captura…"
                    val imported = withContext(Dispatchers.IO) {
                        ImageStore.importCameraFile(context, File(capturedPath))
                    }
                    originalPath = imported.absolutePath
                    resultPath = null
                    previewMode = PreviewMode.ORIGINAL.name
                    status = "Foto lista"
                    message = null
                }.onFailure {
                    status = "Error"
                    message = "No se pudo conservar la foto de cámara: ${it.message ?: "error desconocido"}"
                }
            }
        }
    }

    val cameraGranted = remember(permissionRevision) { hasPermission(Manifest.permission.CAMERA) }
    val legacyStorageGranted = remember(permissionRevision) {
        Build.VERSION.SDK_INT > Build.VERSION_CODES.P || hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFFAF8F5),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Header()

                PermissionCard(
                    cameraGranted = cameraGranted,
                    legacyStorageGranted = legacyStorageGranted,
                    onRequest = {
                        val missing = requiredRuntimePermissions()
                        if (missing.isNotEmpty()) permissionLauncher.launch(missing)
                    },
                )

                ImportCard(
                    cameraGranted = cameraGranted,
                    onGallery = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onCamera = {
                        if (!cameraGranted) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                        } else {
                            val (uri, file) = ImageStore.createCameraUri(context)
                            pendingCameraPath = file.absolutePath
                            cameraLauncher.launch(uri)
                        }
                    },
                )

                if (originalPath != null) {
                    EditorCard(
                        originalPath = originalPath,
                        resultPath = resultPath,
                        previewMode = PreviewMode.valueOf(previewMode),
                        onPreviewMode = { previewMode = it.name },
                    )

                    PresetCard(
                        selected = CatalogPreset.valueOf(presetName),
                        onSelected = { presetName = it.name },
                    )

                    PromptCard(
                        prompt = prompt,
                        onPromptChanged = { prompt = it },
                    )

                    ProcessingCard(
                        isProcessing = isProcessing,
                        status = status,
                        canExport = resultPath != null && legacyStorageGranted,
                        onProcess = {
                            val path = originalPath
                            if (path != null) {
                                processingJob?.cancel()
                                processingJob = scope.launch {
                                    isProcessing = true
                                    message = null
                                    status = "Protegiendo fidelidad…"
                                    runCatching {
                                        val settings = PromptPolicy.interpret(
                                            prompt = prompt,
                                            preset = CatalogPreset.valueOf(presetName),
                                        )
                                        status = "Mejorando foto…"
                                        val result = LocalEnhancementEngine.process(
                                            context = context,
                                            originalPath = path,
                                            settings = settings,
                                        )
                                        resultPath = result.resultPath
                                        previewMode = PreviewMode.RESULT.name
                                        status = if (result.backgroundReplaced) {
                                            "Resultado listo · fondo aislado"
                                        } else {
                                            "Resultado listo"
                                        }
                                        message = result.warning
                                        withContext(Dispatchers.IO) {
                                            ImageStore.appendHistory(context, path, result.resultPath)
                                        }
                                        history = ImageStore.loadHistory(context)
                                    }.onFailure {
                                        if (it is kotlinx.coroutines.CancellationException) {
                                            status = "Procesamiento cancelado"
                                        } else {
                                            status = "Error"
                                            message = "No se pudo procesar esta imagen. La original no fue modificada. ${it.message.orEmpty()}"
                                        }
                                    }
                                    isProcessing = false
                                }
                            }
                        },
                        onCancel = {
                            processingJob?.cancel()
                            processingJob = null
                        },
                        onExport = {
                            val path = resultPath
                            if (path != null) {
                                if (!legacyStorageGranted && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                                } else {
                                    scope.launch {
                                        runCatching {
                                            status = "Guardando en Galería…"
                                            val uri = withContext(Dispatchers.IO) {
                                                ImageStore.exportToGallery(context, path)
                                            }
                                            status = if (uri != null) "Guardado en Pictures/FurnitureShot AI" else "No se pudo exportar"
                                        }.onFailure {
                                            status = "Error al exportar"
                                            message = it.message
                                        }
                                    }
                                }
                            }
                        },
                    )
                }

                message?.let { MessageCard(it) }

                if (history.isNotEmpty()) {
                    HistoryCard(
                        items = history,
                        onOpen = {
                            originalPath = it.originalPath
                            resultPath = it.resultPath
                            previewMode = PreviewMode.RESULT.name
                            status = "Historial abierto"
                            message = null
                        },
                    )
                }

                Text(
                    text = "v0.1.0-alpha01 · procesamiento local · Vistas IA y modelos ML pesados: pendientes",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6C625C),
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "FurnitureShot AI",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF3B2A20),
        )
        Text(
            text = "Foto → catálogo profesional → guardar",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF6C625C),
        )
    }
}

@Composable
private fun PermissionCard(
    cameraGranted: Boolean,
    legacyStorageGranted: Boolean,
    onRequest: () -> Unit,
) {
    val allGranted = cameraGranted && legacyStorageGranted
    Card(colors = CardDefaults.cardColors(containerColor = if (allGranted) Color(0xFFEAF4EA) else Color(0xFFFFF2DF))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Permisos", fontWeight = FontWeight.SemiBold)
            Text(
                if (allGranted) {
                    "Todos los permisos de tiempo de ejecución necesarios están concedidos. Galería usa el selector seguro de Android y no necesita acceso total a tus fotos."
                } else {
                    "Falta algún permiso necesario. La app lo solicita al primer inicio y puedes volver a concederlo aquí."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (!allGranted) {
                TextButton(onClick = onRequest) { Text("Conceder permisos") }
            }
        }
    }
}

@Composable
private fun ImportCard(
    cameraGranted: Boolean,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("1 · Elegir foto", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onGallery, modifier = Modifier.weight(1f)) { Text("Galería") }
                OutlinedButton(onClick = onCamera, modifier = Modifier.weight(1f)) {
                    Text(if (cameraGranted) "Cámara" else "Dar cámara")
                }
            }
        }
    }
}

@Composable
private fun EditorCard(
    originalPath: String?,
    resultPath: String?,
    previewMode: PreviewMode,
    onPreviewMode: (PreviewMode) -> Unit,
) {
    val displayPath = if (previewMode == PreviewMode.RESULT && resultPath != null) resultPath else originalPath
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("2 · Vista previa", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = previewMode == PreviewMode.ORIGINAL,
                    onClick = { onPreviewMode(PreviewMode.ORIGINAL) },
                    label = { Text("Original") },
                )
                FilterChip(
                    selected = previewMode == PreviewMode.RESULT,
                    enabled = resultPath != null,
                    onClick = { onPreviewMode(PreviewMode.RESULT) },
                    label = { Text("Resultado") },
                )
            }
            FileImage(path = displayPath)
        }
    }
}

@Composable
private fun PresetCard(selected: CatalogPreset, onSelected: (CatalogPreset) -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("3 · Preset", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CatalogPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = selected == preset,
                        onClick = { onSelected(preset) },
                        label = { Text(preset.title) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptCard(prompt: String, onPromptChanged: (String) -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("4 · Instrucción de edición", fontWeight = FontWeight.SemiBold)
            Text(
                "Fidelity Lock bloquea instrucciones que intenten cambiar la estructura real del producto.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6C625C),
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChanged,
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 10,
                label = { Text("Prompt") },
            )
        }
    }
}

@Composable
private fun ProcessingCard(
    isProcessing: Boolean,
    status: String,
    canExport: Boolean,
    onProcess: () -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = if (isProcessing) onCancel else onProcess,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isProcessing) "Cancelar" else "Procesar")
                }
                OutlinedButton(
                    onClick = onExport,
                    enabled = canExport && !isProcessing,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Guardar")
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF5E9))) {
        Text(
            text = message,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HistoryCard(items: List<HistoryItem>, onOpen: (HistoryItem) -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Historial local", fontWeight = FontWeight.SemiBold)
            items.take(5).forEach { item ->
                TextButton(onClick = { onOpen(item) }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Abrir resultado", fontWeight = FontWeight.Medium)
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.createdAt)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileImage(path: String?) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(path) { mutableStateOf(path != null) }

    LaunchedEffect(path) {
        bitmap = null
        if (path != null) {
            loading = true
            bitmap = runCatching {
                withContext(Dispatchers.IO) { ImageStore.loadPreview(path) }
            }.getOrNull()
            loading = false
        }
    }

    DisposableEffect(path) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 460.dp)
            .background(Color(0xFFF1EFEC), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator()
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Vista previa del mueble",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
            else -> Text("No se pudo mostrar la vista previa")
        }
    }
}
