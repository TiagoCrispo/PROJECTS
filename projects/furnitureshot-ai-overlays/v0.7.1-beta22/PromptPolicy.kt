package com.tiagocrispo.furnitureshot.processing

import com.tiagocrispo.furnitureshot.model.ProcessSettings

object PromptPolicy {
    fun automaticSettings(): ProcessSettings = ProcessSettings(
        brightness = 0.010f,
        contrast = 1.025f,
        warmth = 0.004f,
        shadowStrength = 0f,
        saturation = 1.008f,
        detailStrength = 0.065f,
    )
}
