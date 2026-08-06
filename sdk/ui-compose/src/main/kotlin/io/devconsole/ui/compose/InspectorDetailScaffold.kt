/**
 * @author Shakib
 * @since 04/08/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Full-screen detail overlay scaffolding: a `ground`-colored surface hosting a fixed [header], a
 * scrolling [content] with 100dp bottom clearance so the last section clears the [footer], and an
 * optional [footer] bar. The actual capture/frame/push/log detail screens assemble from this plus
 * [InspectorDetailHeader], [CollapsibleSection] and friends.
 */
@Composable
internal fun InspectorDetailScaffold(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = DevConsoleTheme.colors.ground) {
        // imePadding() keeps the footer (Cancel/Save on the mock editor, etc.) above the soft
        // keyboard instead of letting it render underneath -- a no-op when the IME is hidden.
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            header()
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                        .padding(top = 12.dp, bottom = 100.dp),
                content = content,
            )
            footer?.invoke()
        }
    }
}
