package com.akiha.akihalink.ui

import com.akiha.akihalink.MainUiState
import com.akiha.akihalink.DnsValidationState
import com.akiha.akihalink.data.ProxyNodeEntity
import com.akiha.akihalink.data.SubscriptionEntity
import com.akiha.akihalink.root.ModuleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenStateTest {
    @Test
    fun backgroundRuntimeRefreshDoesNotDisablePrimaryControls() {
        assertTrue(runtimeControlsEnabled(MainUiState(runtimeRefreshing = true)))
        assertFalse(runtimeControlsEnabled(MainUiState(busy = true, runtimeRefreshing = true)))
    }

    @Test
    fun classifiesRuntimeEnvironmentFailuresPrecisely() {
        fun detail(error: String) = runtimeHealthDetail(
            MainUiState(status = ModuleStatus(actualState = "failed", lastError = error)),
        )

        assertTrue(detail("proxy DNS startup health check failed").startsWith("代理 DNS 启动门禁失败"))
        assertTrue(detail("verified root netd process was not found").startsWith("未识别 Android 系统解析器"))
        assertTrue(detail("eBPF verifier rejected program").startsWith("eBPF/内核运行环境检查失败"))
        assertTrue(detail("Core startup health timed out").startsWith("代理核心启动失败或残留未清理"))
    }

    @Test
    fun reportsDnsInterceptionOnlyAfterResolverAndHealthAreReady() {
        val enabled = MainUiState(
            status = ModuleStatus(
                actualState = "running",
                controllerReady = true,
                startupHealth = "healthy",
                systemResolverAttached = true,
                androidDnsCacheFlush = "ok",
            ),
        )
        assertTrue(dnsInterceptionState(enabled) == DnsInterceptionState.ENABLED)
        assertTrue(dnsInterceptionLabel(enabled).contains("正常"))

        val checking = enabled.copy(dnsValidation = DnsValidationState.CHECKING)
        assertTrue(dnsInterceptionState(checking) == DnsInterceptionState.STARTING)
        assertTrue(dnsInterceptionLabel(checking).contains("准备"))

        val refreshing = enabled.copy(
            runtimeRefreshing = true,
            status = enabled.status.copy(controllerReady = null),
        )
        assertTrue(dnsInterceptionState(refreshing) == DnsInterceptionState.STARTING)
        assertTrue(dnsInterceptionDetail(refreshing) == null)
        assertTrue(
            dnsInterceptionState(
                MainUiState(runtimeRefreshing = true, status = ModuleStatus(actualState = "stopped")),
            ) == DnsInterceptionState.DISABLED,
        )

        val degraded = enabled.copy(dnsValidation = DnsValidationState.DEGRADED)
        assertTrue(dnsInterceptionState(degraded) == DnsInterceptionState.ATTENTION)
        assertEquals("当前节点 DNS 验证失败", dnsInterceptionDetail(degraded))

        val resolverMissing = enabled.copy(
            status = enabled.status.copy(systemResolverAttached = false),
        )
        assertTrue(dnsInterceptionState(resolverMissing) == DnsInterceptionState.ATTENTION)

        val healthDegraded = enabled.copy(
            status = enabled.status.copy(startupHealth = "degraded"),
        )
        assertTrue(dnsInterceptionState(healthDegraded) == DnsInterceptionState.ATTENTION)

        val cacheDegraded = enabled.copy(
            status = enabled.status.copy(androidDnsCacheFlush = "degraded"),
        )
        assertTrue(dnsInterceptionState(cacheDegraded) == DnsInterceptionState.ATTENTION)

        val controllerUnavailable = enabled.copy(
            status = enabled.status.copy(controllerReady = false),
        )
        assertTrue(dnsInterceptionState(controllerUnavailable) == DnsInterceptionState.ATTENTION)
        assertEquals("本地控制服务不可用", dnsInterceptionDetail(controllerUnavailable))

        val controllerUnknown = enabled.copy(
            status = enabled.status.copy(controllerReady = null),
        )
        assertTrue(dnsInterceptionState(controllerUnknown) == DnsInterceptionState.ATTENTION)
        assertEquals("本地控制服务状态待确认", dnsInterceptionDetail(controllerUnknown))

        val starting = MainUiState(
            busy = true,
            status = ModuleStatus(actualState = "starting"),
        )
        assertTrue(dnsInterceptionState(starting) == DnsInterceptionState.STARTING)
        assertTrue(
            dnsInterceptionState(MainUiState(status = ModuleStatus(actualState = "stopped"))) ==
                DnsInterceptionState.DISABLED,
        )
    }

    @Test
    fun overviewStartsOnTheSelectedNodesSubscription() {
        val first = subscription("first")
        val second = subscription("second")
        val state = MainUiState(
            subscriptions = listOf(first, second),
            nodes = listOf(node("first"), node("second")),
            selectedNodeId = "second:node",
        )

        assertEquals(1, overviewSubscriptionIndex(state))
        assertEquals(0, overviewSubscriptionIndex(state.copy(selectedNodeId = "missing")))
        assertEquals(0, overviewSubscriptionIndex(state.copy(subscriptions = emptyList())))
    }

    @Test
    fun combinesUploadAndDownloadUsageWithoutOverflow() {
        assertEquals(
            3_221_225_472L,
            subscriptionUsedBytes(
                subscription("traffic").copy(
                    trafficUploadBytes = 1_073_741_824,
                    trafficDownloadBytes = 2_147_483_648,
                ),
            ),
        )
        assertEquals(
            Long.MAX_VALUE,
            subscriptionUsedBytes(
                subscription("overflow").copy(
                    trafficUploadBytes = Long.MAX_VALUE,
                    trafficDownloadBytes = Long.MAX_VALUE,
                ),
            ),
        )
        assertEquals("1.50 GB", formatTrafficBytes(1_610_612_736))
    }

    @Test
    fun calculatesSubscriptionTimeProgressOnlyFromProviderStartTime() {
        val start = 1_700_000_000_000L
        val end = start + 10_000L
        val timed = subscription("timed").copy(subscriptionStartedAt = start, expiresAt = end)

        assertEquals(.5f, subscriptionTimeProgress(timed, start + 5_000L))
        assertEquals(0f, subscriptionTimeProgress(timed, start - 1L))
        assertEquals(1f, subscriptionTimeProgress(timed, end + 1L))
        assertEquals(null, subscriptionTimeProgress(timed.copy(expiresAt = 0), start + 5_000L))
        assertEquals(
            null,
            subscriptionTimeProgress(
                timed.copy(subscriptionStartedAt = null, createdAt = start),
                start + 5_000L,
            ),
        )
    }

    private fun subscription(id: String) = SubscriptionEntity(
        id = id,
        name = id,
        encryptedUrl = "encrypted",
    )

    private fun node(subscriptionId: String) = ProxyNodeEntity(
        id = "$subscriptionId:node",
        subscriptionId = subscriptionId,
        fingerprint = "$subscriptionId-fingerprint",
        name = subscriptionId,
        protocol = "trojan",
        endpoint = "example.com:443",
        encryptedOutbound = "encrypted",
    )
}
