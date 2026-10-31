package com.example.data.parser

import android.content.Context
import com.example.data.net.CloudflareChallengeException
import com.example.data.net.CloudflareDetector
import com.example.data.net.PageRefererRegistry
import com.example.data.net.WebViewCookieJar
import com.example.data.net.WebViewIdentity
import com.example.data.net.toSafeWebViewHeaders
import eu.kanade.tachiyomi.source.model.FilterList
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class MangaModel(
    val id: String,
    val title: String,
    val coverUrl: String,
    val author: String,
    val artist: String,
    val description: String,
    val status: String,
    val rating: Double,
    val sourceName: String,
    val genres: List<String>,
    val originLanguage: String,
    val availableLanguages: List<String> = emptyList()
)

data class ChapterModel(
    val id: String,
    val mangaId: String,
    val title: String,
    val chapterNumber: Double,
    val uploadDate: String,
    val language: String = "tr"
)

interface MangaSource {
    val name: String

    fun getFilterList(): FilterList = FilterList()

    suspend fun getMangas(
        page: Int = 1,
        tab: String = "Popüler",
        language: String? = null,
        genre: String? = null,
        status: String? = null,
        filters: FilterList = FilterList()
    ): List<MangaModel>

    suspend fun searchMangas(
        query: String,
        page: Int = 1,
        language: String? = null,
        genre: String? = null,
        filters: FilterList = FilterList()
    ): List<MangaModel>

    suspend fun getMangaDetail(mangaId: String): Pair<MangaModel, List<ChapterModel>>

    suspend fun getChapterPages(chapterId: String): List<String>
}

object NetworkManager {
    @Volatile
    var USER_AGENT: String = WebViewIdentity.DEFAULT_USER_AGENT
        private set

    fun initialize(context: Context) {
        USER_AGENT = WebViewIdentity.infer(context)
    }

    private class HostGate {
        val semaphore = java.util.concurrent.Semaphore(3)
        val lastStart = java.util.concurrent.atomic.AtomicLong(0L)
    }

    private val hostGates = java.util.concurrent.ConcurrentHashMap<String, HostGate>()

