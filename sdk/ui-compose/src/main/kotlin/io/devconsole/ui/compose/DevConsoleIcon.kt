/**
 * @author Shakib
 * @since 19/10/26
 */
@file:Suppress("FunctionNaming")

package io.devconsole.ui.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp

/**
 * One `devconsole_ic_*` vector drawable, tinted. A null [contentDescription] keeps the icon out of
 * the accessibility tree, which is what every glyph nested in an already-labelled control wants.
 */
@Composable
internal fun DevConsoleIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier,
    tint: Color,
    size: Dp,
) {
    Icon(
        painter = painterResource(id),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}
