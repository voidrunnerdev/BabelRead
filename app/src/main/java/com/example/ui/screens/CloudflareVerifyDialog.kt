package com.example.ui.screens

import android.webkit.CookieManager
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
import com.example.data.parser.NetworkManager
import com.example.i18n.Text
import com.example.ui.theme.AppRadius
import com.example.ui.theme.AppSpacing
import com.example.viewmodel.MangaViewModel

@Composable
fun CloudflareVerifyDialog(
    challenge: MangaViewModel.CfChallenge,
    onVerified: () -> Unit,
    onCancel: () -> Unit
) {
    val cookieTarget = challenge.url.ifBlank { challenge.host }
    val initialClearance = remember(cookieTarget) {
        WebViewCookieJar.cfClearanceFor(cookieTarget)
    }
    var manualError by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = { },
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
                        text = "Site bot koruması istiyor. Doğrulama çoğu zaman otomatik tamamlanır; gerekirse aşağıdaki kutucuğu işaretleyip 'Doğruladım' düğmesine dokunun.",
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
                    key(challenge.host, challenge.url) {
                        val released = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.loadsImagesAutomatically = true
                                    settings.userAgentString = NetworkManager.USER_AGENT
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    WebViewCookieJar.clearCfClearance(cookieTarget)
                                    webViewClient = object : WebViewClient() {
                                        private var verified = false
                                        private var checks = 0

                                        private fun isChallengedTitle(title: String): Boolean {
                                            val lower = title.lowercase()
                                            return lower.contains("just a moment") ||
                                                lower.contains("bir dakika") ||
                                                lower.contains("attention required") ||
                                                lower.contains("lütfen bekleyin") ||
                                                lower.contains("checking your browser") ||
                                                lower.contains("tarayıcınız kontrol ediliyor")
                                        }

                                        private fun checkVerified(view: WebView) {
                                            if (released.get() || verified) return
                                            CookieManager.getInstance().flush()
                                            val clearance = WebViewCookieJar.cfClearanceFor(cookieTarget)
                                            val changed = clearance != null && clearance != initialClearance
                                            if (changed && !isChallengedTitle(view.title.orEmpty())) {
                                                verified = true
                                                onVerified()
                                            }
                                        }

                                        private fun scheduleCheck(view: WebView) {
                                            view.postDelayed({
                                                if (!released.get() && !verified) {
                                                    checkVerified(view)
                                                    if (!verified && checks < 24) {
                                                        checks++
                                                        scheduleCheck(view)
                                                    }
                                                }
                                            }, 1000)
                                        }

                                        override fun onPageFinished(view: WebView, url: String?) {
                                            if (released.get()) return
                                            checkVerified(view)
                                            scheduleCheck(view)
                                        }
                                    }
                                    loadUrl(challenge.url.ifBlank { "https://${challenge.host}/" })
                                }
                            },
                            onRelease = { webView ->
                                released.set(true)
                                webView.stopLoading()
                                webView.destroy()
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
                                onVerified()
                            } else {
                                manualError = "Doğrulama henüz algılanmadı. Kutucuğu işaretleyip birkaç saniye bekleyin."
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
