package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import uy.kohesive.injekt.injectLazy

/**
 * A simple implementation for sources from a website.
 */
@Suppress("unused")
abstract class HttpSource : CatalogueSource {

    /**
     * Network service.
     */
    protected val network: NetworkHelper by injectLazy()

    /**
     * Base url of the website without the trailing slash, like: http://mysite.com
     */
    abstract val baseUrl: String

    open fun getHomeUrl(): String = baseUrl

    /**
     * Version id used to generate the source id.
     */
    open val versionId: Int = 1

    /**
     * Id of the source. By default it uses the first 16 characters (64 bits)
     * of the MD5 of the string: sourcename/language/versionId
     */
    override val id: Long by lazy { generateId(name, lang, versionId) }

    /**
     * Headers used for requests.
     */
    val headers: Headers by lazy { headersBuilder().build() }

    /**
     * Default network client for doing requests.
     */
    open val client: OkHttpClient by lazy { network.client }

    /**
     * Headers builder for requests. Implementations can override this method for custom headers.
     */
    protected open fun headersBuilder(): Headers.Builder =
        Headers.Builder().add("User-Agent", network.defaultUserAgentProvider())

    override fun toString(): String = "$name (${lang.uppercase()})"

    /**
     * Returns the image url for the provided [page]. Called only if [Page.imageUrl] is null.
     */
    open suspend fun getImageUrl(page: Page): String =
        throw UnsupportedOperationException("getImageUrl is not implemented")

    /**
     * Returns the request for getting the source image.
     */
    protected open fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headers)

    /**
     * Fetches the image for the given [page]. Used by the host app.
     */
    suspend fun getImage(page: Page): Response {
        if (page.imageUrl == null) {
            page.imageUrl = getImageUrl(page)
        }
        return client.newCall(imageRequest(page)).await()
    }

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        val uri = orig.toHttpUrlOrNull() ?: return orig
        return uri.encodedPath + if (uri.query != null) "?" + uri.query else ""
    }

    abstract fun getMangaUrl(manga: SManga): String

    abstract fun getChapterUrl(chapter: SChapter): String

    private fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "$name/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[i].toLong() and 0xff)
        }
        return value and 0x7fff_ffff_ffff_ffffL
    }
}
