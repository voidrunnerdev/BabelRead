package com.example.data.parser

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

sealed interface SourceFilterNode {
    val key: String
    val name: String

    data class Header(
        override val key: String,
        override val name: String
    ) : SourceFilterNode

    data class Separator(
        override val key: String,
        override val name: String = ""
    ) : SourceFilterNode

    data class Select(
        override val key: String,
        override val name: String,
        val values: List<String>,
        val selectedIndex: Int
    ) : SourceFilterNode

    data class Text(
        override val key: String,
        override val name: String,
        val value: String
    ) : SourceFilterNode

    data class CheckBox(
        override val key: String,
        override val name: String,
        val checked: Boolean
    ) : SourceFilterNode

    data class TriState(
        override val key: String,
        override val name: String,
        val state: Int
    ) : SourceFilterNode

    data class Group(
        override val key: String,
        override val name: String,
        val children: List<SourceFilterNode>
    ) : SourceFilterNode

    data class Sort(
        override val key: String,
        override val name: String,
        val values: List<String>,
        val selectedIndex: Int?,
        val ascending: Boolean
    ) : SourceFilterNode
}

data class ActiveSourceFilter(
    val key: String,
    val label: String
)

data class DiscoverFilterState(
    val sourceName: String = "",
    val nodes: List<SourceFilterNode> = emptyList(),
    val defaultNodes: List<SourceFilterNode> = emptyList(),
    val activeFilters: List<ActiveSourceFilter> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val version: Int = 0
) {
    val activeCount: Int get() = activeFilters.size

    val hasFilters: Boolean
        get() = nodes.any { node ->
            node !is SourceFilterNode.Header && node !is SourceFilterNode.Separator
        }
}

object SourceFilterAdapter {
    fun build(filters: FilterList): List<SourceFilterNode> =
        filters.mapIndexedNotNull { index, filter -> toNode(filter, index.toString()) }

    fun apply(filters: FilterList, nodes: List<SourceFilterNode>) {
        nodes.forEach { node ->
            val index = node.key.substringBefore('.').toIntOrNull() ?: return@forEach
            val filter = filters.getOrNull(index) ?: return@forEach
            applyNode(filter, node)
        }
    }

    fun activeFilters(
        defaults: List<SourceFilterNode>,
        current: List<SourceFilterNode>
    ): List<ActiveSourceFilter> {
        val result = mutableListOf<ActiveSourceFilter>()
        current.forEach { node ->
            diffNode(defaults.firstOrNull { it.key == node.key }, node, result)
        }
        return result
    }

    fun findNode(nodes: List<SourceFilterNode>, key: String): SourceFilterNode? {
        nodes.forEach { node ->
            if (node.key == key) return node
            if (node is SourceFilterNode.Group) {
                findNode(node.children, key)?.let { return it }
            }
        }
        return null
    }

    fun replaceNode(
        nodes: List<SourceFilterNode>,
        key: String,
        replacement: SourceFilterNode
    ): List<SourceFilterNode> = nodes.map { node ->
        when {
            node.key == key -> replacement
            node is SourceFilterNode.Group ->
                node.copy(children = replaceNode(node.children, key, replacement))
            else -> node
        }
    }

    private fun toNode(filter: Filter<*>, key: String): SourceFilterNode? = when (filter) {
        is Filter.Header -> SourceFilterNode.Header(key, filter.name)
        is Filter.Separator -> SourceFilterNode.Separator(key, filter.name)
        is Filter.Select<*> -> SourceFilterNode.Select(
            key = key,
            name = filter.name,
            values = filter.values.map { it.toString() },
            selectedIndex = filter.state
        )
        is Filter.Text -> SourceFilterNode.Text(key, filter.name, filter.state)
        is Filter.CheckBox -> SourceFilterNode.CheckBox(key, filter.name, filter.state)
        is Filter.TriState -> SourceFilterNode.TriState(key, filter.name, filter.state)
        is Filter.Group<*> -> SourceFilterNode.Group(
            key = key,
            name = filter.name,
            children = filter.state.mapIndexedNotNull { index, value ->
                (value as? Filter<*>)?.let { toNode(it, "$key.$index") }
            }
        )
        is Filter.Sort -> SourceFilterNode.Sort(
            key = key,
            name = filter.name,
            values = filter.values.toList(),
            selectedIndex = filter.state?.index,
            ascending = filter.state?.ascending ?: true
        )
        else -> null
    }

