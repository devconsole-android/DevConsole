package io.devconsole.server.api

import org.junit.Assert.assertEquals
import org.junit.Test

class StartRequestDefaultsTest {
    @Test
    fun `default server start request uses LAN binding`() {
        assertEquals(BindingMode.LAN, StartRequest().bindingMode)
    }
}
