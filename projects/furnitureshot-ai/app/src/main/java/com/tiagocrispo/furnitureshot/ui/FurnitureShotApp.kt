package com.tiagocrispo.furnitureshot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.tiagocrispo.furnitureshot.processing.FinishPassEngine
import com.tiagocrispo.furnitureshot.processing.LocalEnhancementEngine
import com.tiagocrispo.furnitureshot.processing.PromptPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FurnitureShotApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("startup", 0) }
    val scroll = rememberScrollState()

    var originalPath by rememberSaveable { mutableStateOf<String?>(null) }
    var resultPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerPath by rememberSaveable { mutableStateOf<String?>(null) }
    var processing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var stage by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var permissionRevision by remember { mutableStateOf(0) }

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missingPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.filterNot(::granted).toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionRevision++ }

    LaunchedEffect(Unit) {
        val missing = missingPermissions()
        if (!prefs.getBoolean("permission_gate_shown", false) && missing.isNotEmpty()) {
            prefs.edit().putBoolean("permission_gate_shown", true).apply()
            permissionLauncher.launch(missing)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val file = withContext(Dispatchers.IO) { ImageStore.importUri(context, uri) }
                    originalPath = file.absolutePath
                    resultPath = null
                    progress = 0
                    stage = null
                    message = null
                }.onFailure { message = "No se pudo abrir la foto." }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val path = pendingCameraPath
        pendingCameraPath = null
        if (ok && path != null) {
            scope.launch {
                runCatching {
                    val file = withContext(Dispatchers.IO) { ImageStore.importCameraFile(context, File(path)) }
                    originalPath = file.absolutePath
                    resultPath = null
                    progress = 0
                    stage = null
                    message = null
                }.onFailure { message = "No se pudo abrir la foto." }
            }
        }
    }

    val cameraGranted = remember(permissionRevision) { granted(Manifest.permission.CAMERA) }
    val storageGranted = remember(permissionRevision) {
        Build.VERSION.SDK_INT > Build.VERSION_CODES.P || granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun save(path: String) {
        if (!storageGranted && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            return
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ImageStore.exportToGallery(context, path) } }
                .onSuccess { message = "Listo. Guardado en la galería: Imágenes/Pictures > FurnitureShot AI" }
                .onFailure { message = "No se pudo guardar la foto." }
        }
    }

    fun share(path: String) {
        runCatching {
            val uri = ImageStore.shareUriForResult(context, path)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Compartir foto"))
        }.onFailure { message = "No se pudo compartir la foto." }
    }

    fun processSinglePhoto() {
        val source = originalPath ?: return
        job = scope.launch {
            processing = true
            progress = 0
            stage = "Preparando"
            message = null
            resultPath = null
            try {
                val ticker = launch {
                    var local = 0
                    while (isActive && local < 84) {
                        delay(420)
                        local = (local + 3).coerceAtMost(84)
                        progress = local
                        stage = when {
                            local < 20 -> "Preparando"
                            local < 48 -> "Recortando"
                            local < 72 -> "Mejorando luz y detalle"
                            else -> "Acabado final"
                        }
                    }
                }
                val result = try {
                    LocalEnhancementEngine.process(
                        context = context,
                        originalPath = source,
                        settings = PromptPolicy.automaticSettings(),
                    )
                } finally {
                    ticker.cancel()
                }
                stage = "Acabado final"
                val finished = withContext(Dispatchers.IO) { FinishPassEngine.apply(result.resultPath) }
                withContext(Dispatchers.IO) { ImageStore.appendHistory(context, source, finished) }
                resultPath = finished
                progress = 100
                stage = "Listo"
                message = result.warning
            } catch (_: CancellationException) {
                progress = 0
                stage = null
            } catch (_: OutOfMemoryError) {
                progress = 0
                stage = null
                message = "La foto es demasiado grande para procesarla en este dispositivo."
            } catch (_: Throwable) {
                progress = 0
                stage = null
                message = "No se pudo procesar la foto. Intenta nuevamente."
            } finally {
                processing = false
                job = null
            }
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFFAF8F5)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .verticalScroll(scroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "FurnitureShot",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3B2A20),
                )

                Card {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { galleryLauncher.launch("image/*") },
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
                }

                if (originalPath != null) {
                    Button(
                        onClick = {
                            if (processing) job?.cancel() else processSinglePhoto()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (processing) {
                            CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Cancelar")
                        } else {
                            Text("Procesar")
                        }
                    }
                }

                if (processing) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${progress.coerceIn(0, 100)}% · ${stage ?: "Procesando"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6C625C),
                    )
                }

                originalPath?.let { path ->
                    ImageCard("Foto", path) { viewerPath = it }
                }

                resultPath?.let { path ->
                    ImageCard("Resultado", path) { viewerPath = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { save(path) }, modifier = Modifier.weight(1f)) { Text("Descargar") }
                        OutlinedButton(onClick = { share(path) }, modifier = Modifier.weight(1f)) { Text("Compartir") }
                    }
                }

                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6C625C))
                }
            }

            viewerPath?.let { path ->
                FullscreenImageDialog(
                    path = path,
                    onClose = { viewerPath = null },
                    onSave = { save(path) },
                    onShare = { share(path) },
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
            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, maxDimension) }
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
                contentDescription = null,
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
