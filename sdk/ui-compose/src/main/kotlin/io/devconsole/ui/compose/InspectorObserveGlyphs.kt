/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Observe-screen-only glyphs. Kept out of the shared [InspectorGlyph] set: shared components take
 * icons as caller-supplied `@Composable` slots so a screen can wire in whatever icon set it needs
 * without touching shared-component files.
 */
internal enum class ObserveGlyph { Plug, Activity, Filter, Refresh, Download, Sun, Tag, Trash }

/** Renders one [ObserveGlyph]; same contract as [InspectorGlyphIcon] (null description = decorative). */
@Composable
internal fun ObserveGlyphIcon(
    glyph: ObserveGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DevConsoleTheme.colors.ink,
    size: Dp = 18.dp,
) {
    DevConsoleIcon(glyph.drawableRes(), contentDescription, modifier, tint, size)
}

private fun ObserveGlyph.drawableRes(): Int =
    when (this) {
        ObserveGlyph.Plug -> R.drawable.devconsole_ic_websockets
        ObserveGlyph.Activity -> R.drawable.devconsole_ic_activity
        ObserveGlyph.Filter -> R.drawable.devconsole_ic_filter_lines
        ObserveGlyph.Refresh -> R.drawable.devconsole_ic_refresh
        ObserveGlyph.Download -> R.drawable.devconsole_ic_download
        ObserveGlyph.Sun -> R.drawable.devconsole_ic_sun
        ObserveGlyph.Tag -> R.drawable.devconsole_ic_tag
        ObserveGlyph.Trash -> R.drawable.devconsole_ic_trash
    }
