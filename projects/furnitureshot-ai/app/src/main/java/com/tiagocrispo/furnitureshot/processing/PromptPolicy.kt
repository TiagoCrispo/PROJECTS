package com.tiagocrispo.furnitureshot.processing

import com.tiagocrispo.furnitureshot.model.ProcessSettings

object PromptPolicy {
    fun automaticSettings(): ProcessSettings = ProcessSettings(
        brightness = 0.034f,
        contrast = 1.064f,
        warmth = 0.010f,
        shadowStrength = 0.98f,
        saturation = 1.026f,
        detailStrength = 0.31f,
    )
}
