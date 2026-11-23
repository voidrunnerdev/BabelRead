package com.example.data.linked

import java.net.URLDecoder
import java.net.URLEncoder

sealed class LinkedPageLocator {
    data class Document(val uri: String) : LinkedPageLocator()

    data class ArchiveEntry(val archiveUri: String, val entryName: String) : LinkedPageLocator()

    fun encode(): String = when (this) {
        is Document -> DOC_PREFIX + encodeComponent(uri)
        is ArchiveEntry -> ARC_PREFIX + encodeComponent(archiveUri) + "|" + encodeComponent(entryName)
    }

    companion object {
        private const val DOC_PREFIX = "doc|"
        private const val ARC_PREFIX = "arc|"

        fun document(uri: String): String = Document(uri).encode()

        fun archiveEntry(archiveUri: String, entryName: String): String =
            ArchiveEntry(archiveUri, entryName).encode()

        fun isEncodedLocator(value: String): Boolean =
            value.startsWith(DOC_PREFIX) || value.startsWith(ARC_PREFIX)

        fun decode(value: String): LinkedPageLocator? {
            return when {
                value.startsWith(DOC_PREFIX) -> {
                    val uri = decodeComponent(value.removePrefix(DOC_PREFIX)) ?: return null
                    if (uri.isBlank()) null else Document(uri)
                }
                value.startsWith(ARC_PREFIX) -> {
                    val rest = value.removePrefix(ARC_PREFIX)
                    val separator = rest.indexOf('|')
                    if (separator < 0) return null
                    val archiveUri = decodeComponent(rest.substring(0, separator)) ?: return null
                    val entryName = decodeComponent(rest.substring(separator + 1)) ?: return null
                    if (archiveUri.isBlank() || entryName.isBlank()) {
                        null
                    } else {
                        ArchiveEntry(archiveUri, entryName)
                    }
                }
                else -> null
            }
        }

        fun encodeList(locators: List<String>): String = locators.joinToString("\n")

        fun decodeList(value: String?): List<String> {
            if (value.isNullOrBlank()) return emptyList()
            return value.lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && isEncodedLocator(it) }
                .toList()
        }

        private fun encodeComponent(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())

        private fun decodeComponent(value: String): String? =
            runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrNull()
    }
}
