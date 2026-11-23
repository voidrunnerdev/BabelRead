package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.data.web.WebSite
import com.example.i18n.Text
import com.example.ui.theme.AppSpacing
import com.example.viewmodel.MangaViewModel

@Composable
fun AddWebSiteDialog(
    viewModel: MangaViewModel,
    onDismiss: () -> Unit,
    onAdded: (WebSite) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val normalized = remember(url) { viewModel.normalizeWebUrl(url) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Web Sitesi Ekle",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                Text(
                    "Manga okumak istediğiniz sitenin adresini girin. Site eklenince manga listesi tarayıcı açılmadan otomatik toplanır; listede daha fazla manga için katalog adresi verebilirsiniz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(AppSpacing.md))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Site adresi (ör. mangasite.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Görünen ad (isteğe bağlı)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (url.isNotBlank() && normalized == null) {
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(
                        "Geçerli bir site adresi girin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val site = viewModel.addWebSite(url, name)
                    if (site != null) onAdded(site)
                },
                enabled = normalized != null
            ) {
                Text("Kaydet")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "İptal",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
fun ManageWebSitesDialog(
    webSites: List<WebSite>,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Web Siteleri",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            if (webSites.isEmpty()) {
                Text(
                    "Henüz web sitesi eklenmedi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    webSites.forEach { site ->
                        SurfaceCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = AppSpacing.xs),
                            contentPadding = PaddingValues(
                                horizontal = AppSpacing.md,
                                vertical = AppSpacing.sm
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        site.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        site.origin,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onRemove(site.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Sil",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Kapat")
            }
        }
    )
}
