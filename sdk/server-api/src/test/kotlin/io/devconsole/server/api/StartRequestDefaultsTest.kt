package io.devconsole.server.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartRequestDefaultsTest {
    @Test
    fun `default server start request uses LAN binding`() {
        assertEquals(BindingMode.LAN, StartRequest().bindingMode)
    }

    @Test
    fun `default port range prefers 8080 but leaves room to fall forward`() {
        // A single-port default makes the second app on a device -- or a restart before the old
        // server released the port -- fail with PortUnavailable instead of taking the next port.
        val portRange = StartRequest().portRange

        assertEquals(8080, portRange.first)
        assertTrue("default range must leave fallback ports", portRange.last > portRange.first)
    }
}
