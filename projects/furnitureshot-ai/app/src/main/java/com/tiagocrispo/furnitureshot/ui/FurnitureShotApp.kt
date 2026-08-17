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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.tiagocrispo.furnitureshot.data.ImageStore
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
    val scrollState = rememberScrollState()

    var originalPath by rememberSaveable { mutableStateOf<String?>(null) }
    var resultPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var processingPercent by remember { mutableStateOf(0) }
    var processingStage by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var permissionRevision by remember { mutableStateOf(0) }
    var viewerPath by rememberSaveable { mutableStateOf<String?>(null) }

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missingPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.filterNot(::hasPermission).toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRevision++
    }

    LaunchedEffect(Unit) {
        val missing = missingPermissions()
        if (!prefs.getBoolean("permission_gate_shown", false) && missing.isNotEmpty()) {
            prefs.edit().putBoolean("permission_gate_shown", true).apply()
            permissionLauncher.launch(missing)
        }
    }

    LaunchedEffect(resultPath) {
        if (resultPath != null) scrollState.animateScrollTo(scrollState.maxValue)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val file = withContext(Dispatchers.IO) { ImageStore.importUri(context, uri) }
                    originalPath = file.absolutePath
                    resultPath = null
                    processingPercent = 0
                    processingStage = null
                    message = null
                }.onFailure { message = "No se pudo abrir la foto." }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val path = pendingCameraPath
        pendingCameraPath = null
        if (success && path != null) {
            scope.launch {
                runCatching {
                    val file = withContext(Dispatchers.IO) { ImageStore.importCameraFile(context, File(path)) }
                    originalPath = file.absolutePath
                    resultPath = null
                    processingPercent = 0
                    processingStage = null
                    message = null
                }.onFailure { message = "No se pudo abrir la foto." }
            }
        }
    }

    val cameraGranted = remember(permissionRevision) { hasPermission(Manifest.permission.CAMERA) }
    val legacyStorageGranted = remember(permissionRevision) {
        Build.VERSION.SDK_INT > Build.VERSION_CODES.P || hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun savePhoto(path: String) {
        if (!legacyStorageGranted && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        } else {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { ImageStore.exportToGallery(context, path) } }
                    .onFailure { message = "No se pudo guardar la foto." }
            }
        }
    }

    fun sharePhoto(path: String) {
        runCatching {
            val uri = ImageStore.shareUriForResult(context, path)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Compartir foto"))
        }.onFailure { message = "No se pudo compartir la foto." }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFFAF8F5)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "FurnitureShot",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3B2A20),
                )

                Card {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Galería") }
                        OutlinedButton(
                            onClick = {
                                if (!cameraGranted) {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                                } else {
                                    val (uri, file) = ImageStore.createCameraUri(context)
                                    pendingCameraPath = file.absolutePath
                                    cameraLauncher.launch(uri)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Cámara") }
                    }
                }

                originalPath?.let { path ->
                    ImageCard(title = "Foto original", path = path, onOpen = { viewerPath = it })

                    Button(
                        onClick = {
                            if (isProcessing) {
                                job?.cancel()
                                job = null
                            } else {
                                job = scope.launch {
                                    isProcessing = true
                                    processingPercent = 0
                                    processingStage = "Preparando la foto"
                                    message = null
                                    resultPath = null
                                    try {
                                        val result = LocalEnhancementEngine.process(
                                            context = context,
                                            originalPath = path,
                                            settings = PromptPolicy.automaticSettings(),
                                            onProgress = { percent, stage ->
                                                processingPercent = percent
                                                processingStage = stage
                                            },
                                        )
                                        resultPath = result.resultPath
                                        processingPercent = 100
                                        processingStage = "Foto lista"
                                        message = result.warning
                                        withContext(Dispatchers.IO) {
                                            ImageStore.appendHistory(context, path, result.resultPath)
                                        }
                                    } catch (_: kotlinx.coroutines.CancellationException) {
                                        processingPercent = 0
                                        processingStage = null
                                        message = null
                                    } catch (_: OutOfMemoryError) {
                                        processingPercent = 0
                                        processingStage = null
                                        message = "La foto es demasiado grande para procesarla en este dispositivo."
                                    } catch (_: Throwable) {
                                        processingPercent = 0
                                        processingStage = null
                                        message = "No se pudo procesar la foto. Intenta nuevamente."
                                    } finally {
                                        isProcessing = false
                                        job = null
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Cancelar")
                        } else {
                            Text("Procesar")
                        }
                    }

                    if (isProcessing) {
                        LinearProgressIndicator(
                            progress = { (processingPercent.coerceIn(0, 100) / 100f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "${processingPercent.coerceIn(0, 100)}% · ${processingStage ?: "Procesando"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6C625C),
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }

                resultPath?.let { path ->
                    ImageCard(title = "Resultado", path = path, onOpen = { viewerPath = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { savePhoto(path) }, modifier = Modifier.weight(1f)) { Text("Guardar") }
                        OutlinedButton(onClick = { sharePhoto(path) }, modifier = Modifier.weight(1f)) { Text("Compartir") }
                    }
                }

                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6C625C),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            viewerPath?.let { path ->
                FullscreenImageDialog(
                    path = path,
                    onClose = { viewerPath = null },
                    onSave = { savePhoto(path) },
                    onShare = { sharePhoto(path) },
                )
            }
        }
    }
}

@Composable
private fun ImageCard(title: String, path: String, onOpen: (String) -> Unit) {
    Card {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            FileImage(path = path, maxDimension = 1280, onOpen = onOpen)
        }
    }
}

@Composable
private fun FileImage(path: String, maxDimension: Int, onOpen: (String) -> Unit) {
    var bitmap by remember(path, maxDimension) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(path, maxDimension) { mutableStateOf(true) }

    LaunchedEffect(path, maxDimension) {
        bitmap = null
        loading = true
        bitmap = runCatching {
            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, maxDimension = maxDimension) }
        }.getOrNull()
        loading = false
    }

    DisposableEffect(path, maxDimension) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 480.dp)
            .background(Color(0xFFF1EFEC), RoundedCornerShape(16.dp))
            .clickable { onOpen(path) },
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator()
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = if (path.endsWith("result.jpg")) "Resultado" else "Foto original",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
            else -> Text("No se pudo mostrar la imagen")
        }
    }
}

@Composable
private fun FullscreenImageDialog(
    path: String,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xCC000000)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Descargar") }
                    OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Compartir") }
                    TextButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Cerrar") }
                }
                FileImage(path = path, maxDimension = 2200, onOpen = {})
            }
        }
    }
}
