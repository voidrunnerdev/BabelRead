package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class UncaughtExceptionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        try {
            return chain.proceed(chain.request())
        } catch (e: Throwable) {
            if (call.isCanceled()) {
                throw IOException("Canceled", e)
            }
            throw e
        }
    }
}
