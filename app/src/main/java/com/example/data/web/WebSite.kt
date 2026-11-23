package com.example.data.web

import com.example.data.pref.MangaPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class WebSite(
    val id: String,
    val name: String,
    val origin: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("origin", origin)

    companion object {
        fun fromJson(obj: JSONObject): WebSite? {
            val origin = obj.optString("origin", "")
            val id = obj.optString("id", "").ifBlank { origin }
            val name = obj.optString("name", "")
            if (id.isBlank() || origin.isBlank() || name.isBlank()) return null
            return WebSite(id, name, origin)
        }

        fun parseList(raw: String?): List<WebSite> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { fromJson(it) }
                }
            }.getOrDefault(emptyList())
        }

        fun encodeList(sites: List<WebSite>): String {
            val array = JSONArray()
            for (site in sites) array.put(site.toJson())
            return array.toString()
        }
    }
}

class WebSiteStore(private val preferences: MangaPreferences, scope: CoroutineScope) {
    private val _sites = MutableStateFlow<List<WebSite>>(emptyList())
    val sites: StateFlow<List<WebSite>> = _sites.asStateFlow()

    init {
        scope.launch {
            preferences.webSites.collect { list ->
                _sites.value = list
            }
        }
    }

    fun siteById(id: String): WebSite? = _sites.value.firstOrNull { it.id == id }

    fun siteByName(name: String): WebSite? =
        _sites.value.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun siteBySourceName(sourceName: String): WebSite? =
        siteByName(sourceName.removePrefix("Web: "))

    fun uniqueName(base: String): String {
        val existing = _sites.value.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in existing) return base
        var counter = 2
        while ("${base.lowercase()} ($counter)" in existing) counter++
        return "$base ($counter)"
    }

    suspend fun add(site: WebSite) {
        val current = _sites.value.filter { it.id != site.id }
        preferences.setWebSites((current + site).sortedBy { it.name.lowercase() })
    }

    suspend fun remove(id: String) {
        preferences.setWebSites(_sites.value.filter { it.id != id })
    }
}
