package com.akiha.akihalink.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.akiha.akihalink.InstalledApp
import com.composables.icons.lucide.R as LucideR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class InstalledAppIconLoader(context: Context) {
    private val context = context.applicationContext
    private val launcherApps = this.context.getSystemService(LauncherApps::class.java)
    private val cache = LruCache<String, ImageBitmap>(128)

    suspend fun load(app: InstalledApp, densityDpi: Int, sizePx: Int): ImageBitmap? {
        val cacheKey = "${app.userId}:${app.iconPackageName}:$densityDpi:$sizePx"
        cache.get(cacheKey)?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get(cacheKey) ?: loadIcon(app, densityDpi, sizePx)?.also { cache.put(cacheKey, it) }
        }
    }

    private fun loadIcon(app: InstalledApp, densityDpi: Int, sizePx: Int): ImageBitmap? = runCatching {
        val currentUser = Process.myUserHandle()
        val requestedUser = UserHandle.getUserHandleForUid(app.uid)
        val user = launcherApps.profiles.firstOrNull { it == requestedUser }
            ?: currentUser.takeIf { it == requestedUser }
            ?: return null
        val launcherActivity = launcherApps.getActivityList(app.iconPackageName, user).firstOrNull()
        val applicationInfo = launcherActivity?.applicationInfo
            ?: launcherApps.getApplicationInfo(app.iconPackageName, 0, user)
        // PackageItemInfo.loadIcon lets OEM theme engines substitute the active icon-pack asset.
        // LauncherActivityInfo.getIcon reads the APK drawable directly and bypasses ColorOS theming.
        val drawable = launcherActivity?.let {
            runCatching { it.activityInfo.loadIcon(context.packageManager) }.getOrNull()
        } ?: runCatching {
            applicationInfo.loadIcon(context.packageManager)
        }.getOrNull() ?: launcherActivity?.getIcon(densityDpi) ?: return null
        drawable.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
}

@Composable
internal fun InstalledAppIcon(
    app: InstalledApp,
    loader: InstalledAppIconLoader,
    densityDpi: Int,
    sizePx: Int,
    checked: Boolean,
) {
    val icon by produceState<ImageBitmap?>(
        initialValue = null,
        app.userId,
        app.iconPackageName,
        densityDpi,
        sizePx,
    ) {
        value = loader.load(app, densityDpi, sizePx)
    }
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = Modifier.size(44.dp).clip(shape),
        shape = shape,
        color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .10f)
        else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Image(
                    bitmap = icon!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_app_window),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
