package com.tiagocrispo.furnitureshot.model

data class ProcessSettings(
    val brightness: Float,
    val contrast: Float,
    val warmth: Float,
    val shadowStrength: Float,
    val saturation: Float,
    val detailStrength: Float,
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

data class SellerShotItem(
    val id: String,
    val originalPath: String,
    val resultPath: String? = null,
    val viewLabel: String? = null,
    val sortRank: Int = 999,
)
