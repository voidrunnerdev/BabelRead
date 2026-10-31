package eu.kanade.tachiyomi.network.interceptor

import com.example.data.net.CloudflareChallengeException
import com.example.data.net.CloudflareDetector
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Detects Cloudflare challenge responses on the shared extension client and surfaces them
 * as [CloudflareChallengeException] so the app can run its WebView verification flow and
 * retry the original operation with freshly resolved cf_clearance cookies.
 */
class CloudflareInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code != 403 && response.code != 503) return response

        val mitigated = response.header("cf-mitigated").equals("challenge", ignoreCase = true)
        val fromCloudflare = mitigated ||
            response.header("Server").orEmpty().contains("cloudflare", ignoreCase = true)
        if (!fromCloudflare) return response

        val challenged = mitigated || runCatching {
            CloudflareDetector.looksLikeChallengeHtml(response.peekBody(64 * 1024).string())
        }.getOrDefault(false)
        if (!challenged) return response

        val host = request.url.host
        response.close()
        throw CloudflareChallengeException(host, request.url.toString(), "Eklenti")
    }
}
