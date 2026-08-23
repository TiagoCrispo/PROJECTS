from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
ui = root / 'app/src/main/java/com/tiagocrispo/furnitureshot/ui/FurnitureShotApp.kt'
store = root / 'app/src/main/java/com/tiagocrispo/furnitureshot/data/ImageStore.kt'
provider = root / 'app/src/main/java/com/tiagocrispo/furnitureshot/processing/MediaPipeReflectiveBackgroundProvider.kt'
engine = root / 'app/src/main/java/com/tiagocrispo/furnitureshot/processing/BackgroundEngine.kt'
static = root / 'scripts/static_validate.py'


def replace_once(path: Path, old: str, new: str):
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, got {count}\n--- OLD ---\n{old[:500]}')
    path.write_text(text.replace(old, new, 1))

# 1) Never swallow coroutine cancellation in gallery/camera/save/image loaders.
replace_once(ui, '''            scope.launch {
                runCatching {
                    val previousOriginal = originalPath
                    val previousResult = resultPath
                    val file = withContext(Dispatchers.IO) { ImageStore.importUri(context, uri) }
                    originalPath = file.absolutePath
                    resultPath = null
                    progress = 0
                    stage = null
                    message = null
                    if (previousOriginal != null && previousResult == null) {
                        withContext(Dispatchers.IO) { ImageStore.discardUncommittedJob(context, previousOriginal) }
                    }
                }.onFailure { message = "No se pudo abrir la foto." }
            }
''', '''            scope.launch {
                try {
                    val previousOriginal = originalPath
                    val previousResult = resultPath
                    val file = withContext(Dispatchers.IO) { ImageStore.importUri(context, uri) }
                    originalPath = file.absolutePath
                    resultPath = null
                    progress = 0
                    stage = null
                    message = null
                    if (previousOriginal != null && previousResult == null) {
                        withContext(Dispatchers.IO) { ImageStore.discardUncommittedJob(context, previousOriginal) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    message = "No se pudo abrir la foto."
                }
            }
''')
replace_once(ui, '''            scope.launch {
                runCatching {
                    val previousOriginal = originalPath
                    val previousResult = resultPath
                    val imported = withContext(Dispatchers.IO) {
                        ImageStore.importCameraFile(context, File(capturePath))
                    }
                    originalPath = imported.absolutePath
                    resultPath = null
                    progress = 0
                    stage = null
                    message = null
                    if (previousOriginal != null && previousResult == null) {
                        withContext(Dispatchers.IO) { ImageStore.discardUncommittedJob(context, previousOriginal) }
                    }
                }.onFailure {
                    runCatching { File(capturePath).delete() }
                    message = "No se pudo importar la foto tomada."
                }
            }
''', '''            scope.launch {
                try {
                    val previousOriginal = originalPath
                    val previousResult = resultPath
                    val imported = withContext(Dispatchers.IO) {
                        ImageStore.importCameraFile(context, File(capturePath))
                    }
                    originalPath = imported.absolutePath
                    resultPath = null
                    progress = 0
                    stage = null
                    message = null
                    if (previousOriginal != null && previousResult == null) {
                        withContext(Dispatchers.IO) { ImageStore.discardUncommittedJob(context, previousOriginal) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    runCatching { File(capturePath).delete() }
                    message = "No se pudo importar la foto tomada."
                }
            }
''')
replace_once(ui, '''        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ImageStore.exportToGallery(context, path) } }
                .onSuccess {
                    toast("Listo. Guardado en la galería")
                    message = null
                }
                .onFailure {
                    toast("No se pudo guardar la foto")
                    message = null
                }
        }
''', '''        scope.launch {
            try {
                withContext(Dispatchers.IO) { ImageStore.exportToGallery(context, path) }
                toast("Listo. Guardado en la galería")
                message = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toast("No se pudo guardar la foto")
                message = null
            }
        }
''')
replace_once(ui, '''        bitmap = runCatching {
            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, maxDimension) }
        }.getOrNull()
        loading = false
''', '''        bitmap = try {
            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, maxDimension) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        loading = false
''')
replace_once(ui, '''        bitmap = try {
            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, 3200) }
        } catch (_: OutOfMemoryError) {
            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, 2200) }
        } catch (_: Throwable) {
            null
        }
''', '''        bitmap = try {
            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, 3200) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, 2200) }
        } catch (_: Exception) {
            null
        }
''')

