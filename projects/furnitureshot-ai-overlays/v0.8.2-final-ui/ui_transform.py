from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()

def add_after(anchor: str, addition: str):
    global text
    if addition.strip() in text:
        return
    if anchor not in text:
        raise SystemExit(f'anchor not found: {anchor!r}')
    text = text.replace(anchor, anchor + addition, 1)

add_after('import androidx.compose.foundation.Image\n', 'import androidx.compose.foundation.gestures.detectTapGestures\nimport androidx.compose.foundation.gestures.detectTransformGestures\n')
add_after('import androidx.compose.ui.Alignment\n', 'import androidx.compose.ui.geometry.Offset\n')
add_after('import androidx.compose.ui.graphics.asImageBitmap\n', 'import androidx.compose.ui.graphics.graphicsLayer\n')
add_after('import androidx.compose.ui.layout.ContentScale\n', 'import androidx.compose.ui.layout.onSizeChanged\n')
add_after('import androidx.compose.ui.platform.LocalContext\n', 'import androidx.compose.ui.input.pointer.pointerInput\n')
add_after('import androidx.compose.ui.unit.dp\n', 'import androidx.compose.ui.unit.IntSize\n')

old = '''                resultPath = finished\n                progress = 100\n'''
new = '''                resultPath = finished\n                viewerPath = finished\n                progress = 100\n'''
if old not in text:
    raise SystemExit('auto-open result anchor not found')
text = text.replace(old, new, 1)

start = text.find('@Composable\nprivate fun FullscreenImageDialog(')
if start < 0:
    raise SystemExit('fullscreen dialog not found')

replacement = r'''@Composable
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
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                ZoomableFileImage(
                    path = path,
                    modifier = Modifier.fillMaxSize(),
                )

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xB31A1A1A),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Pellizca para ampliar · doble toque para zoom",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                        )
                        TextButton(onClick = onClose) {
                            Text("Cerrar", color = Color.White)
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xD91A1A1A),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Descargar") }
                        OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                            Text("Compartir", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableFileImage(
    path: String,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(path) { mutableStateOf(true) }
    var scale by remember(path) { mutableStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(path) { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(path) {
        bitmap = null
        loading = true
        scale = 1f
        offset = Offset.Zero
        bitmap = try {
            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, 3200) }
        } catch (_: OutOfMemoryError) {
            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, 2200) }
        } catch (_: Throwable) {
            null
        }
        loading = false
    }

    DisposableEffect(path) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }

    fun clampOffset(candidate: Offset, targetScale: Float): Offset {
        val image = bitmap ?: return Offset.Zero
        if (containerSize.width <= 0 || containerSize.height <= 0 || targetScale <= 1f) return Offset.Zero
        val cw = containerSize.width.toFloat()
        val ch = containerSize.height.toFloat()
        val imageAspect = image.width.toFloat() / image.height.toFloat()
        val boxAspect = cw / ch
        val fittedWidth: Float
        val fittedHeight: Float
        if (imageAspect > boxAspect) {
            fittedWidth = cw
            fittedHeight = cw / imageAspect
        } else {
            fittedHeight = ch
            fittedWidth = ch * imageAspect
        }
        val maxX = ((fittedWidth * targetScale - cw) / 2f).coerceAtLeast(0f)
        val maxY = ((fittedHeight * targetScale - ch) / 2f).coerceAtLeast(0f)
        return Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY),
        )
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            .pointerInput(path, bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 6f)
                    val nextOffset = if (nextScale <= 1.001f) Offset.Zero else clampOffset(offset + pan, nextScale)
                    scale = nextScale
                    offset = nextOffset
                }
            }
            .pointerInput(path, bitmap) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            offset = Offset.Zero
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator(color = Color.White)
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Resultado ampliable",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
            else -> Text("No se pudo mostrar la imagen", color = Color.White)
        }
    }
}
'''
text = text[:start] + replacement + '\n'
path.write_text(text)
print('v0.8.2 UI transform applied', path, len(text))
