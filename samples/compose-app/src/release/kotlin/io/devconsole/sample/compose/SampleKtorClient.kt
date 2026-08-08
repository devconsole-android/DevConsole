/**
 * @author Shakib
 * @since 08/08/26
 */
package io.devconsole.sample.compose

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/**
 * The release half of the sample's Ktor client: a plain [HttpClient], with nothing installed.
 *
 * `sdk:network-ktor` has no `-noop` twin, so unlike every other adapter in this sample there is no
 * same-API stand-in to call here -- the capture line is simply absent from the release build. The
 * shared signature is what keeps `MainActivity` (which lives in `src/main` and compiles against
 * both variants) free of any conditional.
 */
internal fun buildSampleKtorClient(): HttpClient = HttpClient(CIO)
