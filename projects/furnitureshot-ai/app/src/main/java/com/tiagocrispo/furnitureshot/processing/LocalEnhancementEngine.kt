package com.tiagocrispo.furnitureshot.processing

import android.content.Context
import com.tiagocrispo.furnitureshot.model.ProcessResult
import com.tiagocrispo.furnitureshot.model.ProcessSettings

/**
 * Stable entry point used by the UI.
 *
 * The implementation now lives in HighFidelityEnhancementEngine so the app no longer
 * downsamples the entire photographic pipeline to the old 1280-1920 px working image.
 */
object LocalEnhancementEngine {
    suspend fun process(
        context: Context,
        originalPath: String,
        settings: ProcessSettings,
    ): ProcessResult = HighFidelityEnhancementEngine.process(
        context = context,
        originalPath = originalPath,
        settings = settings,
    )
}
