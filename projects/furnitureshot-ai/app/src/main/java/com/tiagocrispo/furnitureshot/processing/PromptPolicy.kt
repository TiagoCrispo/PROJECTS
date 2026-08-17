package com.tiagocrispo.furnitureshot.processing

import com.tiagocrispo.furnitureshot.model.ProcessSettings

object PromptPolicy {
    fun automaticSettings(): ProcessSettings = ProcessSettings(
        brightness = 0.018f,
        contrast = 1.028f,
        warmth = 0.006f,
        // Generated shadows are disabled by default. A future contact-shadow stage
        // may opt in only after a confidence/ground-contact check succeeds.
        shadowStrength = 0.0f,
        saturation = 1.012f,
        detailStrength = 0.16f,
    )
}
