package com.example.service

import org.json.JSONObject

data class GeminiModelInfo(
    val id: String,
    val displayName: String,
    val description: String = "",
    val inputTokenLimit: Int = 0,
    val outputTokenLimit: Int = 0,
    val supportsThinking: Boolean = false
)

object GeminiModelCatalog {
    private val nonTranslationTokens = listOf(
        "embedding",
        "imagen",
        "veo",
        "aqa",
        "tts",
        "audio",
        "robotics",
        "computer-use",
        "lyria",
        "chirp",
        "bison",
        "gecko",
        "live",
        "deep-research",
        "nano-banana",
        "learnlm",
        "vision"
    )

    private val versionRegex = Regex("(\\d+)(?:\\.(\\d+))?")

    fun parseModelsPage(jsonBody: String): Pair<List<GeminiModelInfo>, String?> {
        val root = JSONObject(jsonBody)
        val nextPageToken = root.optString("nextPageToken", "").takeIf { it.isNotBlank() }
        val array = root.optJSONArray("models")
            ?: return emptyList<GeminiModelInfo>() to nextPageToken
        val parsed = mutableListOf<GeminiModelInfo>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val methods = obj.optJSONArray("supportedGenerationMethods")
            var supportsGenerateContent = false
            if (methods != null) {
                for (m in 0 until methods.length()) {
                    if (methods.optString(m) == "generateContent") {
                        supportsGenerateContent = true
                        break
                    }
                }
            }
            if (!supportsGenerateContent) continue

            val rawName = obj.optString("name", "").trim()
            val id = rawName.removePrefix("models/").trim()
            if (id.isEmpty()) continue
            val lowerId = id.lowercase()
            if (!lowerId.startsWith("gemini-")) continue
            if (nonTranslationTokens.any { it in lowerId }) continue
            if (lowerId.contains("-image")) continue

            val displayName = obj.optString("displayName", "").trim().ifEmpty { id }
            val description = obj.optString("description", "").trim()
            val inputLimit = obj.optInt("inputTokenLimit", 0)
            val outputLimit = obj.optInt("outputTokenLimit", 0)
            val thinking = obj.optBoolean("thinking", false)
            parsed += GeminiModelInfo(
                id = id,
                displayName = displayName,
                description = description,
                inputTokenLimit = inputLimit,
                outputTokenLimit = outputLimit,
                supportsThinking = thinking
            )
        }
        return parsed to nextPageToken
    }

    fun sortForPicker(models: List<GeminiModelInfo>): List<GeminiModelInfo> {
        return models.distinctBy { it.id }.sortedWith(
            compareByDescending<GeminiModelInfo> { it.id.endsWith("-latest") }
                .thenByDescending { it.id.startsWith("gemini-") }
                .thenByDescending { versionScore(it.id) }
                .thenByDescending { it.id.contains("flash", ignoreCase = true) }
                .thenBy { it.id }
        )
    }

    fun formatTokenLimit(tokens: Int): String? {
        if (tokens <= 0) return null
        return when {
            tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
            tokens >= 1_000 -> "${tokens / 1_000}K"
            else -> tokens.toString()
        }
    }

    private fun versionScore(id: String): Int {
        val match = versionRegex.find(id) ?: return 0
        val major = match.groupValues[1].toIntOrNull() ?: 0
        val minor = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        return major * 100 + minor
    }
}
