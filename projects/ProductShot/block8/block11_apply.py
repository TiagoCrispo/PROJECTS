#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()

def read(rel):
    return (root / rel).read_text(encoding='utf-8')

def write(rel, text):
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')

def replace(rel, old, new, count=-1):
    text = read(rel)
    if old not in text:
        raise SystemExit(f'Expected source fragment not found in {rel}: {old[:120]!r}')
    text2 = text.replace(old, new, count)
    write(rel, text2)

# Release identity.
replace('app/build.gradle.kts', 'versionCode = 55', 'versionCode = 57')
replace('app/build.gradle.kts', 'versionName = "0.9.9-guided-local-install"', 'versionName = "0.9.11-local-ai-quality"')

# Persisted local-AI readiness state.
write('app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalAiReadinessStore.kt', r'''package com.tiagocrispo.furnitureshot.processing

import android.content.Context

internal data class LocalAiReadiness(
    val validated: Boolean,
    val message: String?,
    val timestampMs: Long,
)

internal object LocalAiReadinessStore {
    private const val PREFS = "productshot_local_ai"
    private const val KEY_VALIDATED = "validated"
    private const val KEY_MESSAGE = "message"
    private const val KEY_TIMESTAMP = "timestamp"

    fun read(context: Context): LocalAiReadiness {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return LocalAiReadiness(
            validated = prefs.getBoolean(KEY_VALIDATED, false),
            message = prefs.getString(KEY_MESSAGE, null),
            timestampMs = prefs.getLong(KEY_TIMESTAMP, 0L),
        )
    }

    fun write(context: Context, validated: Boolean, message: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VALIDATED, validated)
            .putString(KEY_MESSAGE, message)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }
}
''')

# Diagnostics surface model + real readiness state.
replace(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalAiDiagnostics.kt',
    '    val modelStatus: LocalModelStatus,\n) {',
    '    val modelStatus: LocalModelStatus,\n    val readiness: LocalAiReadiness,\n) {',
)
replace(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalAiDiagnostics.kt',
    '''            append(if (modelStatus.usable) "Modelo local: listo" else "Modelo local: ${modelStatus.message}")
            append("\\n")
            append(if (recommendedForGeneration) "Generación local: compatible" else "Generación local: usará fallback seguro si el dispositivo/modelo no alcanza")''',
    '''            append(if (modelStatus.usable) "Modelo local: listo" else "Modelo local: ${modelStatus.message}")
            append("\\n")
            append(
                when {
                    !modelStatus.usable -> "Motor IA: pendiente de un modelo válido"
                    readiness.validated -> readiness.message ?: "Motor IA: validado y listo"
                    readiness.message != null -> readiness.message
                    recommendedForGeneration -> "Motor IA: listo para ejecutar autoprueba"
                    else -> "Motor IA: el dispositivo no alcanza el umbral recomendado"
                }
            )''',
)
replace(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalAiDiagnostics.kt',
    '            modelStatus = LocalModelManager.inspect(context),\n        )',
    '            modelStatus = LocalModelManager.inspect(context),\n            readiness = LocalAiReadinessStore.read(context),\n        )',
)

# Reinstall/uninstall invalidates previous readiness.
replace(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalModelManager.kt',
    '    suspend fun installZip(context: Context, uri: Uri): ModelInstallResult = withContext(Dispatchers.IO) {\n        val staging = File(context.filesDir, STAGING_ROOT)',
    '    suspend fun installZip(context: Context, uri: Uri): ModelInstallResult = withContext(Dispatchers.IO) {\n        LocalAiReadinessStore.write(context, validated = false, message = "Motor pendiente de validación tras instalar/cambiar el modelo.")\n        val staging = File(context.filesDir, STAGING_ROOT)',
)
replace(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalModelManager.kt',
    '    fun uninstall(context: Context): Boolean {\n        val root = modelRoot(context)',
    '    fun uninstall(context: Context): Boolean {\n        LocalAiReadinessStore.write(context, validated = false, message = "Motor local no instalado.")\n        val root = modelRoot(context)',
)

# Operational self-test: initialization + one real diffusion iteration.
selftest = read('app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalAiSelfTest.kt')
if 'BitmapExtractor' not in selftest:
    selftest = selftest.replace(
        'import android.os.SystemClock\n',
        'import android.os.SystemClock\nimport com.google.mediapipe.framework.image.BitmapExtractor\n',
    )
