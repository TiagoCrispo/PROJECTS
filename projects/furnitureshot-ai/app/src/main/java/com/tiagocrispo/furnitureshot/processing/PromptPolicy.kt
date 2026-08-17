package com.tiagocrispo.furnitureshot.processing

import com.tiagocrispo.furnitureshot.model.CatalogPreset
import com.tiagocrispo.furnitureshot.model.ProcessSettings

object PromptPolicy {
    val defaultPrompt = """
        Edita esta foto como una fotografía profesional de catálogo para venta. Conserva estrictamente el mismo mueble: forma, proporciones, patas, estantes, cajones, bordes, uniones, vetas, textura, color y pequeñas imperfecciones reales. Mejora de forma natural la exposición, balance de blancos, contraste y definición sin aspecto plástico, CGI ni HDR exagerado. Aísla cuidadosamente el producto sobre un fondo blanco de estudio, sin halos ni piezas recortadas. Mantén una sombra de contacto suave y realista. Centra el mueble, conserva todo el objeto dentro del encuadre y nunca inventes detalles. Si una mejora exige alterar físicamente el producto, no la realices. El resultado debe parecer una fotografía real tomada por un fotógrafo profesional.
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
                whiteBackground = true,
                brightness = 0.035f,
                contrast = 1.04f,
                warmth = 0.0f,
            )
            CatalogPreset.CATALOG -> ProcessSettings(
                whiteBackground = true,
                brightness = 0.025f,
                contrast = 1.06f,
                warmth = 0.01f,
            )
            CatalogPreset.NATURAL -> ProcessSettings(
                whiteBackground = false,
                brightness = 0.015f,
                contrast = 1.025f,
                warmth = 0.0f,
            )
            CatalogPreset.MARKETPLACE -> ProcessSettings(
                whiteBackground = true,
                brightness = 0.03f,
                contrast = 1.05f,
                warmth = 0.0f,
            )
        }

        val asksWhite = normalized.contains("fondo blanco") || normalized.contains("blanco de estudio")
        val asksKeepBackground = normalized.contains("mantén el fondo") || normalized.contains("manten el fondo")
        val asksBrighter = normalized.contains("aclara") || normalized.contains("más claro") || normalized.contains("mas claro")
        val asksNatural = normalized.contains("natural")

        return base.copy(
            whiteBackground = when {
                asksKeepBackground -> false
                asksWhite -> true
                else -> base.whiteBackground
            },
            brightness = (base.brightness + if (asksBrighter) 0.025f else 0f).coerceAtMost(0.08f),
            contrast = if (asksNatural) minOf(base.contrast, 1.035f) else base.contrast,
            fidelityWarning = if (protectedRequest) {
                "Fidelity Lock ignoró una instrucción que intentaba cambiar la estructura real del mueble."
            } else {
                null
            },
        )
    }
}
