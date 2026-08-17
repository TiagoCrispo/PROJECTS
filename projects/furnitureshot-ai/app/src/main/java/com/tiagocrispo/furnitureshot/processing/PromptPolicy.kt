package com.tiagocrispo.furnitureshot.processing

import com.tiagocrispo.furnitureshot.model.ProcessSettings

object PromptPolicy {
    fun automaticSettings(): ProcessSettings = ProcessSettings(
        brightness = 0.036f,
        contrast = 1.072f,
        warmth = 0.012f,
        shadowStrength = 1.0f,
        saturation = 1.030f,
        detailStrength = 0.34f,
    )
}
