package com.example.data.db

import org.json.JSONArray

/** Stores new page lists as JSON while remaining able to read legacy comma separated rows. */
object PagePathCodec {
    fun encode(paths: List<String>): String = JSONArray(paths).toString()

    fun decode(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[")) {
            runCatching {
                val array = JSONArray(trimmed)
                return (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotBlank() }
                }
            }
        }
        return trimmed.split(',').map(String::trim).filter(String::isNotEmpty)
    }
}
