package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.i18n.Text
import com.example.ui.theme.AppSizes
import com.example.ui.theme.AppSpacing

data class LanguagePickerOption(
    val code: String,
    val title: String,
    val nativeName: String? = null,
    val translateTitle: Boolean = true,
)

@Composable
fun LanguagePickerDialog(
    title: String,
    options: List<LanguagePickerOption>,
    selectedCode: String?,
    recentCodes: List<String> = emptyList(),
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xs),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Geri",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.lg),
                    placeholder = {
                        Text(
                            text = "Dil ara…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))

                val normalizedQuery = query.trim().lowercase()
                val filtered = remember(options, normalizedQuery) {
                    if (normalizedQuery.isEmpty()) {
                        options
                    } else {
                        options.filter { option ->
                            option.title.lowercase().contains(normalizedQuery) ||
                                option.code.lowercase().contains(normalizedQuery) ||
                                option.nativeName.orEmpty().lowercase().contains(normalizedQuery)
                        }
                    }
                }
                val recentOptions = if (query.isBlank()) {
                    val byCode = options.associateBy { it.code }
                    recentCodes.mapNotNull { byCode[it] }.take(5)
                } else {
                    emptyList()
                }

                if (filtered.isEmpty()) {
                    StateView(
                        title = "Dil bulunamadı.",
                        icon = Icons.Default.Search,
                        modifier = Modifier.padding(top = AppSpacing.lg),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (recentOptions.isNotEmpty()) {
                            item {
                                LanguageSectionLabel("SON KULLANILANLAR")
                            }
                            items(recentOptions, key = { "recent_${it.code}" }) { option ->
                                LanguageRow(
                                    option = option,
                                    selected = option.code == selectedCode,
                                    onClick = { onSelect(option.code) },
                                )
                            }
                            item {
                                LanguageSectionLabel("TÜM DİLLER")
                            }
                        }
                        items(filtered, key = { it.code }) { option ->
                            LanguageRow(
                                option = option,
                                selected = option.code == selectedCode,
                                onClick = { onSelect(option.code) },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(AppSpacing.xl)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(
            start = AppSpacing.lg,
            end = AppSpacing.lg,
            top = AppSpacing.sm,
            bottom = AppSpacing.xs,
        ),
    )
}

@Composable
private fun LanguageRow(
    option: LanguagePickerOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedBackground = if (selected) {
        Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
    } else {
        Modifier
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sm)
            .heightIn(min = AppSizes.minTouchTarget)
            .clip(MaterialTheme.shapes.small)
            .then(selectedBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (option.translateTitle) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                MaterialText(
                    text = option.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!option.nativeName.isNullOrBlank() && option.nativeName != option.title) {
                MaterialText(
                    text = option.nativeName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Seçili",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(AppSizes.iconLarge),
            )
        }
    }
}
