package com.akiha.akihalink.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akiha.akihalink.DnsValidationState
import com.akiha.akihalink.EnvironmentPresentation
import com.akiha.akihalink.MainUiState
import com.akiha.akihalink.R
import com.akiha.akihalink.environmentPresentation
import com.akiha.akihalink.config.ProxyMode
import com.akiha.akihalink.data.SubscriptionEntity
import com.akiha.akihalink.root.ModuleCompatibility
import com.composables.icons.lucide.R as LucideR
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ProductEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
internal const val SUBSCRIPTION_OVERVIEW_PAGER_TAG = "subscription-overview-pager"

internal fun runtimeControlsEnabled(state: MainUiState): Boolean = !state.busy

private enum class ConnectionVisualState { DISCONNECTED, CONNECTING, CONNECTED, ATTENTION }

@Composable
fun HomeScreen(
    state: MainUiState,
    onTogglePower: () -> Unit,
    onModeSelected: (ProxyMode) -> Unit,
    onCheckEnvironment: () -> Unit,
    onOpenNodes: () -> Unit,
    onOpenManage: () -> Unit = {},
) {
    val running = state.status.actualState == "running"
    val compatible = state.compatibility == ModuleCompatibility.Compatible
    val canPower = compatible && state.probe?.ok == true && state.selectedNodeId != null && state.mode != ProxyMode.DIRECT
    val selected = state.nodes.firstOrNull { it.id == state.selectedNodeId }
    val visualState = when {
        state.busy || state.status.actualState == "starting" -> ConnectionVisualState.CONNECTING
        running && dnsInterceptionState(state) == DnsInterceptionState.ATTENTION -> ConnectionVisualState.ATTENTION
        running -> ConnectionVisualState.CONNECTED
        else -> ConnectionVisualState.DISCONNECTED
    }

    PhoneContent {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            PageHeading(title = "概览")
            Column(Modifier.padding(horizontal = AkihaSpacing.page)) {
                if (environmentPresentation(state) != EnvironmentPresentation.READY) {
                    EnvironmentNotice(state, onCheckEnvironment)
                    Spacer(Modifier.height(AkihaSpacing.md))
                }

                ConnectionHero(
                    visualState = visualState,
                    selectedNode = selected?.let { nodeDisplayName(it.name) },
                    enabled = (running || canPower) && runtimeControlsEnabled(state),
                    onTogglePower = onTogglePower,
                    onOpenNodes = onOpenNodes,
                )

                dnsInterceptionDetail(state)?.let {
                    Spacer(Modifier.height(AkihaSpacing.sm))
                    InlineNotice(dnsInterceptionLabel(state), it)
                }

                Spacer(Modifier.height(AkihaSpacing.lg))
                SectionHeading("流量路径")
                Spacer(Modifier.height(AkihaSpacing.sm))
                ModeRail(state.mode, runtimeControlsEnabled(state), onModeSelected)

                Spacer(Modifier.height(AkihaSpacing.lg))
                SectionHeading(
                    title = "当前线路",
                    action = { TextButton(onClick = onOpenManage) { Text("管理") } },
                )
                Spacer(Modifier.height(AkihaSpacing.sm))
                CurrentRouteCard(state, onOpenNodes)
                Spacer(Modifier.height(AkihaSpacing.xl))
            }
        }
    }
}

