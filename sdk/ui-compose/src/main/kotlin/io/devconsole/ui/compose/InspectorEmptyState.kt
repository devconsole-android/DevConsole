/**
 * @author Shakib
 * @since 19/10/26
 */
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName") // Named for its main composable.

package io.devconsole.ui.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Empty-state spot illustrations (the web's `dc-spot-*` symbols): a muted base vector with a
 * signal accent vector drawn over it, so both follow the theme. */
internal enum class EmptySpot(
    @DrawableRes val base: Int,
    @DrawableRes val accent: Int,
) {
    Traffic(R.drawable.devconsole_spot_traffic, R.drawable.devconsole_spot_traffic_accent),
    Sockets(R.drawable.devconsole_spot_sockets, R.drawable.devconsole_spot_sockets_accent),
    CrashesClear(R.drawable.devconsole_spot_crashes, R.drawable.devconsole_spot_crashes_accent),
    Push(R.drawable.devconsole_spot_push, R.drawable.devconsole_spot_push_accent),
    Logs(R.drawable.devconsole_spot_logs, R.drawable.devconsole_spot_logs_accent),
    Mocks(R.drawable.devconsole_spot_mocks, R.drawable.devconsole_spot_mocks_accent),
    RemoteConfig(R.drawable.devconsole_spot_remote_config, R.drawable.devconsole_spot_remote_config_accent),
    Search(R.drawable.devconsole_spot_search, R.drawable.devconsole_spot_search_accent),
    Folder(R.drawable.devconsole_spot_folder, R.drawable.devconsole_spot_folder_accent),
    Database(R.drawable.devconsole_spot_database, R.drawable.devconsole_spot_database_accent),
}

@Composable
internal fun EmptySpotImage(
    spot: EmptySpot,
    size: Dp = 80.dp,
    modifier: Modifier = Modifier,
) {
    val colors = DevConsoleTheme.colors
    Box(modifier.size(size)) {
        Icon(painterResource(spot.base), contentDescription = null, tint = colors.text3, modifier = Modifier.size(size))
        Icon(
            painterResource(spot.accent),
            contentDescription = null,
            tint = colors.signal,
            modifier = Modifier.size(size),
        )
    }
}

/** Centred spot plus one muted sentence; the sentence is the whole message, the spot is decoration. */
@Composable
internal fun InspectorEmptyState(
    spot: EmptySpot,
    message: String,
    modifier: Modifier = Modifier,
    spotSize: Dp = 80.dp,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EmptySpotImage(spot, spotSize)
        Text(
            message,
            modifier = Modifier.widthIn(max = 280.dp),
            color = DevConsoleTheme.colors.muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
    }
}
