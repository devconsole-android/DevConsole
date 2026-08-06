/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import android.content.Context
import androidx.core.content.ContextCompat

/**
 * Bridges server lifecycle to [DevConsoleForegroundService]. Kept out of PlatformFacadeProvider
 * so the gate check, the start/stop intents, and the never-throw guarantee are testable without
 * booting the whole facade.
 */
internal class KeepAliveServiceController(
    private val gate: KeepAliveGate,
) {
    fun onServerStarted(
        context: Context,
        endpointUrl: String,
    ) {
        if (!gate.canRunForegroundService()) return
        // Never let keep-alive break the server: an OEM quirk or a background-start rejection
        // (ForegroundServiceStartNotAllowedException) degrades the feature, nothing else.
        runCatching {
            ContextCompat.startForegroundService(context, DevConsoleForegroundService.startIntent(context, endpointUrl))
        }.onFailure {
            logcatInfo("DevConsole", "Keep-alive service unavailable: ${it.javaClass.simpleName}")
        }
    }

    fun onServerStopped(context: Context) {
        runCatching { context.stopService(DevConsoleForegroundService.stopIntent(context)) }
    }
}
