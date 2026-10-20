package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.extensions.ExtensionManager
import com.example.extensions.languageLabel
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
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onOpenStore,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Eklenti Mağazası", fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { scope.launch(Dispatchers.IO) { ExtensionManager.reload() } },
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Yenile", fontSize = 13.sp)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "${installed.size} kurulu eklenti",
                color = Color.Gray,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (loadErrors.isNotEmpty()) {
            item {
                Text(
                    "Yüklenemeyenler",
                    color = Color(0xFFFF8A8A),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(loadErrors.entries.toList(), key = { "err_${it.key}" }) { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF2A1A1A))
                        .padding(10.dp),
                ) {
                    Text(
                        entry.key,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.value,
                        color = Color(0xFFFF8A8A),
                        fontSize = 11.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (installed.isEmpty() && loadErrors.isEmpty()) {
            item {
                Text(
                    "Henüz eklenti kurulmadı. \"Eklenti Mağazası\"ndan depo ekleyip eklenti kurabilirsin.",
                    color = Color.Gray,
                    fontSize = 13.sp,
                )
            }
        }

        items(installed, key = { "${it.meta.pkgName}@${it.meta.versionCode}" }) { ext ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF2A2A2A)),
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
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        ext.meta.name,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
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
                        color = Color.Gray,
                        fontSize = 11.sp,
                    )
                }
                OutlinedButton(
                    onClick = { scope.launch { ExtensionManager.uninstall(ext.meta.pkgName) } },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = Color(0xFFFF8A8A),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Kaldır", fontSize = 12.sp, color = Color(0xFFFF8A8A))
                }
            }
        }
    }
}
