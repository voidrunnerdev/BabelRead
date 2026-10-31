package com.example.data.net

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import java.util.Locale
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
    sourceName: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
) : java.io.IOException("Cloudflare doğrulaması gerekiyor: $host ($sourceName)")

fun Headers.toSafeWebViewHeaders(): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (name in names()) {
        val value = values(name).firstOrNull() ?: continue
        val lowerName = name.lowercase(Locale.ENGLISH)
        val lowerValue = value.lowercase(Locale.ENGLISH)
        if (lowerName in unsafeWebViewHeaders) continue
        if (lowerName.startsWith("proxy-")) continue
        if (lowerName == "connection" && lowerValue == "upgrade") continue
        result[name] = value
    }
    return result
}

private val unsafeWebViewHeaders = setOf(
    "content-length",
    "host",
    "trailer",
    "te",
    "upgrade",
    "cookie",
    "cookie2",
    "keep-alive",
    "transfer-encoding",
    "set-cookie",
    "origin",
)

object CloudflareDetector {
    fun looksLikeChallengeHtml(body: String): Boolean {
        if (body.isBlank()) return false
        val lower = body.lowercase()
        return lower.contains("just a moment") ||
            lower.contains("cf-chl") ||
            lower.contains("__cf_chl") ||
            lower.contains("attention required") ||
            lower.contains("checking your browser")
    }
}

object WebViewCookieJar : CookieJar {
    fun cfClearanceFor(urlOrHost: String): String? {
        if (urlOrHost.isBlank()) return null
        return runCatching {
            for (lookupUrl in clearanceLookupUrls(urlOrHost)) {
                val header = CookieManager.getInstance().getCookie(lookupUrl) ?: continue
                val clearance = header.split(';').firstNotNullOfOrNull { part ->
                    val trimmed = part.trim()
                    if (trimmed.startsWith("cf_clearance=")) {
                        trimmed.substringAfter('=').trim().ifBlank { null }
                    } else null
                }
                if (clearance != null) return@runCatching clearance
            }
            null
        }.getOrNull()
    }

    fun clearCfClearance(urlOrHost: String) {
        if (urlOrHost.isBlank()) return
        runCatching {
            val target = normalizedHttpUrl(urlOrHost) ?: return@runCatching
            val manager = CookieManager.getInstance()
            val host = target.host
            val paths = buildSet {
                add("/")
                var path = target.path.orEmpty().trimEnd('/')
                while (path.isNotEmpty() && path != "/") {
                    add(path)
                    add("$path/")
                    path = path.substringBeforeLast('/', "")
                }
            }
            val domains = buildSet {
                add(host)
                val withoutWww = host.removePrefix("www.")
                add(withoutWww)
                val labels = withoutWww.split('.')
                if (labels.size > 2) add(labels.takeLast(2).joinToString("."))
            }
            for (scheme in listOf(target.protocol, "https").filter { it.isNotBlank() }.distinct()) {
                for (domain in domains.filter { it.isNotBlank() }) {
                    for (path in paths) {
                        val attributes = "Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path"
                        manager.setCookie("$scheme://$domain$path", "cf_clearance=; $attributes")
                        manager.setCookie("$scheme://$domain$path", "cf_clearance=; $attributes; Domain=$domain")
                        manager.setCookie("$scheme://$domain$path", "cf_clearance=; $attributes; Domain=.$domain")
                        if (domain != host) {
                            manager.setCookie("$scheme://$host$path", "cf_clearance=; $attributes; Domain=$domain")
                            manager.setCookie("$scheme://$host$path", "cf_clearance=; $attributes; Domain=.$domain")
                        }
                    }
                }
            }
            manager.flush()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        runCatching {
            val manager = CookieManager.getInstance()
            val now = System.currentTimeMillis()
            for (cookie in cookies) {
                if (cookie.expiresAt <= now) continue
                val attributes = buildString {
                    append("; Path=").append(cookie.path.ifBlank { "/" })
                    if (!cookie.hostOnly && cookie.domain.isNotBlank()) {
                        append("; Domain=").append(cookie.domain)
                    }
                    if (cookie.secure) append("; Secure")
                    if (cookie.httpOnly) append("; HttpOnly")
                }
                manager.setCookie(url.toString(), "${cookie.name}=${cookie.value}$attributes")
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

    private fun clearanceLookupUrls(urlOrHost: String): List<String> {
        val target = normalizedHttpUrl(urlOrHost) ?: return emptyList()
        return listOf(target.toString(), "${target.protocol}://${target.host}/").distinct()
    }

    private fun normalizedHttpUrl(value: String): java.net.URL? {
        val raw = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
        return runCatching { java.net.URL(raw) }
            .getOrNull()
            ?.takeIf { it.host.orEmpty().isNotBlank() }
    }
}
