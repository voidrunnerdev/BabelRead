package com.example.ui.screens

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.net.WebViewCookieJar
import com.example.data.net.WebViewIdentity
import com.example.data.parser.NetworkManager
import com.example.i18n.Text
import com.example.ui.theme.AppRadius
import com.example.ui.theme.AppSpacing
import com.example.viewmodel.MangaViewModel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CloudflareVerifyDialog(
    challenge: MangaViewModel.CfChallenge,
    onVerified: () -> Unit,
    onCancel: () -> Unit
) {
    val cookieTarget = challenge.url.ifBlank { challenge.host }
    val targetUrl = remember(challenge.host, challenge.url, challenge.method) {
        challengeTargetUrl(challenge)
    }
    val userAgent = remember(challenge.headers) {
        challenge.headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: NetworkManager.USER_AGENT
    }
    val initialClearance = remember(cookieTarget) {
        WebViewCookieJar.cfClearanceFor(cookieTarget)
    }
    var manualError by remember(challenge.host, challenge.url, challenge.method) {
        mutableStateOf<String?>(null)
    }
    val verifiedOnce = remember(challenge.host, challenge.url, challenge.method) {
        java.util.concurrent.atomic.AtomicBoolean(false)
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(AppRadius.lg),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(AppSpacing.lg)) {
                    Text(
                        text = "Cloudflare Doğrulaması",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    Text(
                        text = challenge.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(
                        text = "Site bot koruması istiyor. Doğrulama çoğu zaman otomatik tamamlanır; gerekirse sayfadaki kontrolü tamamlayıp 'Doğruladım' düğmesine dokunun.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.md)
                        .clip(RoundedCornerShape(AppRadius.sm))
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    key(challenge.host, challenge.url, challenge.method) {
                        val released = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    settings.loadsImagesAutomatically = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                                    settings.setSupportMultipleWindows(false)
                                    settings.setSupportZoom(true)
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    WebViewIdentity.apply(this, userAgent)
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    WebViewCookieJar.clearCfClearance(cookieTarget)
                                    webChromeClient = WebChromeClient()
                                    webViewClient = object : WebViewClient() {
                                        private var checks = 0
                                        private var pollingScheduled = false

                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean {
                                            val scheme = request?.url?.scheme?.lowercase()
                                            return scheme != "http" && scheme != "https"
                                        }

                                        private fun checkVerified() {
                                            if (released.get() || verifiedOnce.get()) return
                                            CookieManager.getInstance().flush()
                                            val clearance = WebViewCookieJar.cfClearanceFor(cookieTarget)
                                            if (clearance != null && clearance != initialClearance) {
                                                if (verifiedOnce.compareAndSet(false, true)) {
                                                    onVerified()
                                                }
                                            }
                                        }

                                        private fun scheduleCheck() {
                                            if (pollingScheduled || released.get() || verifiedOnce.get()) return
                                            pollingScheduled = true
                                            val tick = object : Runnable {
                                                override fun run() {
                                                    if (released.get() || verifiedOnce.get()) {
                                                        pollingScheduled = false
                                                        return
                                                    }
                                                    checkVerified()
                                                    if (!verifiedOnce.get() && checks < 24) {
                                                        checks++
                                                        mainHandler.postDelayed(this, 1000)
                                                    } else {
                                                        pollingScheduled = false
                                                    }
                                                }
                                            }
                                            mainHandler.postDelayed(tick, 1000)
                                        }

                                        override fun onPageFinished(view: WebView, url: String?) {
                                            if (released.get()) return
                                            checks = 0
                                            checkVerified()
                                            scheduleCheck()
                                        }
                                    }
                                    if (targetUrl.startsWith("https://", ignoreCase = true) ||
                                        targetUrl.startsWith("http://", ignoreCase = true)
                                    ) {
                                        loadUrl(targetUrl, challenge.headers)
                                    }
                                }
                            },
                            onRelease = { webView ->
                                released.set(true)
                                mainHandler.removeCallbacksAndMessages(null)
                                runCatching {
                                    webView.stopLoading()
                                    webView.webChromeClient = null
                                    webView.webViewClient = WebViewClient()
                                    webView.loadUrl("about:blank")
                                    webView.removeAllViews()
                                    webView.destroy()
                                }
                            }
                        )
                    }
                }
                manualError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = AppSpacing.lg)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AppSpacing.lg),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel) {
                        Text("İptal", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Button(
                        onClick = {
                            CookieManager.getInstance().flush()
                            val clearance = WebViewCookieJar.cfClearanceFor(cookieTarget)
                            if (clearance != null && clearance != initialClearance) {
                                manualError = null
                                if (verifiedOnce.compareAndSet(false, true)) {
                                    onVerified()
                                }
                            } else {
                                manualError = "Doğrulama henüz algılanmadı. Sayfadaki kontrolü tamamlayıp birkaç saniye bekleyin."
                            }
                        }
                    ) {
                        Text("Doğruladım, Devam Et")
                    }
                }
            }
        }
    }
}

private fun challengeTargetUrl(challenge: MangaViewModel.CfChallenge): String {
    if (challenge.method.equals("GET", ignoreCase = true)) {
        return challenge.url.ifBlank { "https://${challenge.host}/" }
    }
    return challenge.url.toHttpUrlOrNull()
        ?.newBuilder()
        ?.encodedPath("/")
        ?.query(null)
        ?.fragment(null)
        ?.build()
        ?.toString()
        ?: "https://${challenge.host}/"
}
