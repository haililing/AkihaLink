package com.akiha.akihalink.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR

const val PHONE_CONTENT_MAX_WIDTH_DP = 720

@Composable
fun PhoneContent(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxWidth().widthIn(max = PHONE_CONTENT_MAX_WIDTH_DP.dp)) { content() }
    }
}

@Composable
fun PageHeading(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(
            start = if (leading == null) AkihaSpacing.page else AkihaSpacing.xs,
            end = AkihaSpacing.sm,
            top = AkihaSpacing.lg,
            bottom = AkihaSpacing.md,
        ),
        horizontalArrangement = Arrangement.spacedBy(AkihaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        }
        actions()
    }
}

@Composable
fun SectionHeading(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AkihaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        action?.invoke()
    }
}

enum class StatusBandTone { NEUTRAL, SUCCESS, ERROR }

@Composable
fun StatusBand(
    title: String,
    supporting: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    tone: StatusBandTone = StatusBandTone.NEUTRAL,
    action: (@Composable () -> Unit)? = null,
) {
    val container by animateColorAsState(
        when (tone) {
            StatusBandTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh
            StatusBandTone.SUCCESS -> MaterialTheme.colorScheme.secondaryContainer
            StatusBandTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        },
        tween(AkihaMotion.Fast),
        label = "status-container",
    )
    val contentColor = when (tone) {
        StatusBandTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
        StatusBandTone.SUCCESS -> MaterialTheme.colorScheme.onSecondaryContainer
        StatusBandTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(modifier = modifier.fillMaxWidth(), color = container, contentColor = contentColor, shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.fillMaxWidth().padding(AkihaSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(AkihaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = contentColor.copy(alpha = .10f)) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), null, Modifier.size(21.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = .78f))
            }
            action?.invoke()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AkihaIconButton(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.size(48.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) { Icon(painterResource(icon), label, Modifier.size(21.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AkihaIconToggleButton(
    label: String,
    @DrawableRes icon: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = modifier.size(48.dp),
            colors = IconButtonDefaults.iconToggleButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) { Icon(painterResource(icon), label, Modifier.size(21.dp)) }
    }
}

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    @DrawableRes icon: Int = LucideR.drawable.lucide_ic_inbox,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = AkihaSpacing.section, vertical = AkihaSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AkihaSpacing.md),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
        ) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            supporting?.let {
                Spacer(Modifier.height(AkihaSpacing.xxs))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action?.invoke()
    }
}
