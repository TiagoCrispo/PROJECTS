package com.tiagocrispo.furnitureshot.processing

import com.tiagocrispo.furnitureshot.model.ProcessSettings

object PromptPolicy {
    val defaultPrompt: String = ""

    fun interpret(prompt: String): ProcessSettings {
        val normalized = prompt.lowercase()

        val asksBrighter = normalized.contains("aclara") ||
            normalized.contains("más claro") ||
            normalized.contains("mas claro") ||
            normalized.contains("más luminosa") ||
            normalized.contains("mas luminosa")
        val asksWarmer = normalized.contains("más cálida") ||
            normalized.contains("mas calida") ||
            normalized.contains("más cálido") ||
            normalized.contains("mas calido")
        val asksSofter = normalized.contains("más suave") ||
            normalized.contains("mas suave") ||
            normalized.contains("natural")
        val asksNoShadow = normalized.contains("sin sombra")

        return ProcessSettings(
            brightness = (0.018f + if (asksBrighter) 0.014f else 0f).coerceAtMost(0.05f),
            contrast = if (asksSofter) 1.028f else 1.042f,
            warmth = if (asksWarmer) 0.016f else 0.006f,
            shadowStrength = if (asksNoShadow) 0f else 0.62f,
        )
    }
}
