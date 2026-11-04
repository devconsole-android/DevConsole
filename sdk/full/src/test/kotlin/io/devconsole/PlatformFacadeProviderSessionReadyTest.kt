/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformFacadeProviderSessionReadyTest {
    @Test
    fun `ready or in-flight bootstrap keeps durable session available`() {
        assertTrue(PlatformFacadeProvider.sessionReadyForStart(true, durableReady = true, bootstrapInFlight = false))
        assertTrue(PlatformFacadeProvider.sessionReadyForStart(true, durableReady = false, bootstrapInFlight = true))
        assertTrue(PlatformFacadeProvider.sessionReadyForStart(false, durableReady = false, bootstrapInFlight = false))
        assertFalse(PlatformFacadeProvider.sessionReadyForStart(true, durableReady = false, bootstrapInFlight = false))
    }
}
