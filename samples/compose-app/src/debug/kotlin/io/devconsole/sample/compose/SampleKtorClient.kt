/**
 * @author Shakib
 * @since 08/08/26
 */
package io.devconsole.sample.compose

import io.devconsole.DevConsole
import io.devconsole.network.ktor.DevConsoleKtorClientPlugin
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/**
 * The debug half of the sample's Ktor client: the same client the release source set builds, plus
 * [DevConsoleKtorClientPlugin].
 *
 * The split exists because `sdk:network-ktor` is the one adapter with no `-noop` twin, so it is a
 * `debugImplementation` and its types simply do not exist on the release classpath. Keeping the
 * client construction in a variant source set -- rather than in `src/main` behind a build-config
 * check -- is what lets the release variant compile at all, and is the pattern a host app should
 * copy for any debug-only adapter.
 *
 * [CIO] is deliberate, not incidental. It is a pure-Kotlin engine with no OkHttp underneath, so a
 * captured response body here proves the capture came from the Ktor plugin itself rather than from
 * the OkHttp adapter the rest of this sample uses.
 */
internal fun buildSampleKtorClient(): HttpClient =
    HttpClient(CIO) {
        install(DevConsoleKtorClientPlugin) {
            recorder = DevConsole.networkRecorder()
        }
    }
