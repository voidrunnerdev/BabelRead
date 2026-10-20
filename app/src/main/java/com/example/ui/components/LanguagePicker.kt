package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class LanguagePickerOption(
    val code: String,
    val title: String,
    val nativeName: String? = null,
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
            color = Color(0xFF121212),
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
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Geri",
                            tint = Color.White,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text("Dil ara…", color = Color.Gray) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray)
                    },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))

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
                    Text(
                        "Dil bulunamadı.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (recentOptions.isNotEmpty()) {
                            item {
                                Text(
                                    "SON KULLANILANLAR",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                                )
                            }
                            items(recentOptions, key = { "recent_${it.code}" }) { option ->
                                LanguageRow(
                                    option = option,
                                    selected = option.code == selectedCode,
                                    onClick = { onSelect(option.code) },
                                )
                            }
                            item {
                                Text(
                                    "TÜM DİLLER",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                                )
                            }
                        }
                        items(filtered, key = { it.code }) { option ->
                            LanguageRow(
                                option = option,
                                selected = option.code == selectedCode,
                                onClick = { onSelect(option.code) },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(
    option: LanguagePickerOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                option.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            if (!option.nativeName.isNullOrBlank() && option.nativeName != option.title) {
                Text(
                    option.nativeName,
                    color = Color(0xFF9E9E9E),
                    fontSize = 12.sp,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Seçili",
                tint = Color(0xFF00E676),
            )
        }
    }
}