    private fun applyNode(filter: Filter<*>, node: SourceFilterNode) {
        when (filter) {
            is Filter.Select<*> -> if (node is SourceFilterNode.Select) {
                filter.state = node.selectedIndex.coerceIn(
                    0,
                    (filter.values.size - 1).coerceAtLeast(0)
                )
            }
            is Filter.Text -> if (node is SourceFilterNode.Text) {
                filter.state = node.value
            }
            is Filter.CheckBox -> if (node is SourceFilterNode.CheckBox) {
                filter.state = node.checked
            }
            is Filter.TriState -> if (node is SourceFilterNode.TriState) {
                filter.state = node.state.coerceIn(
                    Filter.TriState.STATE_IGNORE,
                    Filter.TriState.STATE_EXCLUDE
                )
            }
            is Filter.Group<*> -> if (node is SourceFilterNode.Group) {
                node.children.forEach { childNode ->
                    val childIndex = childNode.key.substringAfterLast('.').toIntOrNull()
                        ?: return@forEach
                    val child = (filter.state.getOrNull(childIndex) as? Filter<*>) ?: return@forEach
                    applyNode(child, childNode)
                }
            }
            is Filter.Sort -> if (node is SourceFilterNode.Sort) {
                filter.state = node.selectedIndex?.let { index ->
                    Filter.Sort.Selection(
                        index.coerceIn(0, (filter.values.size - 1).coerceAtLeast(0)),
                        node.ascending
                    )
                }
            }
            else -> Unit
        }
    }

    private fun diffNode(
        defaultNode: SourceFilterNode?,
        node: SourceFilterNode,
        result: MutableList<ActiveSourceFilter>
    ) {
        when (node) {
            is SourceFilterNode.Header,
            is SourceFilterNode.Separator -> Unit
            is SourceFilterNode.Select -> {
                val defaultIndex = (defaultNode as? SourceFilterNode.Select)?.selectedIndex ?: 0
                if (node.selectedIndex != defaultIndex) {
                    result += ActiveSourceFilter(
                        node.key,
                        "${node.name}: ${node.values.getOrElse(node.selectedIndex) { "" }}"
                    )
                }
            }
            is SourceFilterNode.Text -> {
                val defaultValue = (defaultNode as? SourceFilterNode.Text)?.value ?: ""
                if (node.value != defaultValue) {
                    result += ActiveSourceFilter(
                        node.key,
                        if (node.value.isBlank()) node.name else "${node.name}: ${node.value}"
                    )
                }
            }
            is SourceFilterNode.CheckBox -> {
                val defaultChecked = (defaultNode as? SourceFilterNode.CheckBox)?.checked ?: false
                if (node.checked != defaultChecked) {
                    result += ActiveSourceFilter(
                        node.key,
                        if (node.checked) node.name else "${node.name}: kapalı"
                    )
                }
            }
            is SourceFilterNode.TriState -> {
                val defaultState = (defaultNode as? SourceFilterNode.TriState)?.state
                    ?: Filter.TriState.STATE_IGNORE
                if (node.state != defaultState) {
                    result += ActiveSourceFilter(
                        node.key,
                        "${node.name}: ${triStateLabel(node.state)}"
                    )
                }
            }
            is SourceFilterNode.Group -> {
                val defaults = (defaultNode as? SourceFilterNode.Group)?.children.orEmpty()
                node.children.forEach { child ->
                    diffNode(defaults.firstOrNull { it.key == child.key }, child, result)
                }
            }
            is SourceFilterNode.Sort -> {
                val defaultSort = defaultNode as? SourceFilterNode.Sort
                val changed = node.selectedIndex != defaultSort?.selectedIndex ||
                    (node.selectedIndex != null && node.ascending != defaultSort?.ascending)
                if (changed) {
                    val value = node.selectedIndex?.let { node.values.getOrNull(it) } ?: "varsayılan"
                    val direction = if (node.selectedIndex == null) {
                        ""
                    } else if (node.ascending) {
                        " ↑"
                    } else {
                        " ↓"
                    }
                    result += ActiveSourceFilter(node.key, "${node.name}: $value$direction")
                }
            }
        }
    }

    private fun triStateLabel(state: Int): String = when (state) {
        Filter.TriState.STATE_INCLUDE -> "dahil"
        Filter.TriState.STATE_EXCLUDE -> "hariç"
        else -> "yok say"
    }
}
