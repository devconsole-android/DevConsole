/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevConsoleInitializerTest {
    @Test
    fun `main process decision accepts configured name or package fallback`() {
        assertTrue(DevConsoleInitializer.isMainProcessName("app", "app", "app"))
        assertFalse(DevConsoleInitializer.isMainProcessName("app:remote", "app", "app"))
        assertTrue(DevConsoleInitializer.isMainProcessName("app", null, "app"))
    }
}
