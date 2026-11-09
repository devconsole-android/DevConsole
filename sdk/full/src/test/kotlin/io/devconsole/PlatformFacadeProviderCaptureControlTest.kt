/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole

import io.devconsole.api.PurgeResult
import io.devconsole.api.SessionExportResult
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkTransaction
import io.devconsole.push.InMemoryPushStore
import io.devconsole.push.PushEvent
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.SocketConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pause/export/purge on the full provider; Robolectric because the provider's ANR watchdog needs a main Looper. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformFacadeProviderCaptureControlTest {
    @Test
    fun `uninitialized full PlatformFacadeProvider reports disabled pause and export states`() {
        val provider = PlatformFacadeProvider()

        assertFalse(provider.isCapturePaused())
        assertFalse(provider.setCapturePaused(true))
        assertFalse(provider.isCapturePaused())
        assertEquals(SessionExportResult.Disabled, runBlocking { provider.exportSession() })
    }

    @Test
    fun `purgeCapturedData counts and clears in-memory stores`() {
        val provider = PlatformFacadeProvider()
        val networkStore =
            PlatformFacadeProvider::class.java
                .getDeclaredField("networkTransactionStore")
                .apply { isAccessible = true }
                .get(provider) as InMemoryNetworkTransactionStore
        val socketStore =
            PlatformFacadeProvider::class.java
                .getDeclaredField("inMemorySocketStore")
                .apply { isAccessible = true }
                .get(provider) as InMemorySocketStore
        val pushStore =
            PlatformFacadeProvider::class.java
                .getDeclaredField("inMemoryPushStore")
                .apply { isAccessible = true }
                .get(provider) as InMemoryPushStore

        val capture =
            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.recommended()))
                .capture(NetworkRequestInput("GET", "https://api.test/items"), NetworkResponseInput(200))
        networkStore.record(
            NetworkTransaction(
                "tx-1",
                startedAtEpochMs = 1L,
                completedAtEpochMs = 2L,
                capture = capture,
            ),
        )
        networkStore.record(
            NetworkTransaction(
                "tx-2",
                startedAtEpochMs = 3L,
                completedAtEpochMs = 4L,
                capture = capture,
            ),
        )
        socketStore.open(SocketConnection(id = "sock-1", url = "wss://api.test/ws", openedAtEpochMs = 1L))
        pushStore.append(PushEvent(provider = "fcm", data = emptyMap(), messageId = "msg-1"))

        val purged = runBlocking { provider.purgeCapturedData() }

        assertEquals(
            PurgeResult(
                networkTransactions = 2,
                socketConnections = 1,
                pushEvents = 1,
                retainedRunsDeleted = 0,
            ),
            purged,
        )
        assertNull(networkStore.find("tx-1"))
        assertNull(networkStore.find("tx-2"))
        assertTrue(socketStore.connections().isEmpty())
        assertTrue(pushStore.events().isEmpty())
    }
}
