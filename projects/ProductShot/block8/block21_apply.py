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
replace_once(ui, '''            scope.launch {\n                runCatching {\n                    val previousOriginal = originalPath\n                    val previousResult = resultPath\n                    val file = withContext(Dispatchers.IO) { ImageStore.importUri(context, uri) }\n                    originalPath = file.absolutePath\n                    resultPath = null\n                    progress = 0\n                    stage = null\n                    message = null\n                    if (previousOriginal != null && previousResult == null) {\n                        withContext(Dispatchers.IO) { ImageStore.discardUncommittedJob(context, previousOriginal) }\n                    }\n                }.onFailure { message = "No se pudo abrir la foto." }\n            }\n''', '''            scope.launch {\n                try {\n                    val previousOriginal = originalPath\n                    val previousResult = resultPath\n                    val file = withContext(Dispatchers.IO) { ImageStore.importUri(context, uri) }\n                    originalPath = file.absolutePath\n                    resultPath = null\n                    progress = 0\n                    stage = null\n                    message = null\n                    if (previousOriginal != null && previousResult == null) {\n                        withContext(Dispatchers.IO) { ImageStore.discardUncommittedJob(context, previousOriginal) }\n                    }\n                } catch (cancelled: CancellationException) {\n                    throw cancelled\n                } catch (_: Exception) {\n                    message = "No se pudo abrir la foto."\n                }\n            }\n''')
replace_once(ui, '''            scope.launch {\n                runCatching {\n                    val previousOriginal = originalPath\n                    val previousResult = resultPath\n                    val imported = withContext(Dispatchers.IO) {\n                        ImageStore.importCameraFile(context, File(capturePath))\n                    }\n                    originalPath = imported.absolutePath\n                    resultPath = null\n                    progress = 0\n                    stage = null\n                    message = null\n                    if (previousOriginal != null && previousResult == null) {\n                        withContext(Dispatchers.IO) { ImageStore.discardUncommittedJob(context, previousOriginal) }\n                    }\n                }.onFailure {\n                    runCatching { File(capturePath).delete() }\n                    message = "No se pudo importar la foto tomada."\n                }\n            }\n''', '''            scope.launch {\n                try {\n                    val previousOriginal = originalPath\n                    val previousResult = resultPath\n                    val imported = withContext(Dispatchers.IO) {\n                        ImageStore.importCameraFile(context, File(capturePath))\n                    }\n                    originalPath = imported.absolutePath\n                    resultPath = null\n                    progress = 0\n                    stage = null\n                    message = null\n                    if (previousOriginal != null && previousResult == null) {\n                        withContext(Dispatchers.IO) { ImageStore.discardUncommittedJob(context, previousOriginal) }\n                    }\n                } catch (cancelled: CancellationException) {\n                    throw cancelled\n                } catch (_: Exception) {\n                    runCatching { File(capturePath).delete() }\n                    message = "No se pudo importar la foto tomada."\n                }\n            }\n''')
replace_once(ui, '''        scope.launch {\n            runCatching { withContext(Dispatchers.IO) { ImageStore.exportToGallery(context, path) } }\n                .onSuccess {\n                    toast("Listo. Guardado en la galería")\n                    message = null\n                }\n                .onFailure {\n                    toast("No se pudo guardar la foto")\n                    message = null\n                }\n        }\n''', '''        scope.launch {\n            try {\n                withContext(Dispatchers.IO) { ImageStore.exportToGallery(context, path) }\n                toast("Listo. Guardado en la galería")\n                message = null\n            } catch (cancelled: CancellationException) {\n                throw cancelled\n            } catch (_: Exception) {\n                toast("No se pudo guardar la foto")\n                message = null\n            }\n        }\n''')
replace_once(ui, '''        bitmap = runCatching {\n            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, maxDimension) }\n        }.getOrNull()\n        loading = false\n''', '''        bitmap = try {\n            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, maxDimension) }\n        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (_: Exception) {\n            null\n        }\n        loading = false\n''')
replace_once(ui, '''        bitmap = try {\n            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, 3200) }\n        } catch (_: OutOfMemoryError) {\n            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, 2200) }\n        } catch (_: Throwable) {\n            null\n        }\n''', '''        bitmap = try {\n            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, 3200) }\n        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (_: OutOfMemoryError) {\n            withContext(Dispatchers.IO) { ImageStore.loadPreview(path, 2200) }\n        } catch (_: Exception) {\n            null\n        }\n''')

