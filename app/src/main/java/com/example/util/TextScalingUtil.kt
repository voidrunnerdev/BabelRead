package com.example.util

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

object TextScalingUtil {

    data class ScaledTextResult(
        val formattedText: String,
        val fontSize: TextUnit,
        val maxLines: Int
    )

    fun calculateBoundingBoxArea(width: Float, height: Float): Float {
        return width * height
    }

    fun calculateScaledText(
        text: String,
        widthDp: Float,
        heightDp: Float,
        minSp: Float = 7.0f,
        maxSp: Float = 12.0f
    ): ScaledTextResult {
        val clean = text.replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
        val area = widthDp * heightDp

        // Scale font size according to bubble area
        val calculatedSp = (minSp + (area / 1200f) * 1.5f).coerceIn(minSp, maxSp)
        val estimatedLines = max(1, (heightDp / (calculatedSp * 1.3f)).toInt())

        return ScaledTextResult(
            formattedText = clean,
            fontSize = calculatedSp.sp,
            maxLines = estimatedLines
        )
    }
}
