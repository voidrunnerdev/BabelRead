package eu.kanade.tachiyomi.network

import android.content.Context
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers

/**
 * Util for evaluating JavaScript in sources.
 */
@Suppress("unused")
class JavaScriptEngine(@Suppress("unused") private val context: Context) {

    suspend fun <T> evaluate(script: String): T {
        val quickJs = QuickJs.create(Dispatchers.Default)
        try {
            val result: Any? = quickJs.evaluate(script)
            @Suppress("UNCHECKED_CAST")
            return result as T
        } finally {
            runCatching { quickJs.close() }
        }
    }
}