selftest = selftest.replace(
    '''        if (!diagnostics.modelStatus.usable) {
            return@withContext LocalAiSelfTestResult(false, "Autoprueba: instala primero un motor local válido.")
        }''',
    '''        if (!diagnostics.modelStatus.usable) {
            LocalAiReadinessStore.write(context, false, "Autoprueba: instala primero un motor local válido.")
            return@withContext LocalAiSelfTestResult(false, "Autoprueba: instala primero un motor local válido.")
        }''',
)
selftest = selftest.replace(
    '''        if (!diagnostics.arm64) {
            return@withContext LocalAiSelfTestResult(false, "Autoprueba: este dispositivo no expone arm64-v8a.")
        }''',
    '''        if (!diagnostics.arm64) {
            LocalAiReadinessStore.write(context, false, "Autoprueba: este dispositivo no expone arm64-v8a.")
            return@withContext LocalAiSelfTestResult(false, "Autoprueba: este dispositivo no expone arm64-v8a.")
        }''',
)
selftest = selftest.replace(
    '''        if (!diagnostics.imageGeneratorRuntimePresent) {
            return@withContext LocalAiSelfTestResult(false, "Autoprueba: el runtime MediaPipe Image Generator no está disponible.")
        }''',
    '''        if (!diagnostics.imageGeneratorRuntimePresent) {
            LocalAiReadinessStore.write(context, false, "Autoprueba: el runtime MediaPipe Image Generator no está disponible.")
            return@withContext LocalAiSelfTestResult(false, "Autoprueba: el runtime MediaPipe Image Generator no está disponible.")
        }''',
)
selftest = selftest.replace(
    '''        if (!diagnostics.recommendedForGeneration) {
            return@withContext LocalAiSelfTestResult(
                false,
                "Autoprueba: modelo detectado, pero el dispositivo no supera el umbral seguro de RAM/API para iniciar Image Generator; ProductShot mantendrá el fallback local seguro.",
            )
        }''',
    '''        if (!diagnostics.recommendedForGeneration) {
            LocalAiReadinessStore.write(context, false, "Autoprueba: hardware por debajo del umbral recomendado.")
            return@withContext LocalAiSelfTestResult(
                false,
                "Autoprueba: modelo detectado, pero el dispositivo no supera el umbral seguro de RAM/API para iniciar Image Generator; ProductShot mantendrá el fallback local seguro.",
            )
        }''',
)
selftest = selftest.replace(
    '''        val modelDirectory = LocalModelManager.preferredModelDirectory(context)
            ?: return@withContext LocalAiSelfTestResult(false, "Autoprueba: no se encontró el directorio normalizado del modelo.")''',
    '''        val modelDirectory = LocalModelManager.preferredModelDirectory(context)
            ?: run {
                LocalAiReadinessStore.write(context, false, "Autoprueba: no se encontró el directorio normalizado del modelo.")
                return@withContext LocalAiSelfTestResult(false, "Autoprueba: no se encontró el directorio normalizado del modelo.")
            }''',
)
selftest = selftest.replace(
    '''            generator = ImageGenerator.createFromOptions(context, options)
            val elapsed = SystemClock.elapsedRealtime() - started
            LocalAiSelfTestResult(
                true,
                "Autoprueba OK: runtime + modelo inicializan correctamente (${elapsed} ms). La generación completa se valida al crear la foto.",
            )''',
    '''            generator = ImageGenerator.createFromOptions(context, options)
            val initElapsed = SystemClock.elapsedRealtime() - started
            val probeStarted = SystemClock.elapsedRealtime()
            val probeResult = generator.generate(
                "empty neutral product photography studio background, no objects, no text",
                1,
                20260822,
            )
            val probeImage = probeResult?.generatedImage()
                ?: error("MediaPipe no devolvió imagen en la inferencia de prueba.")
            val probeBitmap = BitmapExtractor.extract(probeImage)
            require(probeBitmap.width >= 64 && probeBitmap.height >= 64) {
                "MediaPipe devolvió una imagen de prueba inválida."
            }
            probeBitmap.recycle()
            val probeElapsed = SystemClock.elapsedRealtime() - probeStarted
            LocalAiReadinessStore.write(context, true, "Motor validado: inicialización + inferencia local OK.")
            LocalAiSelfTestResult(
                true,
                "Autoprueba OK: motor inicializó (${initElapsed} ms) y ejecutó una inferencia local real (${probeElapsed} ms).",
            )''',
)
selftest = selftest.replace(
    '''        } catch (oom: OutOfMemoryError) {
            LocalAiSelfTestResult(false, "Autoprueba: memoria insuficiente para inicializar el motor; se mantendrá el fallback local seguro.")
        } catch (t: Throwable) {
            LocalAiSelfTestResult(false, "Autoprueba: el motor no pudo inicializarse (${t.javaClass.simpleName}).")''',
    '''        } catch (oom: OutOfMemoryError) {
            LocalAiReadinessStore.write(context, false, "Autoprueba: memoria insuficiente para ejecutar el motor.")
            LocalAiSelfTestResult(false, "Autoprueba: memoria insuficiente para inicializar/ejecutar el motor; se mantendrá el fallback local seguro.")
        } catch (t: Throwable) {
            LocalAiReadinessStore.write(context, false, "Autoprueba: el motor falló (${t.javaClass.simpleName}).")
            LocalAiSelfTestResult(false, "Autoprueba: el motor no pudo ejecutar la inferencia (${t.javaClass.simpleName}).")''',
)
write('app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalAiSelfTest.kt', selftest)

