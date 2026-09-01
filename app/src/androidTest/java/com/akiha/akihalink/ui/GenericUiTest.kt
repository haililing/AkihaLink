package com.akiha.akihalink.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.SemanticsActions
import com.akiha.akihalink.InstalledApp
import com.akiha.akihalink.InstalledAppsLoadState
import com.akiha.akihalink.MainUiState
import com.akiha.akihalink.config.ProxyMode
import com.akiha.akihalink.data.ProxyNodeEntity
import com.akiha.akihalink.data.SubscriptionEntity
import com.akiha.akihalink.root.ModuleCompatibility
import com.akiha.akihalink.root.ModuleStatus
import com.akiha.akihalink.root.ProbeResult
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GenericUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun homeKeepsBenchmarkSemanticsAndModeAction() {
        var globalSelected = false
        compose.setContent {
            AkihaTheme {
                HomeScreen(
                    state = sampleState,
                    onTogglePower = {},
                    onModeSelected = { globalSelected = it == ProxyMode.GLOBAL },
                    onCheckEnvironment = {},
                    onOpenNodes = {},
                )
            }
        }

        compose.onNodeWithContentDescription("关闭代理").assertIsDisplayed()
        compose.onNodeWithText("全局").performClick()
        compose.runOnIdle { assertTrue(globalSelected) }
    }

    @Test
    fun disconnectedHomeExposesStartActionAndStatus() {
        compose.setContent {
            AkihaTheme {
                HomeScreen(
                    state = sampleState.copy(
                        status = sampleState.status.copy(actualState = "stopped"),
                    ),
                    onTogglePower = {},
                    onModeSelected = {},
                    onCheckEnvironment = {},
                    onOpenNodes = {},
                )
            }
        }

        compose.onNodeWithContentDescription("启动代理").assertIsDisplayed()
        compose.onNodeWithText("未连接").assertIsDisplayed()
    }

    @Test
    fun checkingEnvironmentUsesNeutralCopyUntilFailureIsConfirmed() {
        compose.setContent {
            AkihaTheme {
                HomeScreen(
                    state = MainUiState(runtimeRefreshing = true, runtimeStale = true),
                    onTogglePower = {},
                    onModeSelected = {},
                    onCheckEnvironment = {},
                    onOpenNodes = {},
                )
            }
        }

        compose.onNodeWithText("正在检查运行环境").assertIsDisplayed()
        compose.onNodeWithText("运行环境未就绪").assertIsNotDisplayed()
    }

    @Test
    fun confirmedEnvironmentFailureUsesAttentionCopy() {
        compose.setContent {
            AkihaTheme {
                HomeScreen(
                    state = MainUiState(compatibility = ModuleCompatibility.RootDenied),
                    onTogglePower = {},
                    onModeSelected = {},
                    onCheckEnvironment = {},
                    onOpenNodes = {},
                )
            }
        }

        compose.onNodeWithText("运行环境未就绪").assertIsDisplayed()
        compose.onNodeWithText("Root 授权被拒绝").assertIsDisplayed()
    }

    @Test
    fun unhealthyHomeShowsAttentionState() {
        compose.setContent {
            AkihaTheme {
                HomeScreen(
                    state = sampleState.copy(
                        status = sampleState.status.copy(systemResolverAttached = false),
                    ),
                    onTogglePower = {},
                    onModeSelected = {},
                    onCheckEnvironment = {},
                    onOpenNodes = {},
                )
            }
        }

        compose.onNodeWithText("需要处理").assertIsDisplayed()
        compose.onNodeWithText("DNS 需要检查").assertIsDisplayed()
    }

    @Test
    fun failedInstalledAppLoadCanRetryWithoutLosingTheScreen() {
        var retried = false
        compose.setContent {
            AkihaTheme {
                AppExclusionScreen(
                    state = MainUiState(
                        installedAppsLoadState = InstalledAppsLoadState.FAILED,
                        installedAppsLoadError = "包管理器暂时不可用",
                    ),
                    onBack = {},
                    onToggleDraft = {},
                    onApply = {},
                    onDiscard = {},
                    onRetryLoad = { retried = true },
                )
            }
        }

        compose.onNodeWithText("应用列表加载失败").assertIsDisplayed()
        compose.onNodeWithText("包管理器暂时不可用").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun healthyHomeKeepsConnectionDetailsConcise() {
        compose.setContent {
            AkihaTheme {
                HomeScreen(
                    state = sampleState,
                    onTogglePower = {},
                    onModeSelected = {},
                    onCheckEnvironment = {},
                    onOpenNodes = {},
                )
            }
        }

        compose.onNodeWithText("日本|高速|0.5x").assertIsDisplayed()
        compose.onNodeWithText("anytls").assertIsNotDisplayed()
        compose.onNodeWithText("DNS 接管正常").assertIsNotDisplayed()
        compose.onNodeWithText("AkihaLink").assertIsNotDisplayed()
        compose.onNodeWithText("下载").assertIsNotDisplayed()
        compose.onNodeWithText("上传").assertIsNotDisplayed()
        compose.onNodeWithText("运行").assertIsNotDisplayed()
        compose.onNodeWithText("流量正在安全转发").assertIsNotDisplayed()
    }

    @Test
    fun managementDoesNotExposeRuntimeEnvironment() {
        compose.setContent {
            AkihaTheme {
                ManagementScreen(
                    state = sampleState,
                    onOpenSubscriptions = {},
                    onOpenApps = {},
                    onToggleReverseDnsMapping = {},
                    onToggleHotspotProxy = {},
                )
            }
        }

        compose.onNodeWithText("网络策略").assertIsDisplayed()
        compose.onNodeWithText("运行环境").assertIsNotDisplayed()
    }

    @Test
    fun homeShowsCurrentSubscriptionOverview() {
        compose.setContent {
            AkihaTheme {
                HomeScreen(
                    state = sampleState.copy(
                        subscriptions = listOf(
                            subscription.copy(
                                createdAt = 1_700_000_000_000,
                                trafficUploadBytes = 1_073_741_824,
                                trafficDownloadBytes = 2_147_483_648,
                                trafficTotalBytes = 10_737_418_240,
                                expiresAt = 1_800_000_000_000,
                                subscriptionStartedAt = 1_700_000_000_000,
                            ),
                        ),
                        subscriptionNodeCounts = mapOf(subscription.id to 2),
                    ),
                    onTogglePower = {},
                    onModeSelected = {},
                    onCheckEnvironment = {},
                    onOpenNodes = {},
                )
            }
        }

        compose.onNodeWithText("当前线路").assertIsDisplayed()
        compose.onNodeWithText("日常线路").assertIsDisplayed()
        compose.onNodeWithText("3.00 GB / 10.0 GB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("开始").assertIsDisplayed()
        compose.onNodeWithText("结束").assertIsDisplayed()
        compose.onNodeWithText("刷新周期").assertIsNotDisplayed()
    }

    @Test
    fun homeShowsTheSelectedNodesSubscription() {
        val secondSubscription = subscription.copy(
            id = "second-subscription",
            name = "备用线路",
        )
        val secondNode = nodes.last().copy(
            id = "second-node",
            subscriptionId = secondSubscription.id,
        )
        compose.setContent {
            AkihaTheme {
                HomeScreen(
                    state = sampleState.copy(
                        subscriptions = listOf(subscription, secondSubscription),
                        nodes = nodes + secondNode,
                        selectedNodeId = secondNode.id,
                        subscriptionNodeCounts = mapOf(
                            subscription.id to 2,
                            secondSubscription.id to 1,
                        ),
                    ),
                    onTogglePower = {},
                    onModeSelected = {},
                    onCheckEnvironment = {},
                    onOpenNodes = {},
                )
            }
        }

        compose.onNodeWithText("备用线路").assertIsDisplayed()
    }

    @Test
    fun nodeSearchFiltersByName() {
        compose.setContent {
            AkihaTheme {
                NodeScreen(sampleState.copy(selectedNodeId = null), {}, {}, {}, {}, {}, {})
            }
        }

        compose.onNodeWithText("搜索地区、名称或协议").performTextInput("新加坡")
        compose.onNode(hasText("新加坡") and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("日本").assertIsNotDisplayed()
    }

    @Test
    fun nodeCardsUseTwoColumnsAndExposeSelection() {
        compose.setContent {
            AkihaTheme {
                NodeScreen(sampleState, {}, {}, {}, {}, {}, {})
            }
        }

        compose.onNodeWithText("日本").assertIsDisplayed()
        compose.onNodeWithText("新加坡").assertIsDisplayed()
        compose.onNodeWithText("高速").assertIsDisplayed()
        compose.onNodeWithText("专线").assertIsDisplayed()
        compose.onNodeWithContentDescription("当前节点").assertIsDisplayed()

        val firstTop = compose.onNodeWithText("日本").fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithText("新加坡").fetchSemanticsNode().boundsInRoot.top
        assertEquals(firstTop, secondTop, 1f)
    }

    @Test
    fun emptyNodeListOffersSubscriptionManagement() {
        compose.setContent {
            AkihaTheme {
                NodeScreen(sampleState.copy(nodes = emptyList()), {}, {}, {}, {}, {}, {})
            }
        }

        compose.onNodeWithText("还没有节点").assertIsDisplayed()
        compose.onNodeWithText("添加订阅").assertIsDisplayed()
    }

    @Test
    fun subscriptionDeleteRequiresConfirmation() {
        compose.setContent {
            AkihaTheme {
                SubscriptionScreen(sampleState, { _, _ -> }, {}, { _, _ -> }, {})
            }
        }

        compose.onNodeWithContentDescription("管理订阅 日常线路").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("“日常线路”及其节点将被移除。此操作无法撤销。").assertIsDisplayed()
    }

    @Test
    fun subscriptionSwitchReportsRequestedState() {
        var requestedSubscription: SubscriptionEntity? = null
        var requestedEnabled = true
        compose.setContent {
            AkihaTheme {
                SubscriptionScreen(
                    state = sampleState,
                    onAdd = { _, _ -> },
                    onUpdate = {},
                    onSetEnabled = { subscription, enabled ->
                        requestedSubscription = subscription
                        requestedEnabled = enabled
                    },
                    onDelete = {},
                )
            }
        }

        compose.onNodeWithContentDescription("关闭订阅 日常线路").performClick()

        compose.runOnIdle {
            assertEquals(subscription, requestedSubscription)
            assertFalse(requestedEnabled)
        }
    }

    @Test
    fun disabledSubscriptionKeepsStoredNodeCountAndActions() {
        compose.setContent {
            AkihaTheme {
                SubscriptionScreen(
                    state = sampleState.copy(
                        subscriptions = listOf(subscription.copy(isEnabled = false)),
                        nodes = emptyList(),
                    ),
                    onAdd = { _, _ -> },
                    onUpdate = {},
                    onSetEnabled = { _, _ -> },
                    onDelete = {},
                )
            }
        }

        compose.onNodeWithText("2 个节点 · 已暂停", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("启用订阅 日常线路").assertIsDisplayed()
        compose.onNodeWithContentDescription("订阅操作").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("管理订阅 日常线路").performClick()
        compose.onNodeWithText("立即更新").assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsDisplayed()
    }

    @Test
    fun appExclusionShowsApplyBarOnlyForDraftChanges() {
        compose.setContent {
            AkihaTheme {
                AppExclusionScreen(
                    state = sampleState.copy(excludedAppDraftUids = setOf(10_101)),
                    onBack = {},
                    onToggleDraft = {},
                    onApply = {},
                    onDiscard = {},
                )
            }
        }

        compose.onNodeWithText("1 项待应用").assertIsDisplayed()
        compose.onNodeWithText("应用").assertIsDisplayed()
    }

    private companion object {
        val subscription = SubscriptionEntity(
            id = "subscription",
            name = "日常线路",
            encryptedUrl = "encrypted",
            lastUpdatedAt = System.currentTimeMillis(),
        )
        val nodes = listOf(
            ProxyNodeEntity(
                id = "tokyo",
                subscriptionId = subscription.id,
                fingerprint = "tokyo-fingerprint",
                name = "🇯🇵 东京高速 0.5x 入口 01",
                protocol = "anytls",
                endpoint = "example.invalid:443",
                encryptedOutbound = "encrypted",
            ),
            ProxyNodeEntity(
                id = "singapore",
                subscriptionId = subscription.id,
                fingerprint = "singapore-fingerprint",
                name = "🇸🇬 新加坡专线 2x 中转",
                protocol = "shadowsocks",
                endpoint = "example.invalid:443",
                encryptedOutbound = "encrypted",
            ),
        )
        val sampleState = MainUiState(
            compatibility = ModuleCompatibility.Compatible,
            probe = ProbeResult(ok = true),
            status = ModuleStatus(
                actualState = "running",
                controllerReady = true,
                ebpfAttached = true,
                startupHealth = "healthy",
                systemResolverAttached = true,
                androidDnsCacheFlush = "ok",
            ),
            subscriptions = listOf(subscription),
            subscriptionNodeCounts = mapOf(subscription.id to nodes.size),
            nodes = nodes,
            installedApps = listOf(InstalledApp(10_101, "com.example.music", "音乐", excluded = false)),
            selectedNodeId = nodes.first().id,
            mode = ProxyMode.RULE,
            initialLoadComplete = true,
        )
    }
}
