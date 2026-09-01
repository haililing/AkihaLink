package com.akiha.akihalink

import androidx.activity.compose.ReportDrawnWhen
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.akiha.akihalink.ui.AkihaTheme
import com.akiha.akihalink.ui.AppExclusionScreen
import com.akiha.akihalink.ui.HomeScreen
import com.akiha.akihalink.ui.ManagementScreen
import com.akiha.akihalink.ui.NodeScreen
import com.akiha.akihalink.ui.SubscriptionScreen
import com.composables.icons.lucide.R as LucideR

internal enum class MainDestination(
    val route: String,
    @StringRes val label: Int,
    @StringRes val accessibilityLabel: Int,
    @DrawableRes val icon: Int,
) {
    HOME("home", R.string.nav_overview, R.string.nav_home_a11y, LucideR.drawable.lucide_ic_house),
    NODES("nodes", R.string.nav_nodes, R.string.nav_nodes, LucideR.drawable.lucide_ic_waypoints),
    MANAGE("manage", R.string.nav_manage, R.string.nav_manage, LucideR.drawable.lucide_ic_settings_2),
}

private object DetailRoute {
    const val SUBSCRIPTIONS = "manage/subscriptions"
    const val APPS = "manage/apps"
}

private val ProductEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

internal fun NavHostController.navigateToMainDestination(destination: MainDestination) {
    if (popBackStack(destination.route, inclusive = false)) return
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AkihaLinkApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val mainRoute = MainDestination.entries.firstOrNull { it.route == currentRoute }

    ReportDrawnWhen { state.initialLoadComplete }
    LaunchedEffect(currentRoute) { viewModel.setAppExclusionVisible(currentRoute == DetailRoute.APPS) }
    DisposableEffect(Unit) { onDispose { viewModel.setAppExclusionVisible(false) } }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    AkihaTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (mainRoute != null) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        NavigationBar(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(24.dp)),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 0.dp,
                        ) {
                            MainDestination.entries.forEach { destination ->
                                NavigationBarItem(
                                    selected = destination == mainRoute,
                                    onClick = { navController.navigateToMainDestination(destination) },
                                    icon = {
                                        Icon(
                                            painterResource(destination.icon),
                                            stringResource(destination.accessibilityLabel),
                                        )
                                    },
                                    label = { Text(stringResource(destination.label)) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { contentPadding ->
            Box(Modifier.fillMaxSize().padding(contentPadding)) {
                NavHost(
                    navController = navController,
                    startDestination = MainDestination.HOME.route,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        fadeIn(tween(260, easing = ProductEasing)) +
                            scaleIn(initialScale = .985f, animationSpec = tween(300, easing = ProductEasing))
                    },
                    exitTransition = { fadeOut(tween(150, easing = ProductEasing)) },
                    popEnterTransition = {
                        fadeIn(tween(240, easing = ProductEasing)) +
                            slideInHorizontally(tween(280, easing = ProductEasing)) { -it / 12 }
                    },
                    popExitTransition = {
                        fadeOut(tween(180, easing = ProductEasing)) +
                            scaleOut(targetScale = .98f, animationSpec = tween(220, easing = ProductEasing))
                    },
                ) {
                    composable(MainDestination.HOME.route) {
                        HomeScreen(
                            state = state,
                            onTogglePower = viewModel::togglePower,
                            onModeSelected = viewModel::setMode,
                            onCheckEnvironment = viewModel::checkEnvironment,
                            onOpenNodes = { navController.navigateToMainDestination(MainDestination.NODES) },
                            onOpenManage = { navController.navigateToMainDestination(MainDestination.MANAGE) },
                        )
                    }
                    composable(MainDestination.NODES.route) {
                        NodeScreen(
                            state = state,
                            onSelectNode = viewModel::selectNode,
                            onSetSort = viewModel::setNodeSort,
                            onStartSpeedTest = viewModel::startSpeedTest,
                            onCancelSpeedTest = viewModel::cancelSpeedTest,
                            onToggleReverseDnsMapping = viewModel::toggleReverseDnsMapping,
                            onOpenSubscriptions = { navController.navigate(DetailRoute.SUBSCRIPTIONS) },
                        )
                    }
                    composable(MainDestination.MANAGE.route) {
                        ManagementScreen(
                            state = state,
                            onOpenSubscriptions = { navController.navigate(DetailRoute.SUBSCRIPTIONS) },
                            onOpenApps = { navController.navigate(DetailRoute.APPS) },
                            onToggleReverseDnsMapping = viewModel::toggleReverseDnsMapping,
                            onToggleHotspotProxy = viewModel::toggleHotspotProxy,
                        )
                    }
                    composable(
                        route = DetailRoute.SUBSCRIPTIONS,
                        enterTransition = {
                            fadeIn(tween(220, easing = ProductEasing)) +
                                slideInHorizontally(tween(320, easing = ProductEasing)) { it / 5 }
                        },
                        exitTransition = { fadeOut(tween(160, easing = ProductEasing)) },
                        popEnterTransition = { fadeIn(tween(220, easing = ProductEasing)) },
                        popExitTransition = {
                            fadeOut(tween(200, easing = ProductEasing)) +
                                slideOutHorizontally(tween(300, easing = ProductEasing)) { it / 4 }
                        },
                    ) {
                        SubscriptionScreen(
                            state = state,
                            onBack = navController::popBackStack,
                            onAdd = viewModel::addSubscription,
                            onUpdate = viewModel::updateSubscription,
                            onSetEnabled = viewModel::setSubscriptionEnabled,
                            onDelete = viewModel::deleteSubscription,
                        )
                    }
                    composable(
                        route = DetailRoute.APPS,
                        enterTransition = {
                            fadeIn(tween(220, easing = ProductEasing)) +
                                slideInHorizontally(tween(320, easing = ProductEasing)) { it / 5 }
                        },
                        popExitTransition = {
                            fadeOut(tween(200, easing = ProductEasing)) +
                                slideOutHorizontally(tween(300, easing = ProductEasing)) { it / 4 }
                        },
                    ) {
                        AppExclusionScreen(
                            state = state,
                            onBack = navController::popBackStack,
                            onToggleDraft = viewModel::toggleExcludedAppDraft,
                            onApply = viewModel::applyExcludedAppDraft,
                            onDiscard = viewModel::discardExcludedAppDraft,
                            onRetryLoad = viewModel::retryInstalledApps,
                        )
                    }
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }
}
