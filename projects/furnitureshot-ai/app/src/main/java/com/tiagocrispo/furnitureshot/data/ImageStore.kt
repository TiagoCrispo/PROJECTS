package com.tiagocrispo.furnitureshot.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.tiagocrispo.furnitureshot.model.HistoryItem
import com.tiagocrispo.furnitureshot.processing.DetailEnhancementEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object ImageStore {
    private const val JOBS_DIR = "jobs"
    private const val HISTORY_FILE = "history.json"

    fun createCameraUri(context: Context): Pair<Uri, File> {
        val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(cameraDir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return uri to file
    }

    fun importUri(context: Context, uri: Uri): File {
        val jobDir = File(context.filesDir, "$JOBS_DIR/${UUID.randomUUID()}").apply { mkdirs() }
        val destination = File(jobDir, "original.jpg")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "No se pudo abrir la imagen seleccionada." }
            FileOutputStream(destination).use { output -> input.copyTo(output, 128 * 1024) }
        }
        return destination
    }

    fun importCameraFile(context: Context, cameraFile: File): File {
        val jobDir = File(context.filesDir, "$JOBS_DIR/${UUID.randomUUID()}").apply { mkdirs() }
        val destination = File(jobDir, "original.jpg")
        cameraFile.inputStream().use { input ->
            destination.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
        }
        cameraFile.delete()
        return destination
    }

    fun loadPreview(path: String, maxDimension: Int = 1600): Bitmap =
        decodeOriented(File(path), maxDimension)

    fun loadForProcessing(path: String, maxDimension: Int = 4096): Bitmap =
        decodeOriented(File(path), maxDimension)

    fun saveResult(originalPath: String, bitmap: Bitmap): File {
        val original = File(originalPath)
        val result = File(original.parentFile, "result.jpg")
        val temp = File(original.parentFile, "result.tmp.jpg")

        val detailed = DetailEnhancementEngine.enhanceForCatalog(bitmap)
        val highQuality = prepareHighQualityResult(detailed)

        try {
            FileOutputStream(temp).use { output ->
                check(highQuality.compress(Bitmap.CompressFormat.JPEG, 99, output)) {
                    "No se pudo guardar el resultado interno."
                }
            }

            if (result.exists() && !result.delete()) {
                temp.delete()
                error("No se pudo reemplazar el resultado anterior.")
            }
            if (!temp.renameTo(result)) {
                temp.copyTo(result, overwrite = true)
                temp.delete()
            }
        } finally {
            if (highQuality !== detailed && highQuality !== bitmap && !highQuality.isRecycled) {
                highQuality.recycle()
            }
            if (detailed !== bitmap && !detailed.isRecycled) {
                detailed.recycle()
            }
            if (temp.exists()) temp.delete()
        }
        return result
    }

    private fun prepareHighQualityResult(source: Bitmap): Bitmap {
        val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        val maxLongEdge = when {
            maxMemoryMb <= 192L -> 2200
            maxMemoryMb <= 256L -> 2600
            maxMemoryMb <= 384L -> 3000
            else -> 3200
        }

        val currentLongEdge = maxOf(source.width, source.height)
        if (currentLongEdge >= maxLongEdge) return source

        val targetLongEdge = minOf(currentLongEdge * 2, maxLongEdge)
        if (targetLongEdge <= currentLongEdge) return source

        val scale = targetLongEdge.toFloat() / currentLongEdge.toFloat()
        val targetWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).toInt().coerceAtLeast(1)

        return try {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        } catch (_: OutOfMemoryError) {
            source
        }
    }

    fun exportToGallery(context: Context, resultPath: String): Uri? {
        val source = File(resultPath)
        require(source.exists()) { "No existe el resultado a exportar." }
        val displayName = "FurnitureShot_${System.currentTimeMillis()}.jpg"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/FurnitureShot AI",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            try {
                resolver.openOutputStream(uri).use { output ->
                    requireNotNull(output)
                    source.inputStream().use { input -> input.copyTo(output, 128 * 1024) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        } else {
            @Suppress("DEPRECATION")
            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val directory = File(pictures, "FurnitureShot AI").apply { mkdirs() }
            val destination = File(directory, displayName)
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            }
            var scannedUri: Uri? = null
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destination.absolutePath),
                arrayOf("image/jpeg"),
            ) { _, uri ->
                scannedUri = uri
            }
            scannedUri ?: Uri.fromFile(destination)
        }
    }

    fun shareUriForResult(context: Context, resultPath: String): Uri {
        val resultFile = File(resultPath)
        require(resultFile.exists()) { "No existe el resultado a compartir." }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", resultFile)
    }

    fun appendHistory(context: Context, originalPath: String, resultPath: String): HistoryItem {
        val item = HistoryItem(
            id = UUID.randomUUID().toString(),
            originalPath = originalPath,
            resultPath = resultPath,
            createdAt = System.currentTimeMillis(),
        )
        val current = loadHistory(context).toMutableList()
        current.add(0, item)
        writeHistory(context, current.take(40))
        return item
    }

    fun loadHistory(context: Context): List<HistoryItem> {
        val file = File(context.filesDir, HISTORY_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    val item = HistoryItem(
                        id = obj.getString("id"),
                        originalPath = obj.getString("originalPath"),
                        resultPath = obj.getString("resultPath"),
                        createdAt = obj.getLong("createdAt"),
                    )
                    if (File(item.originalPath).exists() && File(item.resultPath).exists()) {
                        add(item)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeHistory(context: Context, items: List<HistoryItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("originalPath", item.originalPath)
                    .put("resultPath", item.resultPath)
                    .put("createdAt", item.createdAt),
            )
        }
        val temp = File(context.filesDir, "$HISTORY_FILE.tmp")
        temp.writeText(array.toString())
        val final = File(context.filesDir, HISTORY_FILE)
        if (final.exists()) final.delete()
        check(temp.renameTo(final)) { "No se pudo persistir el historial." }
    }

    private fun decodeOriented(file: File, maxDimension: Int): Bitmap {
        require(file.exists()) { "La imagen ya no existe." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Formato de imagen no compatible."
        }

        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.absolutePath, options)) {
            "No se pudo decodificar la imagen."
        }
        val resized = if (maxOf(decoded.width, decoded.height) > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(decoded.width, decoded.height).toFloat()
            val scaled = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== decoded) decoded.recycle()
            scaled
        } else {
            decoded
        }

        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return resized

        val oriented = Bitmap.createBitmap(
            resized,
            0,
            0,
            resized.width,
            resized.height,
            matrix,
            true,
        )
        if (oriented !== resized) resized.recycle()
        return oriented
    }
}
