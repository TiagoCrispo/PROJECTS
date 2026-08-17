package com.tiagocrispo.furnitureshot.model

enum class CatalogPreset(val title: String) {
    QUICK_SALE("Venta rápida"),
    CATALOG("Catálogo"),
    NATURAL("Natural"),
    MARKETPLACE("Marketplace"),
}

enum class PreviewMode {
    ORIGINAL,
    RESULT,
}

data class ProcessSettings(
    val whiteBackground: Boolean,
    val brightness: Float,
    val contrast: Float,
    val warmth: Float,
    val fidelityWarning: String? = null,
)

data class ProcessResult(
    val resultPath: String,
    val backgroundReplaced: Boolean,
    val warning: String? = null,
)

data class HistoryItem(
    val id: String,
    val originalPath: String,
    val resultPath: String,
    val createdAt: Long,
)
