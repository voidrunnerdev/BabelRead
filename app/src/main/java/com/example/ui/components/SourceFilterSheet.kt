package com.example.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.parser.DiscoverFilterState
import com.example.data.parser.SourceFilterAdapter
import com.example.data.parser.SourceFilterNode
import com.example.ui.theme.AppSizes
import com.example.ui.theme.AppSpacing
import eu.kanade.tachiyomi.source.model.Filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceFilterSheet(
    state: DiscoverFilterState,
    onDismiss: () -> Unit,
    onApply: (List<SourceFilterNode>) -> Unit,
    onRetry: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(state.sourceName, state.nodes, state.version) {
        mutableStateOf(state.nodes)
    }
    val draftActiveCount = SourceFilterAdapter.activeFilters(state.defaultNodes, draft).size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            androidx.compose.material3.BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = AppSpacing.lg, end = AppSpacing.sm, top = AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Filtreler",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = buildString {
                            append(state.sourceName.ifBlank { "Kaynak" })
                            if (draftActiveCount > 0) append(" · $draftActiveCount aktif")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = AppSizes.minTouchTarget)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Kapat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when {
                state.loading -> StateView(
                    title = "Filtreler yükleniyor…",
                    loading = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                state.error != null -> StateView(
                    title = "Filtreler yüklenemedi",
                    message = state.error.orEmpty(),
                    icon = Icons.Default.ErrorOutline,
                    actionLabel = "Tekrar dene",
                    onAction = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                draft.isEmpty() -> StateView(
                    title = "Bu kaynak filtre sunmuyor",
                    message = "Filtreler eklenti tarafından tanımlanmamış.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(
                            start = AppSpacing.lg,
                            end = AppSpacing.lg,
                            top = AppSpacing.sm,
                            bottom = AppSpacing.lg
                        ),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                    ) {
                        items(draft, key = { it.key }) { node ->
                            SourceFilterNodeContent(
                                node = node,
                                onNodeChange = { updated ->
                                    draft = SourceFilterAdapter.replaceNode(
                                        draft,
                                        updated.key,
                                        updated
                                    )
                                }
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { draft = state.defaultNodes },
                            enabled = draft != state.defaultNodes,
                            modifier = Modifier.heightIn(min = AppSizes.minTouchTarget)
                        ) {
                            Text(
                                "Temizle",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (draft != state.defaultNodes) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = { onApply(draft) },
                            modifier = Modifier.heightIn(min = AppSizes.minTouchTarget)
                        ) {
                            Text("Uygula", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceFilterNodeContent(
    node: SourceFilterNode,
    onNodeChange: (SourceFilterNode) -> Unit
) {
    when (node) {
        is SourceFilterNode.Header -> Text(
            text = node.name.ifBlank { "Filtreler" },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        is SourceFilterNode.Separator -> HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant
        )
        is SourceFilterNode.Select -> SourceSelectFilter(
            node = node,
            onChange = { onNodeChange(node.copy(selectedIndex = it)) }
        )
        is SourceFilterNode.Text -> OutlinedTextField(
            value = node.value,
            onValueChange = { onNodeChange(node.copy(value = it)) },
            label = { Text(node.name.ifBlank { "Metin" }) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        is SourceFilterNode.CheckBox -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppSizes.minTouchTarget)
                .toggleable(
                    value = node.checked,
                    role = Role.Checkbox,
                    onValueChange = { onNodeChange(node.copy(checked = it)) }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = node.checked,
                onCheckedChange = null
            )
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            Text(
                text = node.name.ifBlank { "Seçenek" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        is SourceFilterNode.TriState -> TriStateFilter(
            node = node,
            onChange = { onNodeChange(node.copy(state = it)) }
        )
        is SourceFilterNode.Group -> Column(
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            if (node.name.isNotBlank()) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Column(
                modifier = Modifier.padding(start = AppSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
            ) {
                node.children.forEach { child ->
                    SourceFilterNodeContent(
                        node = child,
                        onNodeChange = { updatedChild ->
                            onNodeChange(
                                node.copy(
                                    children = node.children.map {
                                        if (it.key == updatedChild.key) updatedChild else it
                                    }
                                )
                            )
                        }
                    )
                }
            }
        }
        is SourceFilterNode.Sort -> SortFilter(
            node = node,
            onChange = { index, ascending ->
                onNodeChange(node.copy(selectedIndex = index, ascending = ascending))
            }
        )
    }
}

@Composable
private fun SourceSelectFilter(
    node: SourceFilterNode.Select,
    onChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Text(
            text = node.name.ifBlank { "Seçim" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (node.values.size <= 8) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
            ) {
                node.values.forEachIndexed { index, value ->
                    FilterChip(
                        selected = node.selectedIndex == index,
                        onClick = { onChange(index) },
                        label = {
                            Text(
                                text = value,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.heightIn(min = AppSizes.minTouchTarget),
                        shape = MaterialTheme.shapes.small,
                        colors = sourceFilterChipColors()
                    )
                }
            }
        } else {
            var expanded by remember(node.key) { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AppSizes.minTouchTarget)
                ) {
                    Text(
                        text = node.values.getOrNull(node.selectedIndex).orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    node.values.forEachIndexed { index, value ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            onClick = {
                                expanded = false
                                onChange(index)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TriStateFilter(
    node: SourceFilterNode.TriState,
    onChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Text(
            text = node.name.ifBlank { "Durum" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            listOf(
                Filter.TriState.STATE_IGNORE to "Yok say",
                Filter.TriState.STATE_INCLUDE to "Dahil et",
                Filter.TriState.STATE_EXCLUDE to "Hariç tut"
            ).forEach { (value, label) ->
                FilterChip(
                    selected = node.state == value,
                    onClick = { onChange(value) },
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    modifier = Modifier.heightIn(min = AppSizes.minTouchTarget),
                    shape = MaterialTheme.shapes.small,
                    colors = sourceFilterChipColors()
                )
            }
        }
    }
}

@Composable
private fun SortFilter(
    node: SourceFilterNode.Sort,
    onChange: (Int?, Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Text(
            text = node.name.ifBlank { "Sıralama" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            var expanded by remember(node.key) { mutableStateOf(false) }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AppSizes.minTouchTarget)
                ) {
                    Text(
                        text = node.selectedIndex
                            ?.let { node.values.getOrNull(it) }
                            ?: "Varsayılan",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Varsayılan") },
                        onClick = {
                            expanded = false
                            onChange(null, node.ascending)
                        }
                    )
                    node.values.forEachIndexed { index, value ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            onClick = {
                                expanded = false
                                onChange(index, node.ascending)
                            }
                        )
                    }
                }
            }
            if (node.selectedIndex != null) {
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                IconButton(
                    onClick = { onChange(node.selectedIndex, !node.ascending) },
                    modifier = Modifier.heightIn(min = AppSizes.minTouchTarget)
                ) {
                    Icon(
                        imageVector = if (node.ascending) {
                            Icons.Default.ArrowUpward
                        } else {
                            Icons.Default.ArrowDownward
                        },
                        contentDescription = if (node.ascending) {
                            "Artan sıralama"
                        } else {
                            "Azalan sıralama"
                        },
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun sourceFilterChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
)
