package eu.kanade.tachiyomi.network

import android.content.Context
import com.example.data.net.WebViewCookieJar
import com.example.data.parser.NetworkManager
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Suppress("unused")
class NetworkHelper(context: Context) {

    @Volatile
    var userAgentProvider: () -> String = { NetworkManager.USER_AGENT }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cookieJar(WebViewCookieJar)
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor { userAgentProvider() })
        .addInterceptor(CloudflareInterceptor())
        .build()

    fun defaultUserAgentProvider(): String = userAgentProvider()
}
