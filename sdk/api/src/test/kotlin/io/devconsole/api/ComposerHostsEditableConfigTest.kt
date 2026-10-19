package io.devconsole.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerHostsEditableConfigTest {
    @Test
    fun `the allowlist is read-only unless the app opts in`() {
        assertFalse(DevConsoleConfig.default().composerHostsEditable)
        assertFalse(
            DevConsoleConfig
                .Builder()
                .composerEnabled(true)
                .build()
                .composerHostsEditable,
        )
    }

    @Test
    fun `the builder and copy method carry the flag and every other setting along`() {
        val built =
            DevConsoleConfig
                .Builder()
                .composerEnabled(true)
                .composerAllowedHosts(setOf("api.example.test"))
                .composerHostsEditable(true)
                .browserSecurity(BrowserSecurity.SESSION_CODE)
                .build()

        assertTrue(built.composerHostsEditable)
        assertEquals(BrowserSecurity.SESSION_CODE, built.browserSecurity)
        assertEquals(setOf("api.example.test"), built.composerAllowedHosts)

        val later = built.withBrowserSecurity(BrowserSecurity.NONE)
        assertTrue(later.composerHostsEditable, "an unrelated copy must not drop the flag")
        assertFalse(built.withComposerHostsEditable(false).composerHostsEditable)
    }

    @Test
    fun `runtime equivalence notices a changed flag`() {
        val off = DevConsoleConfig.default()

        assertFalse(off.runtimeEquivalentTo(off.withComposerHostsEditable(true)))
        assertTrue(off.runtimeEquivalentTo(off.withComposerHostsEditable(false)))
    }
}
