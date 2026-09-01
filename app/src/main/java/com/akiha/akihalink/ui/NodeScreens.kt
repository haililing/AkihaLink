package com.akiha.akihalink.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akiha.akihalink.MainUiState
import com.akiha.akihalink.R
import com.akiha.akihalink.data.NodeSort
import com.akiha.akihalink.data.ProxyNodeEntity
import com.akiha.akihalink.nodeSwitchAllowed
import com.akiha.akihalink.root.ModuleCompatibility
import com.akiha.akihalink.speedtest.NodeLatencyResult
import com.akiha.akihalink.speedtest.NodeLatencyStatus
import com.composables.icons.lucide.R as LucideR

@Composable
fun NodeScreen(
    state: MainUiState,
    onSelectNode: (ProxyNodeEntity) -> Unit,
    onSetSort: (NodeSort) -> Unit,
    onStartSpeedTest: () -> Unit,
    onCancelSpeedTest: () -> Unit,
    onToggleReverseDnsMapping: () -> Unit,
    onOpenSubscriptions: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sortExpanded by remember { mutableStateOf(false) }
    val nodes = remember(state.nodes, state.nodeLatencyResults, state.nodeSort, state.speedTestRunning, query) {
        sortedNodes(state).filter {
            val identity = nodeDisplayIdentity(it.name)
            val displayName = nodeDisplayName(it.name)
            query.isBlank() || it.name.contains(query, true) || displayName.contains(query, true) ||
                it.protocol.contains(query, true) || identity.countryCode.contains(query, true) ||
                identity.countryName.contains(query, true)
        }
    }

    PhoneContent {
        Column(Modifier.fillMaxSize()) {
            PageHeading(
                title = "节点",
                actions = {
                    Box {
                        AkihaIconButton(
                            label = "节点排序",
                            icon = LucideR.drawable.lucide_ic_arrow_up_down,
                            onClick = { sortExpanded = true },
                            enabled = state.nodes.isNotEmpty(),
                        )
                        DropdownMenu(sortExpanded, { sortExpanded = false }) {
                            SortItem("延迟优先", NodeSort.LATENCY, state.nodeSort) {
                                sortExpanded = false
                                onSetSort(it)
                            }
                            SortItem("名称顺序", NodeSort.NAME, state.nodeSort) {
                                sortExpanded = false
                                onSetSort(it)
                            }
                        }
                    }
                },
            )
            if (state.nodes.isEmpty()) {
                EmptyState(
                    title = "还没有节点",
                    supporting = "添加订阅后，所有可用线路都会汇总在这里。",
                    icon = LucideR.drawable.lucide_ic_waypoints,
                    modifier = Modifier.weight(1f),
                ) {
                    Button(onClick = onOpenSubscriptions) { Text("添加订阅") }
                }
                return@Column
            }

            SearchAndTestBar(
                query = query,
                onQueryChange = { query = it },
                running = state.speedTestRunning,
                testEnabled = state.nodes.isNotEmpty() && !state.busy && state.compatibility == ModuleCompatibility.Compatible,
                onTest = if (state.speedTestRunning) onCancelSpeedTest else onStartSpeedTest,
            )

            AnimatedVisibility(state.speedTestRunning) { SpeedTestProgress(state) }

            if (nodes.isEmpty()) {
                EmptyState(
                    title = "没有匹配的节点",
                    supporting = "换个地区、名称或协议试试。",
                    icon = LucideR.drawable.lucide_ic_search,
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = AkihaSpacing.page,
                        end = AkihaSpacing.page,
                        top = AkihaSpacing.sm,
                        bottom = AkihaSpacing.lg,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(AkihaSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AkihaSpacing.sm),
                ) {
                    items(nodes, key = { it.id }) { node ->
                        NodeTile(
                            node = node,
                            selected = node.id == state.selectedNodeId,
                            testing = node.id in state.testingNodeIds,
                            latency = state.nodeLatencyResults[node.id],
                            enabled = !state.busy && state.status.nodeSwitchAllowed(),
                            onClick = { onSelectNode(node) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAndTestBar(
    query: String,
    onQueryChange: (String) -> Unit,
    running: Boolean,
    testEnabled: Boolean,
    onTest: () -> Unit,
) {
    val focusManager: FocusManager = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = AkihaSpacing.page),
        horizontalArrangement = Arrangement.spacedBy(AkihaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.large,
            placeholder = { Text("搜索地区、名称或协议") },
            leadingIcon = { Icon(painterResource(LucideR.drawable.lucide_ic_search), null) },
            trailingIcon = if (query.isNotEmpty()) {
                { AkihaIconButton("清空搜索", LucideR.drawable.lucide_ic_x, { onQueryChange("") }) }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
        AkihaIconButton(
            label = if (running) "停止测速" else "批量测速",
            icon = if (running) LucideR.drawable.lucide_ic_square else LucideR.drawable.lucide_ic_gauge,
            onClick = onTest,
            enabled = running || testEnabled,
        )
    }
}

@Composable
private fun SpeedTestProgress(state: MainUiState) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = AkihaSpacing.page, vertical = AkihaSpacing.sm),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(AkihaSpacing.md), verticalArrangement = Arrangement.spacedBy(AkihaSpacing.xs)) {
            Row(Modifier.fillMaxWidth()) {
                Text("正在为所有节点测速", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text("${state.speedTestCompleted}/${state.speedTestTotal}", style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(
                progress = { if (state.speedTestTotal == 0) 0f else state.speedTestCompleted.toFloat() / state.speedTestTotal },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NodeTile(
    node: ProxyNodeEntity,
    selected: Boolean,
    testing: Boolean,
    latency: NodeLatencyResult?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val details = remember(node.name) { nodeDisplayDetails(node.name) }
    val container by animateColorAsState(
        if (selected) AkihaPalette.Indigo else MaterialTheme.colorScheme.surfaceContainerLow,
        tween(AkihaMotion.Default),
        label = "node-tile",
    )
    val contentColor = if (selected) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = contentColor,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
    ) {
        Column(Modifier.fillMaxSize().padding(AkihaSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(details.identity.flag.ifBlank { "◎" }, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = { androidx.compose.animation.fadeIn(tween(200)) togetherWith androidx.compose.animation.fadeOut(tween(120)) },
                    label = "node-selection",
                ) {
                    if (it) {
                        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .18f)) {
                            Icon(painterResource(LucideR.drawable.lucide_ic_check), "当前节点", Modifier.padding(5.dp).size(14.dp))
                        }
                    } else {
                        NodeLatency(testing, latency, false)
                    }
                }
            }
            Spacer(Modifier.height(AkihaSpacing.sm))
            Text(
                details.identity.countryName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                nodeDisplayName(node.name),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = .7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), maxLines = 1) {
                details.attributes.take(2).forEach { attribute -> NodeAttribute(attribute, selected) }
            }
            if (selected) {
                Spacer(Modifier.height(AkihaSpacing.xs))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("当前线路", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = .72f))
                    NodeLatency(testing, latency, true)
                }
            }
        }
    }
}

@Composable
private fun NodeAttribute(attribute: String, selected: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) androidx.compose.ui.graphics.Color.White.copy(alpha = .14f)
        else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(attribute, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun NodeLatency(testing: Boolean, result: NodeLatencyResult?, selected: Boolean) {
    Box(Modifier.widthIn(min = 44.dp), contentAlignment = Alignment.CenterEnd) {
        if (testing) {
            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
        } else {
            val text = when (result?.status) {
                NodeLatencyStatus.AVAILABLE, NodeLatencyStatus.UNSTABLE -> result.delayMs?.let { "$it ms" } ?: "—"
                NodeLatencyStatus.UNAVAILABLE, null -> "—"
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    selected -> androidx.compose.ui.graphics.Color.White
                    result?.status == NodeLatencyStatus.AVAILABLE -> MaterialTheme.colorScheme.secondary
                    result?.status == NodeLatencyStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun SortItem(text: String, value: NodeSort, selected: NodeSort, onSelect: (NodeSort) -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        trailingIcon = if (value == selected) { { Icon(painterResource(LucideR.drawable.lucide_ic_check), null) } } else null,
        onClick = { onSelect(value) },
    )
}

private fun sortedNodes(state: MainUiState): List<ProxyNodeEntity> {
    if (state.speedTestRunning || state.nodeSort == NodeSort.NAME) {
        return state.nodes.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    return state.nodes.sortedWith(
        compareBy<ProxyNodeEntity> { node ->
            when (state.nodeLatencyResults[node.id]?.status) {
                NodeLatencyStatus.AVAILABLE -> if (state.nodeLatencyResults[node.id]?.delayMs != null) 0 else 3
                NodeLatencyStatus.UNSTABLE -> if (state.nodeLatencyResults[node.id]?.delayMs != null) 1 else 3
                null -> 2
                NodeLatencyStatus.UNAVAILABLE -> 3
            }
        }.thenBy { state.nodeLatencyResults[it.id]?.delayMs ?: Int.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
    )
}
