package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class UserAgentInterceptor(private val userAgentProvider: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (!originalRequest.header("User-Agent").isNullOrEmpty()) {
            return chain.proceed(originalRequest)
        }
        val request = originalRequest.newBuilder()
            .header("User-Agent", userAgentProvider())
            .build()
        return chain.proceed(request)
    }
}
