package io.devconsole.ui.views

import io.devconsole.api.BindingMode
import io.devconsole.api.BrowserEndpoint
import io.devconsole.api.DevConsoleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevConsoleStatusTextTest {
    @Test
    fun `running and permission states have actionable text`() {
        assertEquals("DevConsole server is running", DevConsoleStatusText.forState(DevConsoleState.Running))
        assertEquals(
            "Local network permission is required",
            DevConsoleStatusText.forState(DevConsoleState.PermissionRequired),
        )
    }

    @Test
    fun `running status includes the endpoint once known`() {
        val endpoint = BrowserEndpoint("192.168.0.15", 8080, BindingMode.LAN)

        assertEquals(
            "DevConsole server is running at 192.168.0.15:8080",
            DevConsoleStatusText.forState(DevConsoleState.Running, endpoint),
        )
    }

    @Test
    fun `non-running states ignore a stale endpoint`() {
        val endpoint = BrowserEndpoint("192.168.0.15", 8080, BindingMode.LAN)

        assertEquals("DevConsole is ready", DevConsoleStatusText.forState(DevConsoleState.Stopped, endpoint))
    }

    @Test
    fun `LAN endpoint exposes the plaintext transport warning but loopback does not`() {
        val lan = BrowserEndpoint("192.168.0.15", 8080, BindingMode.LAN)
        val loopback = BrowserEndpoint("127.0.0.1", 8080, BindingMode.LOOPBACK)

        assertEquals(LOCAL_NETWORK_HTTP_WARNING, lanTransportWarning(lan))
        assertEquals(null, lanTransportWarning(loopback))
    }

    /**
     * Every [DevConsoleState] must map to a non-blank label -- a blank status line would leave the
     * launcher panel silently showing nothing. Labels are asserted pairwise-distinct with one
     * documented exception: [DevConsoleState.Initialized] and [DevConsoleState.Stopped] deliberately
     * share "DevConsole is ready", since both mean the same thing to a user (not running, but able
     * to start) -- see [DevConsoleStatusText.forState]. Any *other* collision still fails this test.
     */
    @Test
    fun `every DevConsoleState maps to a non-blank label, distinct except for the Initialized-Stopped alias`() {
        val states =
            listOf(
                DevConsoleState.Uninitialized,
                DevConsoleState.DisabledForBuild,
                DevConsoleState.Initialized,
                DevConsoleState.PermissionRequired,
                DevConsoleState.Starting,
                DevConsoleState.Running,
                DevConsoleState.Stopping,
                DevConsoleState.Stopped,
                DevConsoleState.Failed("boom"),
            )
        val labels = states.associateWith { DevConsoleStatusText.forState(it) }

        labels.forEach { (state, label) ->
            assertTrue("expected a non-blank label for $state", label.isNotBlank())
        }

        val documentedAlias = setOf(DevConsoleState.Initialized, DevConsoleState.Stopped)
        val labelToStates = labels.entries.groupBy({ it.value }, { it.key })
        labelToStates.forEach { (label, statesSharingLabel) ->
            if (statesSharingLabel.size > 1) {
                assertEquals(
                    "unexpected label collision on \"$label\" between $statesSharingLabel -- only " +
                        "Initialized/Stopped are allowed to share a label",
                    documentedAlias,
                    statesSharingLabel.toSet(),
                )
            }
        }

        assertEquals("DevConsole is not initialized", labels.getValue(DevConsoleState.Uninitialized))
        assertEquals("DevConsole is disabled for this build", labels.getValue(DevConsoleState.DisabledForBuild))
        assertEquals("DevConsole is ready", labels.getValue(DevConsoleState.Initialized))
        assertEquals("Local network permission is required", labels.getValue(DevConsoleState.PermissionRequired))
        assertEquals("Starting DevConsole", labels.getValue(DevConsoleState.Starting))
        assertEquals("DevConsole server is running", labels.getValue(DevConsoleState.Running))
        assertEquals("Stopping DevConsole", labels.getValue(DevConsoleState.Stopping))
        assertEquals("DevConsole is ready", labels.getValue(DevConsoleState.Stopped))
        assertEquals("DevConsole failed: boom", labels.getValue(DevConsoleState.Failed("boom")))
    }
}
