package com.akiha.akihalink.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.akiha.akihalink.MainUiState
import com.akiha.akihalink.proxyMayBeActive
import com.composables.icons.lucide.R as LucideR

@Composable
fun ManagementScreen(
    state: MainUiState,
    onOpenSubscriptions: () -> Unit,
    onOpenApps: () -> Unit,
    onToggleReverseDnsMapping: () -> Unit,
    onToggleHotspotProxy: () -> Unit,
) {
    val directCount = state.excludedAppDraftUids?.size
        ?: state.installedApps.takeIf { it.isNotEmpty() }?.count { it.excluded }
        ?: state.excludedApps.map { it.uid }.distinct().size

    PhoneContent {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            PageHeading(title = "管理")
            Column(
                Modifier.padding(horizontal = AkihaSpacing.page),
                verticalArrangement = Arrangement.spacedBy(AkihaSpacing.sm),
            ) {
                ManagementCard(
                    title = "订阅与线路",
                    supporting = if (state.subscriptions.isEmpty()) "还没有添加节点来源"
                    else "${state.subscriptions.size} 个来源 · ${state.nodes.size} 个节点",
                    icon = LucideR.drawable.lucide_ic_layers,
                    onClick = onOpenSubscriptions,
                )
                ManagementCard(
                    title = "应用直连",
                    supporting = if (directCount == 0) "所有应用遵循当前代理模式" else "$directCount 个应用绕过代理",
                    icon = LucideR.drawable.lucide_ic_shield,
                    onClick = onOpenApps,
                )

                Spacer(Modifier.height(AkihaSpacing.sm))
                SectionHeading("网络策略")
                SettingCard(
                    title = "域名映射",
                    supporting = "将域名还原为目标 IP，改善规则匹配",
                    icon = LucideR.drawable.lucide_ic_globe,
                    checked = state.reverseDnsMappingEnabled,
                    enabled = !state.busy,
                    onCheckedChange = { onToggleReverseDnsMapping() },
                )
                SettingCard(
                    title = "Wi-Fi 热点代理",
                    supporting = when {
                        !state.hotspotProxyEnabled -> "关闭时热点保持系统直连"
                        state.status.proxyMayBeActive() -> "热点设备使用当前节点和代理模式"
                        else -> "已保存；开启首页代理后生效"
                    },
                    icon = LucideR.drawable.lucide_ic_wifi,
                    checked = state.hotspotProxyEnabled,
                    enabled = !state.busy,
                    onCheckedChange = { onToggleHotspotProxy() },
                )

                Spacer(Modifier.height(AkihaSpacing.xl))
            }
        }
    }
}

@Composable
private fun ManagementCard(
    title: String,
    supporting: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(AkihaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AkihaSpacing.md),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                painterResource(LucideR.drawable.lucide_ic_chevron_right),
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    supporting: String,
    @DrawableRes icon: Int,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(AkihaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AkihaSpacing.md),
        ) {
            Icon(painterResource(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, onCheckedChange, enabled = enabled)
        }
    }
}