# Main result: save the single hero output, not the repeated catalog sheet.
engine_rel = 'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalEnhancementEngine.kt'
replace(engine_rel, '        var generatedPlateAccepted = false\n', '')
replace(engine_rel, '        var sheet: Bitmap? = null\n', '')
replace(engine_rel, '            generatedPlateAccepted = !backgroundPlan.backgroundPlatePath.isNullOrBlank() && plateValidation.accepted\n', '')
replace(engine_rel, '                generatedPlateAccepted = false\n', '')
replace(
    engine_rel,
    '''            onProgress(91, "Armando catálogo")
            sheet = CatalogSheetComposer.compose(
                cutout = cutout,
                shadow = naturalShadow,
                objectBounds = maskReport.bounds,
                style = referenceStyle,
                generatedBackgroundPlate = if (generatedPlateAccepted) plate else null,
            )

            onProgress(97, "Guardando resultado")
            val resultFile = saveJpeg(originalPath, sheet)''',
    '''            onProgress(91, "Finalizando composición")

            onProgress(97, "Guardando resultado")
            val resultFile = saveJpeg(originalPath, output)''',
)
replace(engine_rel, '            sheet?.let { if (!it.isRecycled) it.recycle() }\n', '')

# Direct MediaPipe generation: bounded retries, quality normalization and memory hygiene.
gen_rel = 'app/src/main/java/com/tiagocrispo/furnitureshot/processing/DirectMediaPipeBackgroundGenerator.kt'
gen = read(gen_rel)
gen = gen.replace('    private const val LOW_MEMORY_ITERATIONS = 12\n', '    private const val LOW_MEMORY_ITERATIONS = 12\n    private const val MAX_ATTEMPTS = 3\n')
old_generation = '''            val prompt = buildPrompt(style, manifest)
            val seed = stableSeed(style, manifest.providerName)
            val iterations = if (totalRam < 9_000_000_000L) LOW_MEMORY_ITERATIONS else DEFAULT_ITERATIONS
            val result = generator.generate(prompt, iterations, seed)
            val mpImage = result?.generatedImage()
                ?: return DirectGeneratorResult(null, "MediaPipe no devolvió una imagen generada; se aplicó el fallback seguro.")
            generated = BitmapExtractor.extract(mpImage)
            if (generated == null || generated!!.width < 64 || generated!!.height < 64) {
                return DirectGeneratorResult(null, "MediaPipe devolvió un background plate inválido; se aplicó el fallback seguro.")
            }

            fitted = centerCropAndScale(generated!!, input.sourceWidth, input.sourceHeight)
            val validation = StudioBackgroundPlateFactory.validate(fitted!!)
            if (!validation.accepted) {
                return DirectGeneratorResult(null, "El background plate local fue rechazado: ${validation.reason ?: "control de calidad"}.")
            }

            val dir = File(context.cacheDir, "productshot/direct-mediapipe").apply { mkdirs() }
            val output = File(dir, "background_plate_${System.currentTimeMillis()}.jpg")
            FileOutputStream(output).use { stream ->
                check(fitted!!.compress(Bitmap.CompressFormat.JPEG, 96, stream)) {
                    "No se pudo guardar el background plate local."
                }
            }
            DirectGeneratorResult(
                output.absolutePath,
                "Background plate generado localmente con MediaPipe Image Generator.",
            )'''