# 2) Harden generated-result ownership. Only direct children of app-private jobs/<uuid>/ may be exported/shared/committed.
replace_once(store, '''        val safe = runCatching {
            result.canonicalFile.parentFile?.canonicalPath?.startsWith(jobsRoot.canonicalPath + File.separator) == true &&
                GenerationArtifactPolicy.isGeneratedResultFileName(result.name)
        }.getOrDefault(false)
''', '''        val safe = runCatching {
            val canonical = result.canonicalFile
            val jobDir = canonical.parentFile
            jobDir?.parentFile?.canonicalPath == jobsRoot.canonicalPath &&
                GenerationArtifactPolicy.isGeneratedResultFileName(canonical.name)
        }.getOrDefault(false)
''')
replace_once(store, '''    fun exportToGallery(context: Context, resultPath: String): Uri {
        val source = File(resultPath)
        require(source.exists()) { "No existe el resultado a exportar." }
''', '''    fun exportToGallery(context: Context, resultPath: String): Uri {
        val source = requireValidGeneratedResult(context, resultPath)
''')
replace_once(store, '''    fun shareUriForResult(context: Context, resultPath: String): Uri {
        val resultFile = File(resultPath)
        require(resultFile.exists()) { "No existe el resultado a compartir." }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", resultFile)
    }
''', '''    fun shareUriForResult(context: Context, resultPath: String): Uri {
        val resultFile = requireValidGeneratedResult(context, resultPath)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", resultFile)
    }
''')
replace_once(store, '''    fun appendHistory(context: Context, originalPath: String, resultPath: String): HistoryItem {
        val item = HistoryItem(
            id = UUID.randomUUID().toString(),
            originalPath = originalPath,
            resultPath = resultPath,
            createdAt = System.currentTimeMillis(),
        )
''', '''    fun appendHistory(context: Context, originalPath: String, resultPath: String): HistoryItem {
        val result = requireValidGeneratedResult(context, resultPath).canonicalFile
        val original = File(originalPath).canonicalFile
        require(original.name == "original.jpg" && original.parentFile?.canonicalPath == result.parentFile?.canonicalPath) {
            "La foto original y el resultado no pertenecen al mismo trabajo."
        }
        val item = HistoryItem(
            id = UUID.randomUUID().toString(),
            originalPath = original.absolutePath,
            resultPath = result.absolutePath,
            createdAt = System.currentTimeMillis(),
        )
''')
replace_once(store, '''        val safe = runCatching {
            jobDir.canonicalPath.startsWith(jobsRoot.canonicalPath + File.separator)
        }.getOrDefault(false)
''', '''        val safe = runCatching {
            jobDir.canonicalFile.parentFile?.canonicalPath == jobsRoot.canonicalPath
        }.getOrDefault(false)
''')

