package com.example.data.net

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

object WebViewIdentity {
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"

    private val androidToken = Regex("; Android .*?\\)")
    private val versionToken = Regex("Version/.* Chrome/")
    private val chromeVersion = Regex("""Chrome/(\d+)(\.[\d.]+)?""")

    fun infer(context: Context): String {
        return runCatching {
            WebSettings.getDefaultUserAgent(context)
                .replace(androidToken, "; Android 10; K)")
                .replace(versionToken, "Chrome/")
        }.getOrNull()?.takeIf { it.contains("Chrome/") } ?: DEFAULT_USER_AGENT
    }

    fun apply(webView: WebView, userAgent: String) {
        webView.settings.userAgentString = userAgent
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return

        val match = chromeVersion.find(userAgent) ?: return
        val majorVersion = match.groupValues[1]
        val fullVersion = majorVersion + match.groupValues[2].ifEmpty { ".0.0.0" }

        runCatching {
            val metadata = WebSettingsCompat.getUserAgentMetadata(webView.settings)
            val brands = metadata.brandVersionList.map { brandVersion ->
                val brand = when (brandVersion.brand) {
                    "Android WebView" -> "Google Chrome"
                    else -> brandVersion.brand
                }
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand(brand)
                    .setMajorVersion(majorVersion)
                    .setFullVersion(fullVersion)
                    .build()
            }
            WebSettingsCompat.setUserAgentMetadata(
                webView.settings,
                UserAgentMetadata.Builder(metadata)
                    .setBrandVersionList(brands)
                    .setFullVersion(fullVersion)
                    .setMobile(userAgent.contains("Mobile"))
                    .build()
            )
        }
    }
}
