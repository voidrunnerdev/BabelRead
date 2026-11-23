package com.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModelCatalogTest {

    @Test
    fun parseModelsPage_filtersGenerateContentTextModelsAndStripsPrefix() {
        val json = """
            {
              "models": [
                {
                  "name": "models/gemini-2.5-flash",
                  "displayName": "Gemini 2.5 Flash",
                  "inputTokenLimit": 1048576,
                  "outputTokenLimit": 65536,
                  "thinking": true,
                  "supportedGenerationMethods": ["generateContent", "countTokens"]
                },
                {
                  "name": "models/text-embedding-004",
                  "displayName": "Text Embedding 004",
                  "supportedGenerationMethods": ["embedContent"]
                },
                {
                  "name": "models/gemini-2.5-flash-image",
                  "displayName": "Gemini 2.5 Flash Image",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "models/gemini-2.5-flash-live",
                  "displayName": "Gemini 2.5 Flash Live",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "models/gemini-deep-research-pro",
                  "displayName": "Gemini Deep Research",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "models/gemma-3-27b-it",
                  "displayName": "Gemma 3 27B",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "models/gemini-flash-latest",
                  "displayName": "Gemini Flash Latest",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "models/gemini-3.0-pro",
                  "displayName": "Gemini 3.0 Pro",
                  "supportedGenerationMethods": ["generateContent"]
                }
              ],
              "nextPageToken": "tok_next"
            }
        """.trimIndent()

        val (models, nextToken) = GeminiModelCatalog.parseModelsPage(json)
        assertEquals("tok_next", nextToken)
        assertEquals(3, models.size)
        val sorted = GeminiModelCatalog.sortForPicker(models)
        assertEquals("gemini-flash-latest", sorted[0].id)
        assertEquals("gemini-3.0-pro", sorted[1].id)
        assertEquals("gemini-2.5-flash", sorted[2].id)
        assertTrue(sorted[2].supportsThinking)
        assertEquals("1M", GeminiModelCatalog.formatTokenLimit(sorted[2].inputTokenLimit))
        assertNull(GeminiModelCatalog.formatTokenLimit(0))
    }
}