# 2) Harden generated-result ownership. Only direct children of app-private jobs/<uuid>/ may be exported/shared/committed.
replace_once(store, '''        val safe = runCatching {\n            result.canonicalFile.parentFile?.canonicalPath?.startsWith(jobsRoot.canonicalPath + File.separator) == true &&\n                GenerationArtifactPolicy.isGeneratedResultFileName(result.name)\n        }.getOrDefault(false)\n''', '''        val safe = runCatching {\n            val canonical = result.canonicalFile\n            val jobDir = canonical.parentFile\n            jobDir?.parentFile?.canonicalPath == jobsRoot.canonicalPath &&\n                GenerationArtifactPolicy.isGeneratedResultFileName(canonical.name)\n        }.getOrDefault(false)\n''')
replace_once(store, '''    fun exportToGallery(context: Context, resultPath: String): Uri {\n        val source = File(resultPath)\n        require(source.exists()) { "No existe el resultado a exportar." }\n''', '''    fun exportToGallery(context: Context, resultPath: String): Uri {\n        val source = requireValidGeneratedResult(context, resultPath)\n''')
replace_once(store, '''    fun shareUriForResult(context: Context, resultPath: String): Uri {\n        val resultFile = File(resultPath)\n        require(resultFile.exists()) { "No existe el resultado a compartir." }\n        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", resultFile)\n    }\n''', '''    fun shareUriForResult(context: Context, resultPath: String): Uri {\n        val resultFile = requireValidGeneratedResult(context, resultPath)\n        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", resultFile)\n    }\n''')
replace_once(store, '''    fun appendHistory(context: Context, originalPath: String, resultPath: String): HistoryItem {\n        val item = HistoryItem(\n            id = UUID.randomUUID().toString(),\n            originalPath = originalPath,\n            resultPath = resultPath,\n            createdAt = System.currentTimeMillis(),\n        )\n''', '''    fun appendHistory(context: Context, originalPath: String, resultPath: String): HistoryItem {\n        val result = requireValidGeneratedResult(context, resultPath).canonicalFile\n        val original = File(originalPath).canonicalFile\n        require(original.name == "original.jpg" && original.parentFile?.canonicalPath == result.parentFile?.canonicalPath) {\n            "La foto original y el resultado no pertenecen al mismo trabajo."\n        }\n        val item = HistoryItem(\n            id = UUID.randomUUID().toString(),\n            originalPath = original.absolutePath,\n            resultPath = result.absolutePath,\n            createdAt = System.currentTimeMillis(),\n        )\n''')
replace_once(store, '''        val safe = runCatching {\n            jobDir.canonicalPath.startsWith(jobsRoot.canonicalPath + File.separator)\n        }.getOrDefault(false)\n''', '''        val safe = runCatching {\n            jobDir.canonicalFile.parentFile?.canonicalPath == jobsRoot.canonicalPath\n        }.getOrDefault(false)\n''')

