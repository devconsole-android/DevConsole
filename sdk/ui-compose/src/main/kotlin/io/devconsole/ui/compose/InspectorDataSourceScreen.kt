/**
 * @author Shakib
 * @since 19/10/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Top bar for a Data source sub-screen: 48dp back arrow button followed by the screen title.
 */
@Composable
internal fun DataSourceTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DevConsoleTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            DevConsoleIcon(
                id = R.drawable.devconsole_ic_arrow_back,
                contentDescription = "Back to Data",
                modifier = Modifier,
                tint = colors.ink,
                size = 24.dp,
            )
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = colors.ink,
            fontSize = 20.sp,
        )
    }
}

/**
 * Sub-screen shell for a Data source: renders [DataSourceTopBar] above scrollable [content].
 */
@Composable
internal fun DataSourceSubScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        DataSourceTopBar(title = title, onBack = onBack)
        content()
    }
}