new_generation = '''            val iterations = if (totalRam < 9_000_000_000L) LOW_MEMORY_ITERATIONS else DEFAULT_ITERATIONS
            var acceptedPlate: Bitmap? = null
            var lastValidationReason: String? = null
            val promptVariants = buildPromptVariants(style)
            val baseSeed = stableSeed(style, manifest.providerName)

            for (attempt in 0 until MAX_ATTEMPTS) {
                val prompt = promptVariants[attempt % promptVariants.size]
                val seed = ((baseSeed.toLong() + attempt.toLong() * 9973L) % Int.MAX_VALUE).toInt()
                val result = generator.generate(prompt, iterations, seed)
                val mpImage = result?.generatedImage() ?: continue
                generated?.let { if (!it.isRecycled) it.recycle() }
                fitted?.let { if (!it.isRecycled) it.recycle() }
                generated = BitmapExtractor.extract(mpImage)
                if (generated == null || generated!!.width < 64 || generated!!.height < 64) continue

                val scaled = centerCropAndScale(generated!!, input.sourceWidth, input.sourceHeight)
                fitted = try {
                    neutralizeStudioPlate(scaled)
                } finally {
                    if (!scaled.isRecycled) scaled.recycle()
                }
                val validation = StudioBackgroundPlateFactory.validate(fitted!!)
                if (validation.accepted) {
                    acceptedPlate = fitted
                    fitted = null
                    break
                }
                lastValidationReason = validation.reason
            }

            val finalPlate = acceptedPlate
                ?: return DirectGeneratorResult(null, "El background plate local fue rechazado: ${lastValidationReason ?: "control de calidad"}.")

            val dir = File(context.cacheDir, "productshot/direct-mediapipe").apply { mkdirs() }
            val output = File(dir, "background_plate_${System.currentTimeMillis()}.jpg")
            try {
                FileOutputStream(output).use { stream ->
                    check(finalPlate.compress(Bitmap.CompressFormat.JPEG, 96, stream)) {
                        "No se pudo guardar el background plate local."
                    }
                }
            } finally {
                if (!finalPlate.isRecycled) finalPlate.recycle()
            }
            LocalAiReadinessStore.write(context, true, "MediaPipe generó un fondo local válido para ProductShot.")
            DirectGeneratorResult(
                output.absolutePath,
                "Background plate generado localmente con MediaPipe Image Generator.",
            )'''
if old_generation not in gen:
    raise SystemExit('Expected DirectMediaPipe generation block not found')
gen = gen.replace(old_generation, new_generation)
old_prompt = '''    private fun buildPrompt(style: CatalogReferenceStyle, manifest: ModelPackManifest): String {
        val warm = Color.red(style.wallTop) >= Color.blue(style.wallTop)
        val temperature = if (warm) "warm neutral" else "cool neutral"
        return buildString {
            append("empty premium product photography studio background, ")
            append(temperature)
            append(" off-white seamless wall and subtle light neutral floor, ")
            append("soft diffused commercial softbox lighting, realistic photographic exposure, ")
            append("clean cyclorama, natural gentle floor transition, no dramatic shadows, ")
            append("NO product, NO furniture, NO objects, NO props, NO people, NO text, NO logo, NO watermark, ")
            append("empty scene only, catalog photography background, high realism")
        }
    }
'''
new_prompt = '''    private fun buildPromptVariants(style: CatalogReferenceStyle): List<String> {
        val warm = Color.red(style.wallTop) >= Color.blue(style.wallTop)
        val temperature = if (warm) "warm neutral" else "cool neutral"
        return listOf(
            "empty premium product photography studio background, ${temperature} off-white seamless wall and subtle light neutral floor, soft diffused commercial softbox lighting, realistic photographic exposure, clean cyclorama, natural gentle floor transition, no dramatic shadows, NO product, NO furniture, NO objects, NO props, NO people, NO text, NO logo, NO watermark, empty scene only, catalog photography background, high realism",
            "empty ecommerce furniture studio set, clean matte wall, very light neutral floor, soft broad light, centered glow, minimal reflections, professional catalog backdrop only, no object, no table, no shelf, no person, no text, no logo, no watermark",
            "blank commercial shooting bay for furniture photography, bright neutral seamless studio, gentle floor gradient, subtle spotlight, realistic empty background plate, no objects, no props, no decor, no text, no watermark",
        )
    }

    private fun neutralizeStudioPlate(source: Bitmap): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            var r = Color.red(c).toFloat()
            var g = Color.green(c).toFloat()
            var b = Color.blue(c).toFloat()
            val mean = (r + g + b) / 3f
            r = mean + (r - mean) * 0.34f
            g = mean + (g - mean) * 0.34f
            b = mean + (b - mean) * 0.34f
            r = (r * 0.94f + 14f).coerceIn(0f, 255f)
            g = (g * 0.94f + 14f).coerceIn(0f, 255f)
            b = (b * 0.94f + 14f).coerceIn(0f, 255f)
            pixels[i] = Color.rgb(r.toInt(), g.toInt(), b.toInt())
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }
'''
if old_prompt not in gen:
    raise SystemExit('Expected DirectMediaPipe prompt block not found')
