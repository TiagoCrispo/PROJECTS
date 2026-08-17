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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.tiagocrispo.furnitureshot.model.SellerShotItem
import com.tiagocrispo.furnitureshot.processing.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

@Composable
fun FurnitureShotApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("startup", 0) }
    val scroll = rememberScrollState()

    var shots by remember { mutableStateOf(listOf<SellerShotItem>()) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerPath by rememberSaveable { mutableStateOf<String?>(null) }
    var heroPath by rememberSaveable { mutableStateOf<String?>(null) }
    var detailPaths by remember { mutableStateOf(listOf<String>()) }
    var detailMode by rememberSaveable { mutableStateOf(true) }
    var processing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var stage by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var permissionRevision by remember { mutableStateOf(0) }

    fun granted(permission: String) =
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
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            runCatching {
                val files = uris.take(5).map { uri -> withContext(Dispatchers.IO) { ImageStore.importUri(context, uri) } }
                shots = files.map { SellerShotItem(UUID.randomUUID().toString(), it.absolutePath) }
                heroPath = null
                detailPaths = emptyList()
                progress = 0
                stage = null
                message = if (files.size > 1) "Se cargaron ${files.size} fotos del mismo mueble." else null
            }.onFailure { message = "No se pudieron abrir las fotos." }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingCameraPath
        pendingCameraPath = null
        if (ok && path != null) scope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) { ImageStore.importCameraFile(context, File(path)) }
                shots = listOf(SellerShotItem(UUID.randomUUID().toString(), file.absolutePath))
                heroPath = null
                detailPaths = emptyList()
                message = null
            }.onFailure { message = "No se pudo abrir la foto." }
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

    fun processPack() {
        if (shots.isEmpty()) return
        job = scope.launch {
            processing = true
            progress = 0
            stage = "Preparando lote"
            message = null
            heroPath = null
            detailPaths = emptyList()
            try {
                val total = shots.size
                var items = shots.map { it.copy(resultPath = null, viewLabel = null, sortRank = 999) }
                var warning: String? = null

                for ((index, item) in items.withIndex()) {
                    val number = index + 1
                    stage = "Foto $number/$total · Recortando y mejorando"
                    val ticker = launch {
                        var local = 0
                        while (isActive && local < 82) {
                            delay(420)
                            local = (local + 2).coerceAtMost(82)
                            progress = (((index + local / 100f) / total) * 90f).toInt().coerceIn(0, 90)
                            stage = when {
                                local < 24 -> "Foto $number/$total · Preparando"
                                local < 56 -> "Foto $number/$total · Recortando"
                                else -> "Foto $number/$total · Mejorando luz y detalle"
                            }
                        }
                    }
                    val result = try {
                        LocalEnhancementEngine.process(
                            context = context,
                            originalPath = item.originalPath,
                            settings = PromptPolicy.automaticSettings(),
                        )
                    } finally {
                        ticker.cancel()
                    }
                    stage = "Foto $number/$total · Acabado final"
                    val finished = withContext(Dispatchers.IO) { FinishPassEngine.apply(result.resultPath) }
                    val classification = withContext(Dispatchers.IO) { PhotoPackAnalyzer.classify(finished) }
                    warning = warning ?: result.warning
                    items = items.map {
                        if (it.id == item.id) it.copy(
                            resultPath = finished,
                            viewLabel = classification?.label,
                            sortRank = classification?.rank ?: 999,
                        ) else it
                    }
                    shots = items.sortedWith(compareBy<SellerShotItem> { it.sortRank }.thenBy { it.id })
                    items = shots
                    withContext(Dispatchers.IO) { ImageStore.appendHistory(context, item.originalPath, finished) }
                }

                if (total > 1) {
                    progress = 96
                    stage = "Ajustando consistencia del pack"
                    withContext(Dispatchers.IO) { PackConsistencyEngine.normalize(items.mapNotNull { it.resultPath }) }
                    items = items.map { item ->
                        val path = item.resultPath
                        if (path == null) item else {
                            val c = withContext(Dispatchers.IO) { PhotoPackAnalyzer.classify(path) }
                            item.copy(viewLabel = c?.label, sortRank = c?.rank ?: item.sortRank)
                        }
                    }
                }

                val paths = items.mapNotNull { it.resultPath }
                heroPath = withContext(Dispatchers.IO) { PhotoPackAnalyzer.pickRecommendedCover(paths) }
                detailPaths = withContext(Dispatchers.IO) {
                    PhotoPackAnalyzer.pickRecommendedDetails(paths, heroPath, limit = 2)
                }
                shots = items.sortedWith(compareBy<SellerShotItem> {
                    if (it.resultPath == heroPath) 0 else it.sortRank
                }.thenBy { it.id })

                progress = 100
                stage = "Pack listo"
                message = warning ?: when {
                    total > 1 && heroPath != null -> "Listo. Se eligió una hero y los mejores detalles del pack."
                    total > 1 -> "Listo. Se generó el pack."
                    else -> null
                }
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
                message = "No se pudieron procesar las fotos. Intenta nuevamente."
            } finally {
                processing = false
                job = null
            }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFFAF8F5)) {
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .verticalScroll(scroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("FurnitureShot", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF3B2A20))

                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("Galería x1-x5") }
                            OutlinedButton(onClick = {
                                if (!cameraGranted) permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                                else {
                                    val (uri, file) = ImageStore.createCameraUri(context)
                                    pendingCameraPath = file.absolutePath
                                    cameraLauncher.launch(uri)
                                }
                            }, modifier = Modifier.weight(1f)) { Text("Cámara") }
                        }
                        Text("Sube 1 a 5 fotos del mismo mueble para mostrar distintas caras al comprador.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6C625C))
                    }
                }

                Card {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Modo detalle", fontWeight = FontWeight.SemiBold)
                            Text("Muestra también vetas, repisas y terminaciones.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6C625C))
                        }
                        OutlinedButton(onClick = { detailMode = !detailMode }) { Text(if (detailMode) "Activo" else "Apagado") }
                    }
                }

                if (shots.isNotEmpty()) {
                    Button(onClick = {
                        if (processing) job?.cancel() else processPack()
                    }, modifier = Modifier.fillMaxWidth()) {
                        if (processing) {
                            CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Cancelar")
                        } else Text(if (shots.size > 1) "Procesar pack" else "Procesar")
                    }
                }

                if (processing) {
                    LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${progress.coerceIn(0, 100)}% · ${stage ?: "Procesando"}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6C625C))
                }

                shots.filter { detailMode || it.viewLabel != "Detalle" }.forEachIndexed { index, item ->
                    ImageCard("Foto ${index + 1}", item.originalPath) { viewerPath = it }
                    item.resultPath?.let { path ->
                        ImageCard("Resultado ${index + 1}", path) { viewerPath = it }
                        val tags = buildList {
                            item.viewLabel?.let { add(it) }
                            if (path == heroPath) add("Hero recomendada")
                            if (path in detailPaths) add("Detalle recomendado")
                        }.joinToString(" · ")
                        if (tags.isNotBlank()) Text(tags, style = MaterialTheme.typography.bodySmall, color = Color(0xFF7A4A12))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { save(path) }, modifier = Modifier.weight(1f)) { Text("Guardar") }
                            OutlinedButton(onClick = { share(path) }, modifier = Modifier.weight(1f)) { Text("Compartir") }
                        }
                    }
                }

                heroPath?.let { path -> OutlinedButton(onClick = { save(path) }, modifier = Modifier.fillMaxWidth()) { Text("Guardar hero") } }
                if (detailPaths.isNotEmpty()) OutlinedButton(onClick = { detailPaths.forEach { save(it) } }, modifier = Modifier.fillMaxWidth()) { Text("Guardar detalles recomendados") }
                if (shots.count { it.resultPath != null } > 1) OutlinedButton(onClick = { shots.mapNotNull { it.resultPath }.forEach { save(it) } }, modifier = Modifier.fillMaxWidth()) { Text("Guardar pack") }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6C625C)) }
            }

            viewerPath?.let { path ->
                FullscreenImageDialog(path, { viewerPath = null }, { save(path) }, { share(path) })
            }
        }
    }
}

@Composable
private fun ImageCard(title: String, path: String, onOpen: (String) -> Unit) {
    Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        FileImage(path, 1280, onOpen)
    } }
}

@Composable
private fun FileImage(path: String, maxDimension: Int, onOpen: (String) -> Unit) {
    var bitmap by remember(path, maxDimension) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(path, maxDimension) { mutableStateOf(true) }
    LaunchedEffect(path, maxDimension) {
        bitmap = null
        loading = true
        bitmap = runCatching { withContext(Dispatchers.IO) { ImageStore.loadPreview(path, maxDimension) } }.getOrNull()
        loading = false
    }
    DisposableEffect(path, maxDimension) { onDispose { bitmap?.recycle(); bitmap = null } }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 480.dp)
            .background(Color(0xFFF1EFEC), RoundedCornerShape(16.dp)).clickable { onOpen(path) },
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator()
            bitmap != null -> Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            else -> Text("No se pudo mostrar la imagen")
        }
    }
}

@Composable
private fun FullscreenImageDialog(path: String, onClose: () -> Unit, onSave: () -> Unit, onShare: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xCC000000)) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Descargar") }
                    OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Compartir") }
                    TextButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Cerrar") }
                }
                FileImage(path, 2200) {}
            }
        }
    }
}
