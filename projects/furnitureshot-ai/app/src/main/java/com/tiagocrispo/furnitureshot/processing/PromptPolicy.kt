package com.tiagocrispo.furnitureshot.processing

import com.tiagocrispo.furnitureshot.model.BackgroundMode
import com.tiagocrispo.furnitureshot.model.CatalogPreset
import com.tiagocrispo.furnitureshot.model.ProcessSettings

object PromptPolicy {
    val defaultPrompt = """
        Convierte esta fotografía en una foto profesional de catálogo para venta manteniendo exactamente el mismo mueble. Conserva forma, proporciones, patas, estantes, cajones, bordes, uniones, vetas, textura, color y pequeñas imperfecciones reales. No inventes, borres ni rediseñes ninguna parte. Mejora de forma natural exposición, balance de blancos, contraste local y definición sin aspecto plástico, CGI, HDR exagerado ni sobreenfoque. Aísla cuidadosamente el mueble sobre un fondo blanco de estudio uniforme, preservando huecos, patas, listones y bordes finos; evita halos, manchas blancas, recortes dentados y zonas quemadas. Mantén una sombra de contacto suave y realista debajo del mueble para que no parezca flotando. Conserva el objeto completo dentro del encuadre. Si una mejora exige alterar físicamente el producto, no la realices. El resultado debe parecer una fotografía real tomada en un estudio profesional de e-commerce.
    """.trimIndent()

    private val structuralRequests = listOf(
        "agrega una pata",
        "agregar una pata",
        "más patas",
        "mas patas",
        "quita una pata",
        "quitar una pata",
        "cambia el diseño",
        "cambiar el diseño",
        "agrega cajón",
        "agrega cajon",
        "quita cajón",
        "quita cajon",
        "hazlo más grande",
        "hazlo mas grande",
        "hazlo más chico",
        "hazlo mas chico",
        "cambia la madera",
    )

    fun interpret(prompt: String, preset: CatalogPreset): ProcessSettings {
        val normalized = prompt.lowercase()
        val protectedRequest = structuralRequests.any(normalized::contains)

        val base = when (preset) {
            CatalogPreset.QUICK_SALE -> ProcessSettings(
                backgroundMode = BackgroundMode.STUDIO_WHITE,
                brightness = 0.022f,
                contrast = 1.035f,
                warmth = 0.0f,
                shadowStrength = 0.62f,
            )
            CatalogPreset.CATALOG -> ProcessSettings(
                backgroundMode = BackgroundMode.STUDIO_WHITE,
                brightness = 0.018f,
                contrast = 1.045f,
                warmth = 0.008f,
                shadowStrength = 0.72f,
            )
            CatalogPreset.NATURAL -> ProcessSettings(
                backgroundMode = BackgroundMode.KEEP_ORIGINAL,
                brightness = 0.012f,
                contrast = 1.025f,
                warmth = 0.0f,
                shadowStrength = 0.0f,
            )
            CatalogPreset.MARKETPLACE -> ProcessSettings(
                backgroundMode = BackgroundMode.STUDIO_WHITE,
                brightness = 0.02f,
                contrast = 1.04f,
                warmth = 0.0f,
                shadowStrength = 0.66f,
            )
        }

        val asksWhite = normalized.contains("fondo blanco") || normalized.contains("blanco de estudio") || normalized.contains("blanco puro")
        val asksKeepBackground = normalized.contains("mantén el fondo") || normalized.contains("manten el fondo") || normalized.contains("fondo original")
        val asksBrighter = normalized.contains("aclara") || normalized.contains("más claro") || normalized.contains("mas claro")
        val asksNatural = normalized.contains("natural")
        val asksSoftShadow = normalized.contains("sombra suave")
        val asksNoShadow = normalized.contains("sin sombra")

        return base.copy(
            backgroundMode = when {
                asksKeepBackground -> BackgroundMode.KEEP_ORIGINAL
                asksWhite -> BackgroundMode.STUDIO_WHITE
                else -> base.backgroundMode
            },
            brightness = (base.brightness + if (asksBrighter) 0.018f else 0f).coerceAtMost(0.065f),
            contrast = if (asksNatural) minOf(base.contrast, 1.03f) else base.contrast,
            shadowStrength = when {
                asksNoShadow -> 0.0f
                asksSoftShadow -> minOf(base.shadowStrength, 0.58f)
                else -> base.shadowStrength
            },
            fidelityWarning = if (protectedRequest) {
                "Fidelity Lock ignoró una instrucción que intentaba cambiar la estructura real del mueble."
            } else {
                null
            },
        )
    }
}
