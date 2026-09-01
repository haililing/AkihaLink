package com.akiha.akihalink.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.akiha.akihalink.InstalledApp
import com.akiha.akihalink.MainUiState
import com.akiha.akihalink.config.ProxyMode
import com.akiha.akihalink.data.ExcludedAppEntity
import com.akiha.akihalink.data.NodeSort
import com.akiha.akihalink.data.ProxyNodeEntity
import com.akiha.akihalink.data.SubscriptionEntity
import com.akiha.akihalink.root.ModuleCompatibility
import com.akiha.akihalink.root.ModuleStatus
import com.akiha.akihalink.root.ProbeResult
import com.akiha.akihalink.speedtest.NodeLatencyResult
import com.akiha.akihalink.speedtest.NodeLatencyStatus

@Preview(name = "360 Light", widthDp = 360, heightDp = 800, showBackground = true, fontScale = 1.0f)
@Preview(name = "412 Light Large", widthDp = 412, heightDp = 915, showBackground = true, fontScale = 1.3f)
@Preview(
    name = "360 Dark Extra Large",
    widthDp = 360,
    heightDp = 800,
    showBackground = true,
    fontScale = 1.5f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "412 Dark",
    widthDp = 412,
    heightDp = 915,
    showBackground = true,
    fontScale = 1.0f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class AkihaPreviews

private val previewSubscription = SubscriptionEntity(
    id = "subscription-preview",
    name = "日常线路",
    encryptedUrl = "preview",
    lastUpdatedAt = System.currentTimeMillis(),
    createdAt = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1_000,
    trafficUploadBytes = 2_500_000_000,
    trafficDownloadBytes = 24_800_000_000,
    trafficTotalBytes = 107_374_182_400,
    expiresAt = System.currentTimeMillis() + 16L * 24 * 60 * 60 * 1_000,
    refreshIntervalHours = 24,
    subscriptionStartedAt = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1_000,
)

private val previewNodes = listOf(
    ProxyNodeEntity(
        id = "node-a",
        subscriptionId = previewSubscription.id,
        fingerprint = "fingerprint-a",
        name = "东京入口 01",
        protocol = "anytls",
        endpoint = "example.invalid:443",
        encryptedOutbound = "preview",
    ),
    ProxyNodeEntity(
        id = "node-b",
        subscriptionId = previewSubscription.id,
        fingerprint = "fingerprint-b",
        name = "新加坡中转",
        protocol = "shadowsocks",
        endpoint = "example.invalid:443",
        encryptedOutbound = "preview",
    ),
)

private val previewState = MainUiState(
    compatibility = ModuleCompatibility.Compatible,
    probe = ProbeResult(ok = true),
    status = ModuleStatus(
        actualState = "running",
        startedAt = System.currentTimeMillis() / 1_000 - 5_420,
        ebpfAttached = true,
        startupHealth = "healthy",
        systemResolverAttached = true,
        androidDnsCacheFlush = "ok",
    ),
    subscriptions = listOf(previewSubscription),
    subscriptionNodeCounts = mapOf(previewSubscription.id to previewNodes.size),
    nodes = previewNodes,
    excludedApps = listOf(
        ExcludedAppEntity(
            userId = 0,
            packageName = "com.example.bank",
            signingCertificateSha256 = "preview",
            uid = 10_102,
            label = "银行",
        ),
    ),
    selectedNodeId = previewNodes.first().id,
    mode = ProxyMode.RULE,
    nodeSort = NodeSort.LATENCY,
    nodeLatencyResults = mapOf(
        "node-a" to NodeLatencyResult(NodeLatencyStatus.AVAILABLE, 42, System.currentTimeMillis()),
        "node-b" to NodeLatencyResult(NodeLatencyStatus.UNSTABLE, 108, System.currentTimeMillis()),
    ),
    installedApps = listOf(
        InstalledApp(10_101, "com.example.music", "音乐", excluded = false),
        InstalledApp(10_102, "com.example.bank", "银行", excluded = true),
        InstalledApp(10_103, "com.example.reader", "阅读", excluded = false),
        InstalledApp(10_104, "com.android.settings", "系统设置", excluded = false, isSystem = true),
    ),
    initialLoadComplete = true,
)

@AkihaPreviews
@Composable
private fun HomePreview() {
    AkihaTheme {
        HomeScreen(previewState, {}, {}, {}, {})
    }
}

@AkihaPreviews
@Composable
private fun NodesPreview() {
    AkihaTheme {
        NodeScreen(previewState, {}, {}, {}, {}, {}, {})
    }
}

@AkihaPreviews
@Composable
private fun SubscriptionsPreview() {
    AkihaTheme {
        SubscriptionScreen(previewState, { _, _ -> }, {}, { _, _ -> }, {})
    }
}

@AkihaPreviews
@Composable
private fun AppExclusionPreview() {
    AkihaTheme {
        AppExclusionScreen(
            state = previewState.copy(excludedAppDraftUids = setOf(10_101, 10_102)),
            onBack = {},
            onToggleDraft = {},
            onApply = {},
            onDiscard = {},
        )
    }
}
