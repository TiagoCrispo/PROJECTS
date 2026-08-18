package com.tiagocrispo.furnitureshot.processing

import java.io.File

/**
 * The main pipeline already performs full-resolution matte, texture and QC.
 * A second JPEG re-detection/recomposition pass previously caused halos,
 * texture loss and synthetic-shadow artifacts, so the finish pass is now a
 * deliberate no-op compatibility layer.
 */
object FinishPassEngine {
    fun apply(resultPath: String): String {
        val file = File(resultPath)
        return if (file.exists()) file.absolutePath else resultPath
    }
}
