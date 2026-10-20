package eu.kanade.tachiyomi.network

import okhttp3.Response

/**
 * Exception that handles HTTP codes considered not successful by OkHttp.
 */
class HttpException(val code: Int) : IllegalStateException("HTTP error $code")
