package com.tiagocrispo.furnitureshot.processing

import com.tiagocrispo.furnitureshot.model.ProcessSettings

object PromptPolicy {
    fun automaticSettings(): ProcessSettings = ProcessSettings(
        brightness = 0.030f,
        contrast = 1.052f,
        warmth = 0.010f,
        shadowStrength = 0.74f,
        saturation = 1.022f,
        detailStrength = 0.27f,
    )
}
