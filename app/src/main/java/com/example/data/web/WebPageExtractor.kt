package com.example.data.web

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.net.CloudflareChallengeException
import com.example.data.net.PageRefererRegistry
import com.example.data.parser.NetworkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.resume

object WebPageExtractor {
    private val mutex = Mutex()

    suspend fun extract(context: Context, chapterUrl: String, siteOrigin: String): List<String> = mutex.withLock {
        withContext(Dispatchers.Main) {
            val webView = newWebView(context)
            try {
                if (!loadPage(webView, chapterUrl)) return@withContext emptyList()
                delay(1000)
                scrollThrough(webView, maxSteps = 80, pauseMs = 320L)

                val resultRaw = evalJs(webView, WebScripts.COLLECT_IMAGES)
                val rawUrls = WebCaptureParser.parseImages(resultRaw).map { it.url }
                val urls = WebCaptureParser.selectChapterImages(rawUrls).urls
                if (urls.isNotEmpty()) {
                    PageRefererRegistry.registerForUrls(urls, siteOrigin)
                }
                urls
            } catch (e: Exception) {
                emptyList()
            } finally {
                runCatching { webView.stopLoading() }
                runCatching { webView.destroy() }
            }
        }
    }

    /**
     * HTTP öncelikli: sayfa HTML'ini doğrudan çeker; yeterli görsel bulunursa WebView hiç açılmaz.
     * CloudflareChallengeException yutulmaz, çağırana iletilir (doğrulama diyaloğu tetiklenir).
     */
    suspend fun fetchPages(context: Context, chapterUrl: String, siteOrigin: String): List<String> {
        val viaHttp = try {
            pagesViaHttp(chapterUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudflareChallengeException) {
            throw e
        } catch (e: Exception) {
            WebCaptureParser.ChapterImageSelection(emptyList(), false)
        }
        if (viaHttp.clustered && viaHttp.urls.size >= 3) {
            PageRefererRegistry.registerForUrls(viaHttp.urls, siteOrigin)
            return viaHttp.urls
        }
        val viaWebView = extract(context, chapterUrl, siteOrigin)
        if (viaWebView.size >= 3) return viaWebView
        val fallback = if (viaWebView.size >= viaHttp.urls.size) viaWebView else viaHttp.urls
        if (fallback.isNotEmpty()) {
            PageRefererRegistry.registerForUrls(fallback, siteOrigin)
        }
        return fallback
    }

    private suspend fun pagesViaHttp(chapterUrl: String): WebCaptureParser.ChapterImageSelection =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(chapterUrl).build()
            NetworkManager.executeRequest(request, "WebChapter").use { response ->
                val html = response.body?.string().orEmpty()
                if (html.isBlank()) {
                    WebCaptureParser.ChapterImageSelection(emptyList(), false)
                } else {
                    WebCaptureParser.selectChapterImages(WebCaptureParser.parseImagesFromHtml(html, chapterUrl))
                }
            }
        }

    suspend fun capture(context: Context, pageUrl: String): String? {
        val viaHttp = try {
            captureHttp(pageUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudflareChallengeException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (viaHttp != null) {
            val parsed = WebCaptureParser.parse(viaHttp)
            if (parsed != null && (parsed.chapters.size >= 2 || parsed.series.size >= 2)) {
                return viaHttp
            }
        }
        return captureViaWebView(context, pageUrl)
    }

    /** WebView kullanmadan sayfayı HTTP ile çeker ve katalog/bölüm bilgisini çıkarır. */
    suspend fun captureHttp(pageUrl: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(pageUrl).build()
        NetworkManager.executeRequest(request, "WebCapture").use { response ->
            val html = response.body?.string()
            if (html.isNullOrBlank()) null else WebCaptureParser.captureJsonFromHtml(html, pageUrl)
        }
    }

    private suspend fun captureViaWebView(context: Context, pageUrl: String): String? = mutex.withLock {
        withContext(Dispatchers.Main) {
            val webView = newWebView(context)
            try {
                webView.settings.blockNetworkImage = true
                if (!loadPage(webView, pageUrl)) return@withContext null
                delay(900)
                scrollThrough(webView, maxSteps = 36, pauseMs = 320L)
                evalJs(webView, WebScripts.CAPTURE)
            } catch (e: Exception) {
                null
            } finally {
                runCatching { webView.stopLoading() }
                runCatching { webView.destroy() }
            }
        }
    }

    private fun newWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }

    private suspend fun loadPage(webView: WebView, url: String): Boolean {
        val loaded = CompletableDeferred<Boolean>()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!loaded.isCompleted) loaded.complete(true)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true && !loaded.isCompleted) {
                    loaded.complete(false)
                }
            }
        }
        webView.loadUrl(url)
        return withTimeoutOrNull(30_000) { loaded.await() } ?: false
    }

    private suspend fun scrollThrough(
        webView: WebView,
        maxSteps: Int,
        pauseMs: Long,
        minSteps: Int = 20,
        settleSteps: Int = 10
    ) {
        var lastCount = -1
        var stable = 0
        for (i in 0 until maxSteps) {
            val raw = evalJs(webView, WebScripts.SCROLL_STEP)
            val state = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (state != null) {
                val count = state.optInt("c", 0)
                val y = state.optDouble("y", 0.0)
                val h = state.optDouble("h", 0.0)
                val vh = state.optDouble("vh", 0.0)
                if (count == lastCount) stable++ else stable = 0
                lastCount = count
                if (h > 0 && y + vh >= h - 64 && stable >= 2) break
                // Sonsuz kaydırmalı sayfalarda en alta hiç ulaşılamıyordu; resim sayısı
                // bir süre artmıyorsa sayfa tükendi say ve erken çık.
                if (i + 1 >= minSteps && stable >= settleSteps) break
            }
            delay(pauseMs)
        }
    }

    private suspend fun evalJs(webView: WebView, script: String): String? =
        withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                try {
                    webView.evaluateJavascript(script) { value ->
                        if (cont.isActive) cont.resume(value)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
}
