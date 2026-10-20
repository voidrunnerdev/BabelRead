package com.example.data.net

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

object PageRefererRegistry {
    private val hostReferers = ConcurrentHashMap<String, String>()

    fun registerForUrls(pageUrls: Collection<String>, referer: String) {
        for (url in pageUrls) {
            val host = runCatching { java.net.URL(url).host }.getOrNull() ?: continue
            if (host.isNotBlank()) hostReferers[host] = referer
        }
    }

    fun registerForHost(host: String, referer: String) {
        if (host.isNotBlank()) hostReferers[host] = referer
    }

    fun refererFor(url: String): String? {
        val host = runCatching { java.net.URL(url).host }.getOrNull() ?: return null
        if (host.isBlank()) return null
        hostReferers[host]?.let { return it }
        return when {
            host.contains("mfcdn") -> "https://mangafire.to/"
            host.contains("comick.pictures") -> "https://comick.io/"
            else -> null
        }
    }
}

class CloudflareChallengeException(
    val host: String,
    val challengedUrl: String,
    sourceName: String
) : java.io.IOException("Cloudflare doğrulaması gerekiyor: $host ($sourceName)")

object CloudflareDetector {
    fun looksLikeChallengeHtml(body: String): Boolean {
        if (body.isBlank()) return false
        val lower = body.lowercase()
        return lower.contains("just a moment") ||
            lower.contains("cf-chl") ||
            lower.contains("challenge-platform") ||
            lower.contains("__cf_chl") ||
            lower.contains("attention required")
    }
}

object WebViewCookieJar : CookieJar {
    fun cfClearanceFor(host: String): String? {
        if (host.isBlank()) return null
        return runCatching {
            val header = CookieManager.getInstance().getCookie("https://$host/") ?: return null
            header.split(';').firstNotNullOfOrNull { part ->
                val trimmed = part.trim()
                if (trimmed.startsWith("cf_clearance=")) {
                    trimmed.substringAfter('=').trim().ifBlank { null }
                } else null
            }
        }.getOrNull()
    }

    fun clearCfClearance(host: String) {
        if (host.isBlank()) return
        runCatching {
            val manager = CookieManager.getInstance()
            manager.setCookie("https://$host/", "cf_clearance=; Max-Age=0; Path=/")
            manager.flush()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        runCatching {
            val manager = CookieManager.getInstance()
            for (cookie in cookies) {
                val value = "${cookie.name}=${cookie.value}; path=${cookie.path}"
                manager.setCookie(url.toString(), value)
            }
            manager.flush()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return runCatching {
            val header = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
            header.split(';').mapNotNull { part ->
                val trimmed = part.trim()
                val idx = trimmed.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val name = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                if (name.isEmpty()) return@mapNotNull null
                runCatching {
                    Cookie.Builder()
                        .name(name)
                        .value(value)
                        .hostOnlyDomain(url.host)
                        .path("/")
                        .build()
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
