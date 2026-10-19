/**
 * @author Shakib
 * @since 19/10/26
 */
package io.devconsole.api

import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityDefaultsTest {
    @Test
    fun `constructor and Java builder enable development features without security layers`() {
        listOf(DevConsoleConfig(), DevConsoleConfig.builder().build()).forEach { config ->
            assertEquals(RedactionPolicy.none(), config.redactionPolicy)
            assertEquals(NetworkProtection(false, false), config.networkProtection)
            assertEquals(BrowserSecurity.NONE, config.browserSecurity)
            assertEquals(EditingCapabilities(), config.editingCapabilities)
            assertTrue(config.composerEnabled)
            assertTrue(config.composerAllowedHosts.isEmpty())
            assertTrue(config.stateMutationsEnabled)
            assertTrue(config.screenshotPolicy.enabled)
        }
        assertEquals(EditingCapabilities(), EditingCapabilities.builder().build())
    }

    @Test
    fun `network protection survives additive copies and changes runtime equivalence`() {
        val protection = NetworkProtection.recommended()
        val config =
            DevConsoleConfig()
                .withNetworkProtection(protection)
                .withEditingCapabilities(EditingCapabilities.readOnly())
                .withBrowserSecurity(BrowserSecurity.SESSION_CODE)

        assertEquals(protection, config.networkProtection)
        assertFalse(config.runtimeEquivalentTo(DevConsoleConfig()))
        assertFalse(
            DevConsoleConfig().withNetworkProtection(protection).runtimeEquivalentTo(DevConsoleConfig()),
        )
        assertEquals(
            protection,
            DevConsoleConfig
                .builder()
                .networkProtection(protection)
                .build()
                .networkProtection,
        )
        assertEquals(NetworkProtection(false, false), DevConsoleConfig().networkProtection)
    }
}
