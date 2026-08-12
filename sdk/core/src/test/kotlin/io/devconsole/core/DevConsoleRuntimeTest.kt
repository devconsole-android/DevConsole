package io.devconsole.core

import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.DevConsoleState
import io.devconsole.api.InitResult
import io.devconsole.api.StartResult
import io.devconsole.api.StopReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevConsoleRuntimeTest {
    @Test
    fun `FR-CORE-001 equivalent initialization returns the existing runtime`() {
        val runtime = enabledRuntime()

        assertEquals(InitResult.Initialized, runtime.initialize(DevConsoleConfig.default()))
        assertEquals(InitResult.ExistingRuntime, runtime.initialize(DevConsoleConfig.default()))
    }

    @Test
    fun `FR-CORE-001 conflicting initialization returns a typed conflict`() {
        val runtime = enabledRuntime()
        runtime.initialize(DevConsoleConfig.default())

        val result = runtime.initialize(DevConsoleConfig(eventBufferCapacity = 8))

        assertTrue(result is InitResult.Conflict)
    }

    @Test
    fun `FR-CORE-002 initialization does not start a server`() =
        runTest {
            val runtime = enabledRuntime()
            runtime.initialize(DevConsoleConfig.default())

            assertEquals(StartResult.ServerUnavailable, runtime.start())
            assertEquals(DevConsoleState.Initialized, runtime.state.value)
        }

    @Test
    fun `FR-CORE-004 repeated stop is safe`() =
        runTest {
            val runtime = enabledRuntime()
            runtime.initialize(DevConsoleConfig.default())

            runtime.stop(StopReason.UserRequested)
            runtime.stop(StopReason.UserRequested)

            assertEquals(DevConsoleState.Stopped, runtime.state.value)
        }

    @Test
    fun `a start can be retried after it ended in PermissionRequired`() {
        val runtime = enabledRuntime()
        runtime.initialize(DevConsoleConfig.default())

        assertEquals(null, runtime.beginServerStart())
        runtime.serverRequiresPermission()
        assertEquals(DevConsoleState.PermissionRequired, runtime.state.value)

        // The host may have since granted the permission, or auto-start is falling back to loopback.
        assertEquals(null, runtime.beginServerStart())
        assertEquals(DevConsoleState.Starting, runtime.state.value)
    }

    @Test
    fun `a start can be retried after it failed`() {
        val runtime = enabledRuntime()
        runtime.initialize(DevConsoleConfig.default())

        assertEquals(null, runtime.beginServerStart())
        runtime.serverFailed("no eligible network")
        assertTrue(runtime.state.value is DevConsoleState.Failed)

        assertEquals(null, runtime.beginServerStart())
        assertEquals(DevConsoleState.Starting, runtime.state.value)
    }

    private fun enabledRuntime(): DevConsoleRuntime = DevConsoleRuntime(RuntimeGate.Enabled)

    @Test
    fun `host config supersedes the default installed by auto-initialization`() {
        val runtime = DevConsoleRuntime(RuntimeGate.Enabled)
        assertEquals(InitResult.Initialized, runtime.initialize(DevConsoleConfig.default(), provisional = true))

        val hostConfig = DevConsoleConfig(eventBufferCapacity = 512)
        assertEquals(InitResult.Initialized, runtime.initialize(hostConfig))
    }

    @Test
    fun `host config superseding provisional config preserves a running server state`() {
        val runtime = enabledRuntime()
        runtime.initialize(DevConsoleConfig.default(), provisional = true)
        runtime.beginServerStart()
        runtime.serverStarted()

        val result = runtime.initialize(DevConsoleConfig(eventBufferCapacity = 512))

        assertEquals(InitResult.Initialized, result)
        assertEquals(DevConsoleState.Running, runtime.state.value)
    }

    @Test
    fun `a second differing config still conflicts once the host has configured the runtime`() {
        val runtime = DevConsoleRuntime(RuntimeGate.Enabled)
        runtime.initialize(DevConsoleConfig(eventBufferCapacity = 512))

        val result = runtime.initialize(DevConsoleConfig(eventBufferCapacity = 64))
        assertTrue("expected Conflict, got $result", result is InitResult.Conflict)
    }

    /** Both surfaces reported a permanent 0 because nothing incremented these. */
    @Test
    fun `published and dropped events accumulate onto health`() {
        val runtime = enabledRuntime()
        runtime.initialize(DevConsoleConfig.default())
        assertEquals(0L, runtime.health.value.publishedEventCount)

        repeat(3) { runtime.recordPublishedEvent() }
        runtime.recordDroppedEvents(2)

        assertEquals(3L, runtime.health.value.publishedEventCount)
        assertEquals(2L, runtime.health.value.droppedEventCount)
    }

    @Test
    fun `a non-positive drop count leaves health untouched`() {
        val runtime = enabledRuntime()
        runtime.recordDroppedEvents(0)
        runtime.recordDroppedEvents(-5)

        assertEquals(0L, runtime.health.value.droppedEventCount)
    }
}
