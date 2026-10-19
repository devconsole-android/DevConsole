package io.devconsole.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerHostsEditableConfigTest {
    @Test
    fun `the allowlist is editable unless the app opts out`() {
        assertTrue(DevConsoleConfig.default().composerHostsEditable)
        assertTrue(
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
        val editable = DevConsoleConfig.default()

        assertFalse(editable.runtimeEquivalentTo(editable.withComposerHostsEditable(false)))
        assertTrue(editable.runtimeEquivalentTo(editable.withComposerHostsEditable(true)))
    }
}