@Composable
private fun ConnectionHero(
    visualState: ConnectionVisualState,
    selectedNode: String?,
    enabled: Boolean,
    onTogglePower: () -> Unit,
    onOpenNodes: () -> Unit,
) {
    val connected = visualState == ConnectionVisualState.CONNECTED || visualState == ConnectionVisualState.ATTENTION
    val container by animateColorAsState(
        when (visualState) {
            ConnectionVisualState.CONNECTED -> AkihaPalette.Indigo
            ConnectionVisualState.CONNECTING -> MaterialTheme.colorScheme.primaryContainer
            ConnectionVisualState.ATTENTION -> MaterialTheme.colorScheme.errorContainer
            ConnectionVisualState.DISCONNECTED -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        tween(AkihaMotion.Default),
        label = "hero-color",
    )
    val contentColor = when (visualState) {
        ConnectionVisualState.CONNECTED -> Color.White
        ConnectionVisualState.CONNECTING -> MaterialTheme.colorScheme.onPrimaryContainer
        ConnectionVisualState.ATTENTION -> MaterialTheme.colorScheme.onErrorContainer
        ConnectionVisualState.DISCONNECTED -> MaterialTheme.colorScheme.onSurface
    }
    val buttonColor = if (connected && visualState != ConnectionVisualState.ATTENTION) Color.White.copy(alpha = .16f)
    else contentColor.copy(alpha = .10f)
    val rotation by animateFloatAsState(if (visualState == ConnectionVisualState.CONNECTING) 180f else 0f, tween(600), label = "connect-rotation")
    val supportingText = when (visualState) {
        ConnectionVisualState.DISCONNECTED -> selectedNode ?: "先选择一个可用节点"
        ConnectionVisualState.CONNECTING -> "正在同步路由与 DNS"
        ConnectionVisualState.CONNECTED -> null
        ConnectionVisualState.ATTENTION -> "连接仍在运行，请检查 DNS 状态"
    }

    Surface(
        shape = RoundedCornerShape(30.dp),
        color = container,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(AkihaSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AkihaSpacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (visualState) {
                            ConnectionVisualState.DISCONNECTED -> "未连接"
                            ConnectionVisualState.CONNECTING -> "正在建立连接"
                            ConnectionVisualState.CONNECTED -> "连接已开启"
                            ConnectionVisualState.ATTENTION -> "需要处理"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    supportingText?.let {
                        Spacer(Modifier.height(AkihaSpacing.xxs))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor.copy(alpha = .76f),
                        )
                    }
                }
                Surface(
                    onClick = onTogglePower,
                    enabled = enabled,
                    shape = CircleShape,
                    color = buttonColor,
                    modifier = Modifier.size(72.dp).semantics {
                        contentDescription = if (connected) "关闭代理" else "启动代理"
                        role = Role.Button
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (visualState == ConnectionVisualState.CONNECTING) {
                            CircularProgressIndicator(Modifier.size(30.dp).rotate(rotation), color = contentColor, strokeWidth = 3.dp)
                        } else {
                            Icon(painterResource(LucideR.drawable.lucide_ic_power), null, Modifier.size(29.dp), tint = contentColor)
                        }
                    }
                }
            }

            Surface(
                onClick = onOpenNodes,
                enabled = selectedNode != null,
                shape = MaterialTheme.shapes.medium,
                color = buttonColor,
                contentColor = contentColor,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = AkihaSpacing.md, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AkihaSpacing.sm),
                ) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_waypoints), null, Modifier.size(19.dp))
                    Text(selectedNode ?: "选择节点", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(painterResource(LucideR.drawable.lucide_ic_chevron_right), null, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun InlineNotice(title: String, supporting: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(AkihaSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(AkihaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(LucideR.drawable.lucide_ic_triangle_alert), null, Modifier.size(20.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(supporting, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ModeRail(mode: ProxyMode, enabled: Boolean, onSelected: (ProxyMode) -> Unit) {
    val modes = listOf(
        Triple(ProxyMode.RULE, "规则", "智能分流"),
        Triple(ProxyMode.GLOBAL, "全局", "全部代理"),
        Triple(ProxyMode.DIRECT, "直连", "不走代理"),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AkihaSpacing.xs)) {
        modes.forEach { (value, title, supporting) ->
            val selected = mode == value
            Surface(
                onClick = { onSelected(value) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .28f)) else null,
            ) {
                Column(Modifier.padding(horizontal = AkihaSpacing.sm, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(supporting, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CurrentRouteCard(state: MainUiState, onOpenNodes: () -> Unit) {
    val subscription = state.subscriptions.getOrNull(overviewSubscriptionIndex(state))
    val nodeCount = subscription?.let { state.subscriptionNodeCounts[it.id] } ?: state.nodes.size
    Surface(
        onClick = onOpenNodes,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(AkihaSpacing.md), verticalArrangement = Arrangement.spacedBy(AkihaSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_route),
                            null,
                            Modifier.size(21.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(start = AkihaSpacing.sm)) {
                    Text(subscription?.name ?: "尚未配置线路", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (subscription == null) "添加订阅后即可获取节点" else "$nodeCount 个可用节点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(painterResource(LucideR.drawable.lucide_ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (subscription != null) SubscriptionUsage(subscription)
        }
    }
}

@Composable
private fun SubscriptionUsage(subscription: SubscriptionEntity) {
    val used = subscriptionUsedBytes(subscription)
    val total = subscription.trafficTotalBytes?.takeIf { it > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(AkihaSpacing.md)) {
        if (used != null && total != null) {
            val progress = (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
            Column(verticalArrangement = Arrangement.spacedBy(AkihaSpacing.xs)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("本期流量", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${formatTrafficBytes(used)} / ${formatTrafficBytes(total)}",
                        Modifier.weight(1f),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        } else {
            Text(
                used?.let { "本期已用 ${formatTrafficBytes(it)}" } ?: "服务商未提供流量信息",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(AkihaSpacing.xs)) {
            Text("订阅时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("开始", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        formatSubscriptionPeriodStart(subscription.subscriptionStartedAt),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("结束", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatSubscriptionPeriodEnd(subscription.expiresAt), style = MaterialTheme.typography.labelMedium)
                }
            }
            subscriptionTimeProgress(subscription, System.currentTimeMillis())?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }

    }
}

@Composable
private fun EnvironmentNotice(state: MainUiState, onCheckEnvironment: () -> Unit) {
    val presentation = environmentPresentation(state)
    StatusBand(
        title = when (presentation) {
            EnvironmentPresentation.CHECKING -> "正在检查运行环境"
            EnvironmentPresentation.STALE -> "运行状态待确认"
            EnvironmentPresentation.FAILED -> stringResource(R.string.environment_not_ready)
            EnvironmentPresentation.READY -> "环境已就绪"
        },
        supporting = environmentSupportingText(state),
        icon = if (presentation == EnvironmentPresentation.FAILED) {
            LucideR.drawable.lucide_ic_shield_alert
        } else {
            LucideR.drawable.lucide_ic_shield
        },
        tone = if (presentation == EnvironmentPresentation.FAILED) StatusBandTone.ERROR else StatusBandTone.NEUTRAL,
        action = if (presentation == EnvironmentPresentation.CHECKING) null else {
            {
                TextButton(
                    onClick = onCheckEnvironment,
                    enabled = !state.busy && !state.runtimeRefreshing,
                ) { Text("重新检查") }
            }
        },
    )
}

internal fun overviewSubscriptionIndex(state: MainUiState): Int {
    val selectedSubscriptionId = state.nodes.firstOrNull { it.id == state.selectedNodeId }?.subscriptionId
    return state.subscriptions.indexOfFirst { it.id == selectedSubscriptionId }.coerceAtLeast(0)
}

internal fun subscriptionUsedBytes(subscription: SubscriptionEntity): Long? {
    val upload = subscription.trafficUploadBytes
    val download = subscription.trafficDownloadBytes
    if (upload == null && download == null) return null
    val safeUpload = (upload ?: 0L).coerceAtLeast(0)
    val safeDownload = (download ?: 0L).coerceAtLeast(0)
    return safeUpload.coerceAtMost(Long.MAX_VALUE - safeDownload) + safeDownload
}

internal fun formatTrafficBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    val pattern = if (value >= 100 || unitIndex == 0) "0" else if (value >= 10) "0.0" else "0.00"
    return "${DecimalFormat(pattern, DecimalFormatSymbols(Locale.US)).format(value)} ${units[unitIndex]}"
}

internal fun subscriptionTimeProgress(subscription: SubscriptionEntity, nowMillis: Long): Float? {
    val start = subscription.subscriptionStartedAt?.let(::subscriptionTimestampMillis)?.takeIf { it > 0 }
        ?: return null
    val end = subscription.expiresAt?.let(::subscriptionTimestampMillis)?.takeIf { it > start } ?: return null
    return ((nowMillis - start).toDouble() / (end - start).toDouble()).coerceIn(0.0, 1.0).toFloat()
}

private fun formatSubscriptionPeriodStart(timestamp: Long?): String =
    timestamp?.takeIf { it > 0 }?.let(::formatSubscriptionPeriodDate) ?: "服务商未提供"

private fun formatSubscriptionPeriodEnd(timestamp: Long?): String = when {
    timestamp == 0L -> "长期有效"
    timestamp == null || timestamp < 0 -> "未提供"
    else -> formatSubscriptionPeriodDate(timestamp)
}

private fun formatSubscriptionPeriodDate(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(subscriptionTimestampMillis(timestamp)))

private fun subscriptionTimestampMillis(timestamp: Long): Long =
    if (timestamp >= 100_000_000_000L) timestamp else timestamp * 1_000

internal enum class DnsInterceptionState { ENABLED, STARTING, ATTENTION, DISABLED }

internal fun dnsInterceptionState(state: MainUiState): DnsInterceptionState {
    val status = state.status
    return when {
        // A foreground refresh can briefly return a running core without its
        // health fields. Keep the cached connection neutral until that
        // refresh has finished instead of showing a transient error state.
        state.busy ||
            status.actualState == "starting" ||
            (state.runtimeRefreshing && status.actualState == "running") ->
            DnsInterceptionState.STARTING
        status.actualState == "running" && state.dnsValidation == DnsValidationState.CHECKING -> DnsInterceptionState.STARTING
        status.actualState == "running" && state.dnsValidation == DnsValidationState.DEGRADED -> DnsInterceptionState.ATTENTION
        status.actualState == "running" && status.controllerReady != true -> DnsInterceptionState.ATTENTION
        status.actualState == "running" && !status.systemResolverAttached -> DnsInterceptionState.ATTENTION
        status.actualState == "running" && status.startupHealth != "healthy" -> DnsInterceptionState.ATTENTION
        status.actualState == "running" && status.androidDnsCacheFlush == "degraded" -> DnsInterceptionState.ATTENTION
        status.actualState == "running" && status.systemResolverAttached -> DnsInterceptionState.ENABLED
        else -> DnsInterceptionState.DISABLED
    }
}

internal fun dnsInterceptionLabel(state: MainUiState): String = when (dnsInterceptionState(state)) {
    DnsInterceptionState.ENABLED -> "DNS 接管正常"
    DnsInterceptionState.STARTING -> "正在准备 DNS"
    DnsInterceptionState.ATTENTION -> "DNS 需要检查"
    DnsInterceptionState.DISABLED -> "DNS 未接管"
}

internal fun dnsInterceptionDetail(state: MainUiState): String? {
    if (dnsInterceptionState(state) != DnsInterceptionState.ATTENTION) return null
    val status = state.status
    return when {
        state.dnsValidation == DnsValidationState.DEGRADED -> "当前节点 DNS 验证失败"
        status.controllerReady == false -> "本地控制服务不可用"
        status.controllerReady == null && status.actualState == "running" -> "本地控制服务状态待确认"
        !status.systemResolverAttached -> "系统解析器尚未附着"
        status.startupHealth != "healthy" -> "DNS 启动健康检查未通过"
        status.androidDnsCacheFlush == "degraded" -> "Android DNS 缓存清理失败"
        else -> null
    }
}

fun compatibilityText(state: MainUiState): String = when (val value = state.compatibility) {
    null -> "正在检查 Root 与模块"
    ModuleCompatibility.Compatible -> if (state.probe?.ok == true) "环境与 eBPF 正常" else runtimeHealthDetail(state)
    ModuleCompatibility.RootDenied -> "Root 授权被拒绝"
    ModuleCompatibility.Missing -> "未安装 AkihaLink-KSU 模块"
    is ModuleCompatibility.VersionMismatch -> "控制协议不匹配（模块 ${value.installed}）"
    is ModuleCompatibility.EditionMismatch -> "Edition 不匹配（模块 ${value.installed}）"
    ModuleCompatibility.CoreMismatch -> "模块核心提交或补丁集不匹配"
    is ModuleCompatibility.FeatureMismatch -> "模块缺少 ${value.missing.joinToString()}"
    is ModuleCompatibility.Error -> value.message
}

internal fun environmentSupportingText(state: MainUiState): String = when (environmentPresentation(state)) {
    EnvironmentPresentation.CHECKING -> "正在确认 Root、模块与 eBPF 状态"
    EnvironmentPresentation.READY -> "环境与 eBPF 正常"
    EnvironmentPresentation.STALE -> state.runtimeRefreshError
        ?.takeIf { it.isNotBlank() }
        ?.let { "无法确认最新状态：$it" }
        ?: "正在显示上次确认的运行状态"
    EnvironmentPresentation.FAILED -> compatibilityText(state)
}

internal fun runtimeHealthDetail(state: MainUiState): String {
    val error = state.status.lastError.orEmpty()
    return when {
        error.contains("DNS", true) && (error.contains("health", true) || error.contains("prewarm", true)) ->
            "代理 DNS 启动门禁失败，请检查当前节点"
        !state.status.systemResolverAttached && (error.contains("resolver", true) || error.contains("netd", true)) ->
            "未识别 Android 系统解析器，系统 DNS 接管尚未就绪"
        error.contains("eBPF", true) || error.contains("kernel", true) || error.contains("verifier", true) ->
            "eBPF/内核运行环境检查失败：${error.ifBlank { "请重新检查运行环境" }}"
        state.status.actualState == "starting" || state.status.actualState == "failed" ->
            "代理核心启动失败或残留未清理：${error.ifBlank { "请重新检查运行状态" }}"
        state.probe?.verifierError != null -> "eBPF/内核运行环境检查失败：${state.probe.verifierError}"
        else -> "正在执行 eBPF 与运行环境检查"
    }
}
