package com.tiagocrispo.furnitureshot.processing

import com.tiagocrispo.furnitureshot.model.ProcessSettings

object PromptPolicy {
    fun automaticSettings(): ProcessSettings = ProcessSettings(
        brightness = 0.018f,
        contrast = 1.042f,
        warmth = 0.006f,
        shadowStrength = 0.58f,
    )
}
