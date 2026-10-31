package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.extensions.ExtensionManager
import com.example.extensions.languageLabel
import com.example.i18n.Text
import com.example.ui.theme.AppSizes
import com.example.ui.theme.AppSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ExtensionsSection(onOpenStore: () -> Unit) {
    val installed by ExtensionManager.installed.collectAsState()
    val enabledSourceIds by ExtensionManager.enabledSourceIds.collectAsState()
    val loadErrors by ExtensionManager.loadErrors.collectAsState()
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        item {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                ) {
                    Button(
                        onClick = onOpenStore,
                        contentPadding = PaddingValues(horizontal = AppSpacing.lg),
                        modifier = Modifier.heightIn(min = AppSizes.minTouchTarget),
                    ) {
                        Icon(
                            Icons.Default.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(AppSizes.iconSmall)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Text("Eklenti Mağazası", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = { scope.launch(Dispatchers.IO) { ExtensionManager.reload() } },
                        contentPadding = PaddingValues(horizontal = AppSpacing.lg),
                        modifier = Modifier.heightIn(min = AppSizes.minTouchTarget),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(AppSizes.iconSmall)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Text("Yenile", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Text(
                    "${installed.size} kurulu eklenti",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
            }
        }

        if (loadErrors.isNotEmpty()) {
            item {
                Text(
                    "Yüklenemeyenler",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = AppSpacing.xs),
                )
            }
            items(loadErrors.entries.toList(), key = { "err_${it.key}" }) { entry ->
                SurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                    border = null,
                    contentPadding = PaddingValues(AppSpacing.md),
                ) {
                    Text(
                        entry.key,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (installed.isEmpty() && loadErrors.isEmpty()) {
            item {
                StateView(
                    title = "Henüz eklenti kurulmadı.",
                    message = "\"Eklenti Mağazası\"ndan depo ekleyip eklenti kurabilirsin.",
                    icon = Icons.Default.Extension,
                )
            }
        }

        items(installed, key = { "${it.meta.pkgName}@${it.meta.versionCode}" }) { ext ->
            SurfaceCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(AppSpacing.md),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        val icon = rememberExtensionIcon(ext.meta.apkPath)
                        if (icon != null) {
                            Image(
                                bitmap = icon,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(
                                Icons.Default.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(AppSizes.icon),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            ext.meta.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val enabledSources = enabledSourceIds[ext.meta.pkgName]
                            ?.let { ids -> ext.sources.filter { it.id in ids } }
                            ?.ifEmpty { listOfNotNull(ExtensionManager.defaultSource(ext)) }
                            ?: listOfNotNull(ExtensionManager.defaultSource(ext))
                        val languageText = if (enabledSources.size > 1) {
                            "Çok Dilli"
                        } else {
                            enabledSources.firstOrNull()?.lang?.let(::languageLabel)
                                ?: languageLabel(ext.meta.language)
                        }
                        Text(
                            "v${ext.meta.versionName ?: "?"} · $languageText · ${enabledSources.size} kaynak",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { scope.launch { ExtensionManager.uninstall(ext.meta.pkgName) } },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = AppSpacing.md),
                        modifier = Modifier.heightIn(min = AppSizes.minTouchTarget),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(AppSizes.iconSmall),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Text(
                            "Kaldır",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