    private const val MIN_HOST_INTERVAL_MS = 120L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .cookieJar(WebViewCookieJar)
            .addInterceptor { chain ->
                val request = chain.request()
                val requestBuilder = request.newBuilder()
                if (request.header("User-Agent").isNullOrEmpty()) {
                    requestBuilder.header("User-Agent", USER_AGENT)
                }
                if (request.header("Referer") == null) {
                    val referer = PageRefererRegistry.refererFor(request.url.toString())
                        ?: "https://${request.url.host}/"
                    requestBuilder.header("Referer", referer)
                }
                chain.proceed(requestBuilder.build())
            }
            .addInterceptor { chain ->
                val request = chain.request()
                val gate = hostGates.getOrPut(request.url.host) { HostGate() }
                gate.semaphore.acquire()
                try {
                    val wait = MIN_HOST_INTERVAL_MS - (System.currentTimeMillis() - gate.lastStart.get())
                    if (wait in 1..MIN_HOST_INTERVAL_MS) {
                        Thread.sleep(wait)
                    }
                    gate.lastStart.set(System.currentTimeMillis())
                    chain.proceed(request)
                } finally {
                    gate.semaphore.release()
                }
            }
            .build()
    }

    fun isCloudflareChallenge(response: okhttp3.Response): Boolean {
        if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) return true
        val server = response.header("Server").orEmpty()
        if (!server.contains("cloudflare", ignoreCase = true)) return false
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.isNotBlank() && !contentType.contains("text/html", ignoreCase = true)) return false
        val bodyText = runCatching { response.peekBody(16384).string() }.getOrDefault("")
        return CloudflareDetector.looksLikeChallengeHtml(bodyText)
    }

    suspend fun executeRequest(request: Request, sourceName: String = "Common"): okhttp3.Response {
        var attempt = 1
        var delayMs = 800L
        val maxAttempts = 3
        while (true) {
            try {
                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                val challenge = withContext(Dispatchers.IO) { isCloudflareChallenge(response) }
                if (challenge) {
                    val host = request.url.host
                    val challengedUrl = request.url.toString()
                    response.close()
                    throw CloudflareChallengeException(
                        host,
                        challengedUrl,
                        sourceName,
                        request.method,
                        request.headers.toSafeWebViewHeaders(),
                    )
                }
                if (response.isSuccessful) {
                    return response
                }
                response.close()
                if (attempt >= maxAttempts) {
                    throw IOException("HTTP istek başarısız ($sourceName): ${response.code}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareChallengeException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= maxAttempts) {
                    throw IOException("Bağlantı hatası ($sourceName): ${e.message}", e)
                }
            }
            kotlinx.coroutines.delay(delayMs)
            delayMs *= 2
            attempt++
        }
    }

    suspend fun downloadPageStream(url: String, referer: String? = null): java.io.InputStream? {
        if (url.startsWith("extpg:")) {
            return try {
                val bytes = com.example.extensions.ExtensionPageFetcher.fetch(url)
                    ?: throw IOException("Eklenti sayfası bulunamadı")
                java.io.ByteArrayInputStream(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        val reqBuilder = Request.Builder().url(url)
        val effectiveReferer = referer
            ?: PageRefererRegistry.refererFor(url)
            ?: run {
                val host = try { java.net.URL(url).host } catch (e: Exception) { null }
                if (host != null) "https://$host/" else null
            }
        if (effectiveReferer != null) {
            reqBuilder.header("Referer", effectiveReferer)
        }
        return try {
            val response = executeRequest(reqBuilder.build(), "PageDownloader")
            val body = response.body
            if (body == null) {
                response.close()
                null
            } else {
                body.byteStream()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudflareChallengeException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

object MangaSourceRegistry {
    var webSourceResolver: ((String) -> MangaSource?)? = null

    private val builtInSources: List<MangaSource> = listOf(
        LocalDeviceSource()
    )

    @Volatile
    private var extensionSources: List<MangaSource> = emptyList()

    val sources: List<MangaSource>
        get() = if (extensionSources.isEmpty()) builtInSources else builtInSources + extensionSources

    fun setExtensionSources(list: List<MangaSource>) {
        extensionSources = list
    }

    fun getSource(name: String): MangaSource {
        sources.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        if (name.startsWith("Web: ")) {
            val resolved = webSourceResolver?.invoke(name)
            if (resolved != null) return resolved
        }
        throw IOException("Bilinmeyen kaynak: $name. Kütüphanedeki kaynak güncel olmayabilir.")
    }
}

// -------------------------------------------------------------
// 1. CİHAZ / YEREL MANGA KAYNAĞI (CBZ, ZIP, RESİMLER)
// -------------------------------------------------------------
class LocalDeviceSource : MangaSource {
    override val name = "Cihaz / Yerel Mangalar"

    override suspend fun getMangas(
        page: Int,
        tab: String,
        language: String?,
        genre: String?,
        status: String?,
        filters: FilterList
    ): List<MangaModel> = withContext(Dispatchers.IO) {
        emptyList()
    }

    override suspend fun searchMangas(
        query: String,
        page: Int,
        language: String?,
        genre: String?,
        filters: FilterList
    ): List<MangaModel> = withContext(Dispatchers.IO) {
        emptyList()
    }

    override suspend fun getMangaDetail(mangaId: String): Pair<MangaModel, List<ChapterModel>> = withContext(Dispatchers.IO) {
        throw IOException("Yerel mangalar doğrudan Kütüphane ekranınızdan açılabilir.")
    }

    override suspend fun getChapterPages(chapterId: String): List<String> = withContext(Dispatchers.IO) {
        emptyList()
    }
}
