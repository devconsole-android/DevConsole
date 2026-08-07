/**
 * @author Shakib
 * @since 07/08/26
 */
package io.devconsole

import io.devconsole.mocks.MockEngine
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.ui.compose.InspectorCommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullInspectorDataSourceServerControlTest {
    private fun dataSource(
        scope: CoroutineScope? = null,
        calls: MutableList<String> = mutableListOf(),
    ): FullInspectorDataSource =
        FullInspectorDataSource(
            networkTransactionStore = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16))),
            mockEngine = MockEngine(emptyList()),
            configSupplier = { null },
            serverControlScope = scope,
            startServer = if (scope != null) ({ calls += "start" }) else null,
            stopServer = if (scope != null) ({ calls += "stop" }) else null,
        )

    @Test
    fun `server control is unsupported when the hooks are absent`() {
        val dataSource = dataSource(scope = null)

        assertFalse(dataSource.supportsServerControl())
        assertEquals(InspectorCommandResult.Unavailable, dataSource.setServerRunning(true))
    }

    @Test
    fun `setServerRunning launches the matching hook`() =
        runTest(UnconfinedTestDispatcher()) {
            val calls = mutableListOf<String>()
            val dataSource = dataSource(scope = this, calls = calls)

            assertTrue(dataSource.supportsServerControl())
            assertTrue(dataSource.setServerRunning(true) is InspectorCommandResult.Success)
            assertTrue(dataSource.setServerRunning(false) is InspectorCommandResult.Success)
            assertEquals(listOf("start", "stop"), calls)
        }
}