gen = gen.replace(old_prompt, new_prompt)
gen = gen.replace(
    '''        } catch (t: Throwable) {
            DirectGeneratorResult(''',
    '''        } catch (t: Throwable) {
            LocalAiReadinessStore.write(context, false, "MediaPipe falló durante la generación (${t.javaClass.simpleName}).")
            DirectGeneratorResult(''',
)
write(gen_rel, gen)

# Strong static contract for the release.
write('scripts/static_validate.py', r'''#!/usr/bin/env python3
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
def text(rel): return (root / rel).read_text(encoding='utf-8')
def req(cond, msg):
    if not cond: raise SystemExit('FAIL: ' + msg)

build = text('app/build.gradle.kts')
manifest = text('app/src/main/AndroidManifest.xml')
manager = text('app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalModelManager.kt')
selftest = text('app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalAiSelfTest.kt')
ui = text('app/src/main/java/com/tiagocrispo/furnitureshot/ui/FurnitureShotApp.kt')
engine = text('app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalEnhancementEngine.kt')
generator = text('app/src/main/java/com/tiagocrispo/furnitureshot/processing/DirectMediaPipeBackgroundGenerator.kt')
readiness = text('app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalAiReadinessStore.kt')

req('versionCode = 57' in build, 'versionCode 57 missing')
req('versionName = "0.9.11-local-ai-quality"' in build, 'versionName mismatch')
req('compileSdk = 36' in build and 'targetSdk = 36' in build, 'SDK 36 contract missing')
req('android.permission.INTERNET' not in manifest, 'INTERNET permission must remain absent')
req('android.permission.ACCESS_NETWORK_STATE' not in manifest, 'ACCESS_NETWORK_STATE must remain absent')
ET.parse(root / 'app/src/main/AndroidManifest.xml')
req('DigestInputStream' in manager and 'archiveSha256' in manager, 'secure model installer digest missing')
req('ImageGenerator.createFromOptions' in selftest, 'model initialization self-test missing')
req('generator.generate(' in selftest and 'BitmapExtractor.extract' in selftest, 'real inference probe missing')
req('Probar motor local' in ui, 'self-test UI missing')
req('Ya tengo el ZIP del motor' in ui, 'model install UI missing')
req('saveJpeg(originalPath, output)' in engine, 'single hero output missing')
req('CatalogSheetComposer.compose(' not in engine, 'repeated catalog sheet still wired as main result')
req('MAX_ATTEMPTS = 3' in generator, 'bounded retry policy missing')
req('buildPromptVariants' in generator and 'neutralizeStudioPlate' in generator, 'MediaPipe quality path missing')
req('LocalAiReadinessStore' in readiness, 'readiness persistence missing')
print('PRODUCTSHOT_BLOCK11_STATIC_VALIDATION_OK')
''')
write('docs/BLOCK11_LOCAL_AI_QUALITY.md', '''# ProductShot v0.9.11 — local AI quality\n\n- Single hero result instead of the repeated catalog sheet.\n- Self-test proves real local inference with one diffusion iteration.\n- Readiness persists and is invalidated when the model changes.\n- MediaPipe retries up to three bounded prompt/seed variants.\n- Generated plates are neutralized and QC-gated before composition.\n- Temporary bitmaps are recycled between attempts.\n- Fail-closed fallback preserves the exact product geometry.\n''')

print('PRODUCTSHOT_BLOCK11_APPLIED')
