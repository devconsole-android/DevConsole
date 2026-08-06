package io.devconsole.ui.compose

import io.devconsole.api.BindingMode
import io.devconsole.api.BrowserEndpoint
import io.devconsole.api.DevConsoleState
import org.junit.Assert.assertEquals
import org.junit.Test

class DevConsoleComposeStatusTest {
    @Test
    fun `running status is suitable for a launcher`() {
        assertEquals("DevConsole server is running", DevConsoleComposeStatus.forState(DevConsoleState.Running))
    }

    @Test
    fun `running status includes the endpoint once known`() {
        val endpoint = BrowserEndpoint("192.168.0.15", 8080, BindingMode.LAN)

        assertEquals(
            "DevConsole server is running at 192.168.0.15:8080",
            DevConsoleComposeStatus.forState(DevConsoleState.Running, endpoint),
        )
    }

    @Test
    fun `non-running states ignore a stale endpoint`() {
        val endpoint = BrowserEndpoint("192.168.0.15", 8080, BindingMode.LAN)

        assertEquals("DevConsole is ready", DevConsoleComposeStatus.forState(DevConsoleState.Stopped, endpoint))
    }

    @Test
    fun `LAN endpoint exposes the plaintext transport warning but loopback does not`() {
        val lan = BrowserEndpoint("192.168.0.15", 8080, BindingMode.LAN)
        val loopback = BrowserEndpoint("127.0.0.1", 8080, BindingMode.LOOPBACK)

        assertEquals(LOCAL_NETWORK_HTTP_WARNING, lanTransportWarning(lan))
        assertEquals(null, lanTransportWarning(loopback))
    }
}
