/**
 * @author Shakib
 * @since 09/11/26
 */
package io.devconsole.ui.compose

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.PackageInfoCompat

internal fun buildSummaryLine(
    versionName: String?,
    versionCode: Long,
    manufacturer: String,
    model: String,
    androidRelease: String,
): String {
    val maker = manufacturer.replaceFirstChar { it.uppercase() }
    val device = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$maker $model"
    val version = versionName?.takeIf { it.isNotBlank() }?.let { "$it ($versionCode)" } ?: "($versionCode)"
    return "$version · $device · Android $androidRelease"
}

@Composable
internal fun rememberBuildSummary(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            buildSummaryLine(
                info.versionName,
                PackageInfoCompat.getLongVersionCode(info),
                Build.MANUFACTURER,
                Build.MODEL,
                Build.VERSION.RELEASE,
            )
        }.getOrDefault("")
    }
}
