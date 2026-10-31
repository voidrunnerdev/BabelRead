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
                                WebViewCookieJar.clearCfClearance(challenge.host)
                                webViewClient = object : WebViewClient() {
                                    private var verified = false

                                    private fun isChallengedTitle(title: String): Boolean {
                                        val lower = title.lowercase()
                                        return lower.contains("just a moment") ||
                                            lower.contains("bir dakika") ||
                                            lower.contains("attention required") ||
                                            lower.contains("lütfen bekleyin")
                                    }

                                    private fun checkVerified(view: WebView) {
                                        if (verified) return
                                        CookieManager.getInstance().flush()
                                        val clearance = WebViewCookieJar.cfClearanceFor(challenge.host)
                                        if (!isChallengedTitle(view.title.orEmpty()) && !clearance.isNullOrBlank()) {
                                            verified = true
                                            onVerified()
                                        }
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        checkVerified(view)
                                        view.postDelayed({ checkVerified(view) }, 1500)
                                    }
                                }
                                loadUrl(challenge.url.ifBlank { "https://${challenge.host}/" })
                            }
                        }
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
                            onVerified()
                        }
                    ) {
                        Text("Doğruladım, Devam Et")
                    }
                }
            }
        }
    }
}
