package com.akiha.akihalink.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.akiha.akihalink.AppTypeFilter
import com.akiha.akihalink.InstalledApp
import com.akiha.akihalink.InstalledAppsLoadState
import com.akiha.akihalink.MainUiState
import com.akiha.akihalink.R
import com.akiha.akihalink.filterInstalledApps

@Composable
fun AppExclusionScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onToggleDraft: (Int) -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onRetryLoad: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var typeFilter by rememberSaveable { mutableStateOf(AppTypeFilter.ALL) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val iconLoader = remember(context) { InstalledAppIconLoader(context) }
    val density = LocalDensity.current
    val iconDensityDpi = LocalResources.current.displayMetrics.densityDpi
    val iconSizePx = with(density) { 44.dp.roundToPx() }
    val persistedUids = remember(state.installedApps) {
        state.installedApps.filterTo(mutableListOf()) { it.excluded }.mapTo(hashSetOf()) { it.uid }
    }
    val draftUids = state.excludedAppDraftUids ?: persistedUids
    val hasChanges = draftUids != persistedUids
    val changedCount = remember(draftUids, persistedUids) {
        draftUids.count { it !in persistedUids } + persistedUids.count { it !in draftUids }
    }
    val apps = remember(state.installedApps, query, typeFilter) {
        filterInstalledApps(state.installedApps, typeFilter, query)
    }
    val requestBack = {
        if (hasChanges) confirmDiscard = true else {
            onDiscard()
            onBack()
        }
    }

    BackHandler { requestBack() }
    PhoneContent {
        Column(Modifier.fillMaxSize()) {
            PageHeading(
                title = "应用直连",
                leading = { AkihaIconButton("返回", LucideR.drawable.lucide_ic_arrow_left, { requestBack() }) },
            )
            if (state.installedApps.isNotEmpty()) AppSearchField(query, onQueryChange = { query = it })
            if (state.installedApps.isEmpty()) {
                EmptyState(
                    title = when (state.installedAppsLoadState) {
                        InstalledAppsLoadState.LOADED -> stringResource(R.string.apps_empty)
                        InstalledAppsLoadState.FAILED -> "应用列表加载失败"
                        InstalledAppsLoadState.NOT_REQUESTED,
                        InstalledAppsLoadState.LOADING -> stringResource(R.string.apps_loading)
                    },
                    supporting = when (state.installedAppsLoadState) {
                        InstalledAppsLoadState.LOADED -> stringResource(R.string.apps_empty_supporting)
                        InstalledAppsLoadState.FAILED -> state.installedAppsLoadError ?: "无法读取已安装应用，请重试"
                        InstalledAppsLoadState.NOT_REQUESTED,
                        InstalledAppsLoadState.LOADING -> null
                    },
                    icon = LucideR.drawable.lucide_ic_app_window,
                    modifier = Modifier.weight(1f),
                    action = if (state.installedAppsLoadState == InstalledAppsLoadState.FAILED) {
                        { TextButton(onClick = onRetryLoad) { Text("重试") } }
                    } else null,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AkihaSpacing.page, vertical = AkihaSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AkihaSpacing.xs),
                ) {
                    AppTypeFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = typeFilter == filter,
                            onClick = { typeFilter = filter },
                            label = {
                                Text(
                                    when (filter) {
                                        AppTypeFilter.ALL -> stringResource(R.string.apps_filter_all)
                                        AppTypeFilter.USER -> stringResource(R.string.apps_filter_user)
                                        AppTypeFilter.SYSTEM -> stringResource(R.string.apps_filter_system)
                                    },
                                )
                            },
                            enabled = !state.busy,
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = AkihaSpacing.sm),
                ) {
                    if (apps.isEmpty()) {
                        item {
                            EmptyState(
                                stringResource(R.string.apps_no_match),
                                supporting = stringResource(R.string.apps_no_match_supporting),
                                icon = LucideR.drawable.lucide_ic_search,
                            )
                        }
                    }
                    items(apps, key = { it.uid }) { app ->
                        AppToggleRow(
                            app = app,
                            checked = app.uid in draftUids,
                            enabled = !state.busy,
                            iconLoader = iconLoader,
                            iconDensityDpi = iconDensityDpi,
                            iconSizePx = iconSizePx,
                            onToggle = { onToggleDraft(app.uid) },
                        )
                    }
                }
            }
            AnimatedVisibility(hasChanges) {
                ExclusionApplyBar(draftUids.size, changedCount, !state.busy, onApply)
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            icon = { Icon(painterResource(LucideR.drawable.lucide_ic_triangle_alert), contentDescription = null) },
            title = { Text(stringResource(R.string.apps_discard_title)) },
            text = { Text(stringResource(R.string.apps_discard_message, changedCount)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDiscard()
                    onBack()
                }) { Text(stringResource(R.string.action_discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_continue_editing)) }
            },
            shape = MaterialTheme.shapes.extraLarge,
        )
    }
}

@Composable
private fun AppSearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = AkihaSpacing.page, vertical = AkihaSpacing.xs),
        placeholder = { Text(stringResource(R.string.apps_search_hint)) },
        leadingIcon = { Icon(painterResource(LucideR.drawable.lucide_ic_search), contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            { AkihaIconButton(stringResource(R.string.search_clear), LucideR.drawable.lucide_ic_x, { onQueryChange("") }) }
        } else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
private fun AppToggleRow(
    app: InstalledApp,
    checked: Boolean,
    enabled: Boolean,
    iconLoader: InstalledAppIconLoader,
    iconDensityDpi: Int,
    iconSizePx: Int,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AkihaSpacing.page, vertical = AkihaSpacing.xxs)
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle),
        color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AkihaSpacing.md, vertical = AkihaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AkihaSpacing.md),
        ) {
            InstalledAppIcon(app, iconLoader, iconDensityDpi, iconSizePx, checked)
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.74f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (app.packageCount > 1) {
                    Text(
                        "共享同一 UID，切换会同时影响这 ${app.packageCount} 个应用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
private fun ExclusionApplyBar(excludedCount: Int, changedCount: Int, enabled: Boolean, onApply: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AkihaSpacing.page, vertical = AkihaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AkihaSpacing.sm),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.apps_excluded_count, excludedCount), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.apps_pending_count, changedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Button(onClick = onApply, enabled = enabled) {
                Icon(painterResource(LucideR.drawable.lucide_ic_check), contentDescription = null)
                Text(stringResource(R.string.action_apply), modifier = Modifier.padding(start = AkihaSpacing.xs))
            }
        }
    }
}
