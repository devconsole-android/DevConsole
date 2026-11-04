/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.ui.compose

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveDetailTargetSaverTest {
    private val scope = SaverScope { true }

    private fun roundTrip(target: ObserveDetailTarget?): ObserveDetailTarget? {
        val saved = with(ObserveDetailTargetSaver) { scope.save(target) }
        return saved?.let(ObserveDetailTargetSaver::restore)
    }

    @Test
    fun `every detail target survives a save and restore`() {
        val targets =
            listOf(
                ObserveDetailTarget.Net("tx-1"),
                ObserveDetailTarget.Frame("socket-1", 1_700_000_000_123L),
                ObserveDetailTarget.Push("fcm", "message-1", 42L),
                ObserveDetailTarget.Push("fcm", null, 43L),
                ObserveDetailTarget.Log("log-1"),
                ObserveDetailTarget.Crash("crash-1"),
                ObserveDetailTarget.RemoteConfigKey("firebase", "checkout|flag"),
            )
        targets.forEach { target -> assertEquals(target, roundTrip(target)) }
    }

    @Test
    fun `no open detail restores as none`() {
        assertEquals(null, roundTrip(null))
    }
}
