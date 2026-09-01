package com.akiha.akihalink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akiha.akihalink.MainUiState
import com.akiha.akihalink.data.SubscriptionEntity
import com.composables.icons.lucide.R as LucideR
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SubscriptionMotionEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
fun SubscriptionScreen(
    state: MainUiState,
    onAdd: (String, String) -> Unit,
    onUpdate: (SubscriptionEntity) -> Unit,
    onSetEnabled: (SubscriptionEntity, Boolean) -> Unit,
    onDelete: (SubscriptionEntity) -> Unit,
    onBack: () -> Unit = {},
) {
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<SubscriptionEntity?>(null) }

    PhoneContent {
        Scaffold(
            containerColor = Color.Transparent,
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showAdd = true },
                    icon = { Icon(painterResource(LucideR.drawable.lucide_ic_plus), null) },
                    text = { Text("添加订阅") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large,
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                PageHeading(
                    title = "订阅与线路",
                    leading = { AkihaIconButton("返回", LucideR.drawable.lucide_ic_arrow_left, onBack) },
                )
                if (state.subscriptions.isEmpty()) {
                    EmptyState(
                        title = "还没有订阅",
                        supporting = "添加订阅链接，AkihaLink 会自动整理节点和流量信息。",
                        icon = LucideR.drawable.lucide_ic_layers_plus,
                        modifier = Modifier.weight(1f),
                    ) {
                        Button(onClick = { showAdd = true }, enabled = !state.busy) { Text("添加第一个订阅") }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = AkihaSpacing.page,
                            end = AkihaSpacing.page,
                            top = AkihaSpacing.xs,
                            bottom = 112.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(AkihaSpacing.sm),
                    ) {
                        items(state.subscriptions, key = { it.id }) { subscription ->
                            SubscriptionCard(
                                subscription = subscription,
                                nodeCount = state.subscriptionNodeCounts[subscription.id] ?: 0,
                                expanded = subscription.id == expandedId,
                                enabled = !state.busy,
                                onExpand = {
                                    expandedId = if (expandedId == subscription.id) null else subscription.id
                                },
                                onUpdate = { onUpdate(subscription) },
                                onSetEnabled = { onSetEnabled(subscription, it) },
                                onDelete = { pendingDelete = subscription },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddSubscriptionSheet(
            onDismiss = { showAdd = false },
            onAdd = { name, url ->
                showAdd = false
                onAdd(name, url)
            },
        )
    }
    pendingDelete?.let { subscription ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(painterResource(LucideR.drawable.lucide_ic_trash_2), null) },
            title = { Text("删除订阅？") },
            text = { Text("“${subscription.name}”及其节点将被移除。此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(subscription)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
            shape = MaterialTheme.shapes.extraLarge,
        )
    }
}

@Composable
private fun SubscriptionCard(
    subscription: SubscriptionEntity,
    nodeCount: Int,
    expanded: Boolean,
    enabled: Boolean,
    onExpand: () -> Unit,
    onUpdate: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val switchDescription = if (subscription.isEnabled) "关闭订阅 ${subscription.name}" else "启用订阅 ${subscription.name}"
    val used = subscriptionUsedBytes(subscription)
    val total = subscription.trafficTotalBytes?.takeIf { it > 0 }
    val progress = if (used != null && total != null) (used.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(AkihaMotion.Default, easing = SubscriptionMotionEasing),
        label = "subscription-chevron",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (subscription.isEnabled) MaterialTheme.colorScheme.surfaceContainerLow
        else MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onExpand)
                    .semantics { contentDescription = "管理订阅 ${subscription.name}" }
                    .padding(AkihaSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AkihaSpacing.sm),
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (subscription.isEnabled) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_rss),
                            null,
                            Modifier.size(21.dp),
                            tint = if (subscription.isEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(subscription.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append("$nodeCount 个节点")
                            if (!subscription.isEnabled) append(" · 已暂停")
                            subscription.lastUpdatedAt?.let { append(" · ${formatSubscriptionDate(it)}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = subscription.isEnabled,
                    onCheckedChange = onSetEnabled,
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = switchDescription },
                )
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_chevron_down),
                    null,
                    Modifier.size(19.dp).rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = tween(AkihaMotion.Default, easing = SubscriptionMotionEasing),
                ) + fadeIn(
                    animationSpec = tween(180, delayMillis = 45, easing = SubscriptionMotionEasing),
                ),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = tween(220, easing = SubscriptionMotionEasing),
                ) + fadeOut(
                    animationSpec = tween(120, easing = SubscriptionMotionEasing),
                ),
            ) {
                Column(Modifier.fillMaxWidth().padding(start = AkihaSpacing.md, end = AkihaSpacing.md, bottom = AkihaSpacing.md)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                    Spacer(Modifier.height(AkihaSpacing.md))
                    if (progress != null && used != null && total != null) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("套餐用量", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "${formatTrafficBytes(used)} / ${formatTrafficBytes(total)}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        }
                        Spacer(Modifier.height(AkihaSpacing.xs))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp))
                        Spacer(Modifier.height(AkihaSpacing.md))
                    }
                    subscription.lastError?.takeIf { it.isNotBlank() }?.let {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.small,
                        ) { Text(it, Modifier.fillMaxWidth().padding(AkihaSpacing.sm), style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(AkihaSpacing.sm))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDelete, enabled = enabled) {
                            Icon(painterResource(LucideR.drawable.lucide_ic_trash_2), null, Modifier.size(17.dp))
                            Text("删除", Modifier.padding(start = AkihaSpacing.xs))
                        }
                        TextButton(onClick = onUpdate, enabled = enabled && subscription.isEnabled) {
                            Icon(painterResource(LucideR.drawable.lucide_ic_refresh_cw), null, Modifier.size(17.dp))
                            Text("立即更新", Modifier.padding(start = AkihaSpacing.xs))
                        }
                    }
                }
            }
        }
    }
}

internal fun formatSubscriptionDate(timestamp: Long): String {
    val millis = if (timestamp >= 100_000_000_000L) timestamp else timestamp * 1_000
    return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(millis))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSubscriptionSheet(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val valid = name.isNotBlank() && url.isNotBlank()
    ModalBottomSheet(onDismissRequest = onDismiss, shape = MaterialTheme.shapes.extraLarge) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                .padding(horizontal = AkihaSpacing.page, vertical = AkihaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AkihaSpacing.md),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_link), null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column {
                Text("添加订阅", style = MaterialTheme.typography.headlineMedium)
                Text("粘贴服务商提供的链接，我们会自动整理线路。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                shape = MaterialTheme.shapes.medium,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("订阅链接") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    if (valid) onAdd(name.trim(), url.trim())
                }),
                shape = MaterialTheme.shapes.medium,
            )
            Button(
                onClick = { onAdd(name.trim(), url.trim()) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text("添加并同步") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            Spacer(Modifier.height(AkihaSpacing.md))
        }
    }
}
