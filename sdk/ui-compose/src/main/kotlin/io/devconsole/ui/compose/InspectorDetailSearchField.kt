/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The detail screen's live "find in headers, payload, response" field: 48dp pill input with a
 * leading 17dp search icon and a trailing mono match count.
 */
@Suppress("LongParameterList") // Query/match-count/placeholder/color all vary per detail screen kind.
@Composable
internal fun InspectorDetailSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    matchLabel: String,
    modifier: Modifier = Modifier,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    navigationEnabled: Boolean = false,
    onOpenOptions: (() -> Unit)? = null,
    scopeLabel: String? = null,
    placeholder: String = DEFAULT_DETAIL_SEARCH_PLACEHOLDER,
    matchColor: Color = DevConsoleTheme.colors.muted,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(50))
                            .background(DevConsoleTheme.colors.surface2)
                            .padding(start = 40.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InspectorPlainTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = placeholder,
                        textColor = DevConsoleTheme.colors.ink,
                        placeholderColor = DevConsoleTheme.colors.muted,
                    )
                }
                InspectorGlyphIcon(
                    InspectorGlyph.Search,
                    contentDescription = null,
                    tint = DevConsoleTheme.colors.muted,
                    size = 17.dp,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp),
                )
            }
            Text(matchLabel, color = matchColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
            if (onPrevious != null) {
                InspectorRoundIconButton(
                    contentDescription = "Previous match",
                    onClick = onPrevious,
                    enabled = navigationEnabled,
                    size = 44.dp,
                    icon = {
                        InspectorGlyphIcon(
                            InspectorGlyph.ChevronDown,
                            contentDescription = null,
                            tint = if (navigationEnabled) DevConsoleTheme.colors.ink else DevConsoleTheme.colors.text3,
                            size = 16.dp,
                            rotationDegrees = 90f,
                        )
                    },
                )
            }
            if (onNext != null) {
                InspectorRoundIconButton(
                    contentDescription = "Next match",
                    onClick = onNext,
                    enabled = navigationEnabled,
                    size = 44.dp,
                    icon = {
                        InspectorGlyphIcon(
                            InspectorGlyph.ChevronDown,
                            contentDescription = null,
                            tint = if (navigationEnabled) DevConsoleTheme.colors.ink else DevConsoleTheme.colors.text3,
                            size = 16.dp,
                            rotationDegrees = -90f,
                        )
                    },
                )
            }
        }
        if (onOpenOptions != null && scopeLabel != null) {
            FilterChipRow(
                chips = listOf(InspectorFilterChip("search-scope", "Search in: $scopeLabel", selected = true)),
                onChipClick = { onOpenOptions() },
                modifier = Modifier.padding(top = 0.dp),
            )
        }
    }
}
