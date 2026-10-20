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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.net.WebViewCookieJar
import com.example.data.parser.NetworkManager
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
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E1E)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Cloudflare Doğrulaması",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = challenge.host,
                        color = Color(0xFF9E9E9E),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Site bot koruması istiyor. Doğrulama çoğu zaman otomatik tamamlanır; gerekirse aşağıdaki kutucuğu işaretleyip 'Doğruladım' düğmesine dokunun.",
                        color = Color(0xFFBDBDBD),
                        fontSize = 12.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0E0E0E))
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
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel) {
                        Text("İptal", color = Color(0xFF9E9E9E))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            CookieManager.getInstance().flush()
                            onVerified()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Doğruladım, Devam Et")
                    }
                }
            }
        }
    }
}
