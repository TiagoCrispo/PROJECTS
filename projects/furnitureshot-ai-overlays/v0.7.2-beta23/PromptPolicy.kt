package com.tiagocrispo.furnitureshot.processing

import com.tiagocrispo.furnitureshot.model.ProcessSettings

enum class ProcessingProfile {
    FAST,
    BALANCED,
    QUALITY_MAX,
}

object PromptPolicy {
    fun automaticSettings(profile: ProcessingProfile = ProcessingProfile.QUALITY_MAX): ProcessSettings = when (profile) {
        ProcessingProfile.FAST -> ProcessSettings(
            brightness = 0.004f,
            contrast = 1.010f,
            warmth = 0.002f,
            shadowStrength = 0f,
            saturation = 1.003f,
            detailStrength = 0.035f,
        )
        ProcessingProfile.BALANCED -> ProcessSettings(
            brightness = 0.002f,
            contrast = 1.012f,
            warmth = 0.002f,
            shadowStrength = 0f,
            saturation = 1.006f,
            detailStrength = 0.055f,
        )
        ProcessingProfile.QUALITY_MAX -> ProcessSettings(
            // Default reference-match profile. The engine itself performs adaptive
            // exposure/structure/detail gating, so these remain deliberately mild.
            brightness = 0f,
            contrast = 1.008f,
            warmth = 0.001f,
            shadowStrength = 0f,
            saturation = 1.010f,
            detailStrength = 0.082f,
        )
    }
}