# 3) Remove the obsolete file-handshake backend. The final app has a direct MediaPipe generator;
#    this broker had no producer and only added a one-second wait after a failed generation.
provider.write_text('''package com.tiagocrispo.furnitureshot.processing

import android.content.Context
import com.tiagocrispo.furnitureshot.model.CatalogStudioStyle
import java.io.File

class MediaPipeReflectiveBackgroundProvider {
    @Volatile
    var lastReason: String? = null
        private set

    suspend fun tryRender(
        context: Context,
        input: BackgroundEngineInput,
        modelPack: File,
    ): BackgroundEngineOutput? {
        val runtime = detectRuntimeBridge() ?: run {
            lastReason = "Se detectó un modelo local (${modelPack.name}), pero no está disponible un runtime compatible de generación local. Se aplicó el motor seguro."
            return null
        }
        val manifest = ModelPackManifestLoader.load(modelPack)
        if (manifest == null) {
            lastReason = "Se detectó un modelo local (${modelPack.name}) y un runtime ${runtime.displayName}, pero falta el archivo de perfil del modelo. Se aplicó el motor seguro."
            return null
        }
        val style = manifest.applyTo(CatalogStudioStyle.default())
        if (!modelPack.isDirectory) {
            lastReason = "El modelo local detectado no es un directorio MediaPipe Image Generator válido; se aplicó el motor seguro."
            return null
        }

        val direct = DirectMediaPipeBackgroundGenerator.tryGenerate(
            context = context,
            input = input,
            modelDirectory = modelPack,
            manifest = manifest,
            style = style,
        )
        lastReason = direct.warning
        if (direct.platePath.isNullOrBlank()) return null
        return BackgroundEngineOutput(
            style = style,
            mode = BackgroundEngineMode.ON_DEVICE_GENERATIVE,
            provider = manifest.providerName,
            backgroundPlatePath = direct.platePath,
            warning = direct.warning,
        )
    }

    private data class RuntimeBridge(val displayName: String)

    private fun detectRuntimeBridge(): RuntimeBridge? {
        val candidates = listOf(
            listOf(
                "com.google.mediapipe.tasks.genai.imagegenerator.ImageGenerator",
                "com.google.mediapipe.tasks.genai.imagegenerator.ImageGeneratorOptions",
            ) to "MediaPipe GenAI ImageGenerator",
            listOf(
                "com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator",
                "com.google.mediapipe.tasks.vision.imagegenerator.ImageGeneratorOptions",
            ) to "MediaPipe Vision ImageGenerator",
        )
        for ((classes, label) in candidates) {
            if (classes.all(::classExists)) return RuntimeBridge(label)
        }
        return null
    }

    private fun classExists(name: String): Boolean = runCatching { Class.forName(name) }.isSuccess
}
''')
replace_once(engine, '''enum class BackgroundEngineMode {
    DEFAULT,
    ON_DEVICE_PROVIDER_BRIDGED,
    ON_DEVICE_GENERATIVE,
}
''', '''enum class BackgroundEngineMode {
    DEFAULT,
    ON_DEVICE_GENERATIVE,
}
''')
for rel in [
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/BackgroundGenerationBroker.kt',
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/PhotoPackAnalyzer.kt',
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/PackConsistencyEngine.kt',
]:
    p = root / rel
    if not p.exists():
        raise SystemExit(f'missing expected legacy file: {rel}')
    p.unlink()

# 4) Adapt the accumulated validator to the frozen direct-only pipeline, then extend it.
text = static.read_text()
text = text.replace(
    "broker = text('app/src/main/java/com/tiagocrispo/furnitureshot/processing/BackgroundGenerationBroker.kt')\n",
    "",
)
text = text.replace(
    "req('suspend fun tryAcquirePlate' in broker and 'delay(125)' in broker and 'Thread.sleep' not in broker, 'background broker still blocks cancellation')\n",
    "req(not (root / 'app/src/main/java/com/tiagocrispo/furnitureshot/processing/BackgroundGenerationBroker.kt').exists(), 'obsolete file-handshake background broker still ships')\n",
)
append = '''

# BLOCK21_FINAL_AUDIT
all_kotlin = "\n".join(p.read_text() for p in (root / "app/src/main/java").rglob("*.kt"))
for forbidden in (
    "qualityReferencePath",
    "referenceImagePath",
    "CatalogSheetComposer",
    "BackgroundGenerationBroker",
    "ON_DEVICE_PROVIDER_BRIDGED",
    "Thread.sleep(",
    "java.net.",
    "okhttp",
    "retrofit",
):
    assert forbidden not in all_kotlin, f"Block21 forbidden legacy/network token returned: {forbidden}"
assert "PhotoPackAnalyzer" not in all_kotlin
assert "PackConsistencyEngine" not in all_kotlin
ui_text = (root / "app/src/main/java/com/tiagocrispo/furnitureshot/ui/FurnitureShotApp.kt").read_text()
assert "catch (cancelled: CancellationException)" in ui_text
store_text = (root / "app/src/main/java/com/tiagocrispo/furnitureshot/data/ImageStore.kt").read_text()
assert "jobDir?.parentFile?.canonicalPath == jobsRoot.canonicalPath" in store_text
assert "requireValidGeneratedResult(context, resultPath)" in store_text
print("PRODUCTSHOT_BLOCK21_FINAL_AUDIT_STATIC_OK")
'''
if 'BLOCK21_FINAL_AUDIT' in text:
    raise SystemExit('Block21 static audit already present')
static.write_text(text + append)

print('PRODUCTSHOT_BLOCK21_FINAL_AUDIT_APPLIED')
