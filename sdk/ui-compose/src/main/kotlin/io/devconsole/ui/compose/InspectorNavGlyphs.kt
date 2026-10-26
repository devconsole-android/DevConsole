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

/** [InspectorNavigationBar]'s destination icons: eye, sliders, database and an ellipsis. */
internal enum class NavGlyph { Observe, Control, Data, More }

/** Renders one [NavGlyph]; same contract as [InspectorGlyphIcon] (null description = decorative). */
@Composable
internal fun NavGlyphIcon(
    glyph: NavGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DevConsoleTheme.colors.ink,
    size: Dp = 18.dp,
) {
    DevConsoleIcon(glyph.drawableRes(), contentDescription, modifier, tint, size)
}

private fun NavGlyph.drawableRes(): Int =
    when (this) {
        NavGlyph.Observe -> R.drawable.devconsole_ic_overview
        NavGlyph.Control -> R.drawable.devconsole_ic_preferences
        NavGlyph.Data -> R.drawable.devconsole_ic_database
        NavGlyph.More -> R.drawable.devconsole_ic_more_horizontal
    }