# 3) Remove the obsolete file-handshake backend. The final app has a direct MediaPipe generator;
#    this broker had no producer and only added a one-second wait after a failed generation.
provider.write_text('''package com.tiagocrispo.furnitureshot.processing\n\nimport android.content.Context\nimport com.tiagocrispo.furnitureshot.model.CatalogStudioStyle\nimport java.io.File\n\nclass MediaPipeReflectiveBackgroundProvider {\n    @Volatile\n    var lastReason: String? = null\n        private set\n\n    suspend fun tryRender(\n        context: Context,\n        input: BackgroundEngineInput,\n        modelPack: File,\n    ): BackgroundEngineOutput? {\n        val runtime = detectRuntimeBridge() ?: run {\n            lastReason = "Se detectó un modelo local (${modelPack.name}), pero no está disponible un runtime compatible de generación local. Se aplicó el motor seguro."\n            return null\n        }\n        val manifest = ModelPackManifestLoader.load(modelPack)\n        if (manifest == null) {\n            lastReason = "Se detectó un modelo local (${modelPack.name}) y un runtime ${runtime.displayName}, pero falta el archivo de perfil del modelo. Se aplicó el motor seguro."\n            return null\n        }\n        val style = manifest.applyTo(CatalogStudioStyle.default())\n        if (!modelPack.isDirectory) {\n            lastReason = "El modelo local detectado no es un directorio MediaPipe Image Generator válido; se aplicó el motor seguro."\n            return null\n        }\n\n        val direct = DirectMediaPipeBackgroundGenerator.tryGenerate(\n            context = context,\n            input = input,\n            modelDirectory = modelPack,\n            manifest = manifest,\n            style = style,\n        )\n        lastReason = direct.warning\n        if (direct.platePath.isNullOrBlank()) return null\n        return BackgroundEngineOutput(\n            style = style,\n            mode = BackgroundEngineMode.ON_DEVICE_GENERATIVE,\n            provider = manifest.providerName,\n            backgroundPlatePath = direct.platePath,\n            warning = direct.warning,\n        )\n    }\n\n    private data class RuntimeBridge(val displayName: String)\n\n    private fun detectRuntimeBridge(): RuntimeBridge? {\n        val candidates = listOf(\n            listOf(\n                "com.google.mediapipe.tasks.genai.imagegenerator.ImageGenerator",\n                "com.google.mediapipe.tasks.genai.imagegenerator.ImageGeneratorOptions",\n            ) to "MediaPipe GenAI ImageGenerator",\n            listOf(\n                "com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator",\n                "com.google.mediapipe.tasks.vision.imagegenerator.ImageGeneratorOptions",\n            ) to "MediaPipe Vision ImageGenerator",\n        )\n        for ((classes, label) in candidates) {\n            if (classes.all(::classExists)) return RuntimeBridge(label)\n        }\n        return null\n    }\n\n    private fun classExists(name: String): Boolean = runCatching { Class.forName(name) }.isSuccess\n}\n''')
replace_once(engine, '''enum class BackgroundEngineMode {\n    DEFAULT,\n    ON_DEVICE_PROVIDER_BRIDGED,\n    ON_DEVICE_GENERATIVE,\n}\n''', '''enum class BackgroundEngineMode {\n    DEFAULT,\n    ON_DEVICE_GENERATIVE,\n}\n''')
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
all_kotlin = \"\\n\".join(p.read_text() for p in (root / \"app/src/main/java\").rglob(\"*.kt\"))
for forbidden in (
    \"qualityReferencePath\",
    \"referenceImagePath\",
    \"CatalogSheetComposer\",
    \"BackgroundGenerationBroker\",
    \"ON_DEVICE_PROVIDER_BRIDGED\",
    \"Thread.sleep(\",
    \"java.net.\",
    \"okhttp\",
    \"retrofit\",
):
    assert forbidden not in all_kotlin, f\"Block21 forbidden legacy/network token returned: {forbidden}\"
assert \"PhotoPackAnalyzer\" not in all_kotlin
assert \"PackConsistencyEngine\" not in all_kotlin
ui_text = (root / \"app/src/main/java/com/tiagocrispo/furnitureshot/ui/FurnitureShotApp.kt\").read_text()
assert \"catch (cancelled: CancellationException)\" in ui_text
store_text = (root / \"app/src/main/java/com/tiagocrispo/furnitureshot/data/ImageStore.kt\").read_text()
assert \"jobDir?.parentFile?.canonicalPath == jobsRoot.canonicalPath\" in store_text
assert \"requireValidGeneratedResult(context, resultPath)\" in store_text
print(\"PRODUCTSHOT_BLOCK21_FINAL_AUDIT_STATIC_OK\")
'''
if 'BLOCK21_FINAL_AUDIT' in text:
    raise SystemExit('Block21 static audit already present')
static.write_text(text + append)

print('PRODUCTSHOT_BLOCK21_FINAL_AUDIT_APPLIED')
