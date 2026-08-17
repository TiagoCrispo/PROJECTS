package com.tiagocrispo.furnitureshot.processing

import com.tiagocrispo.furnitureshot.model.ProcessSettings

object PromptPolicy {
    fun automaticSettings(): ProcessSettings = ProcessSettings(
        brightness = 0.024f,
        contrast = 1.038f,
        warmth = 0.008f,
        shadowStrength = 0.62f,
        saturation = 1.015f,
        detailStrength = 0.18f,
    )
}
