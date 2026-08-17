package io.devconsole.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusedConfigurationTest {
    /**
     * Mocks are the one editable-by-default surface. They write nothing of the host's -- a mock rule
     * only ever short-circuits DevConsole's own interceptor -- so the read-only posture that protects
     * preferences, databases, and files buys nothing here, and an empty Mocks screen with a disabled
     * "Add rule" button was the single most common "is this broken?" first run.
     */
    @Test
    fun `editing is read only by default except for mocks`() {
        val policy = EditingCapabilities()

        assertFalse(policy.preferences)
        assertFalse(policy.featureFlags)
        assertFalse(policy.database)
        assertFalse(policy.files)
        assertTrue(policy.mocks)
        assertFalse(policy.requestExecution)
        assertFalse(policy.captureRules)
    }

    @Test
    fun `read only opts mocks back out`() {
        assertFalse(EditingCapabilities(mocks = false).mocks)
    }

    /**
     * [EditingCapabilities.readOnly] has to keep meaning what its name says even though the no-arg
     * constructor no longer does. A host that asked for read-only and silently got writable mocks
     * would be the worst possible reading of this change.
     */
    @Test
    fun `read only factory grants nothing including mocks`() {
        val policy = EditingCapabilities.readOnly()

        assertFalse(policy.preferences)
        assertFalse(policy.featureFlags)
        assertFalse(policy.database)
        assertFalse(policy.files)
        assertFalse(policy.mocks)
        assertFalse(policy.requestExecution)
        assertFalse(policy.captureRules)
    }

    @Test
    fun `java builder starts from the mocks enabled default`() {
        assertTrue(EditingCapabilities.builder().build().mocks)
        assertFalse(
            EditingCapabilities
                .builder()
                .mocks(false)
                .build()
                .mocks,
        )
    }

    /**
     * The capability object's default is only half the story -- [DevConsoleConfig] has to actually
     * carry it. It used to seed [EditingCapabilities.readOnly], which still grants nothing, so a
     * host that configured no capabilities at all would otherwise keep getting read-only mocks.
     */
    @Test
    fun `default config makes mocks editable and nothing else`() {
        val editing = DevConsoleConfig.default().editingCapabilities

        assertTrue(editing.mocks)
        assertFalse(editing.preferences)
        assertFalse(editing.database)
        assertFalse(editing.files)
        assertFalse(editing.captureRules)
    }

    @Test
    fun `java config builder defaults to editable mocks`() {
        assertTrue(
            DevConsoleConfig
                .builder()
                .build()
                .editingCapabilities.mocks,
        )
    }

    @Test
    fun `java builder enables only requested editing capabilities`() {
        val policy =
            EditingCapabilities
                .builder()
                .preferences(true)
                .mocks(true)
                .build()

        assertTrue(policy.preferences)
        assertTrue(policy.mocks)
        assertFalse(policy.database)
        assertFalse(policy.files)
        assertFalse(policy.requestExecution)
    }

    @Test
    fun `focused policies use persistent sessions and auto binding defaults`() {
        val retention = RetentionPolicy()
        val browser = BrowserConfig()

        assertEquals(10, retention.maxSessions)
        assertEquals(7L * 24L * 60L * 60L * 1000L, retention.maxAgeMs)
        assertEquals(100L * 1024L * 1024L, retention.maxBytes)
        assertEquals(BrowserBinding.AUTO, browser.binding)
        assertEquals(8080..8099, browser.portRange)
    }

    @Test
    fun `browser binding still accepts both explicit modes`() {
        assertEquals(BrowserBinding.LOOPBACK, BrowserConfig(binding = BrowserBinding.LOOPBACK).binding)
        assertEquals(BrowserBinding.LAN, BrowserConfig(binding = BrowserBinding.LAN).binding)
    }

    @Test
    fun `focused policies report every invalid bound`() {
        val retention = RetentionPolicy(maxSessions = 0, maxAgeMs = 0, maxBytes = 0)
        val browser = BrowserConfig(portRange = 0..70_000, sessionCodeTtlMs = 0)

        assertEquals(
            setOf(
                ConfigValidationCode.INVALID_RETENTION_POLICY,
                ConfigValidationCode.INVALID_BROWSER_CONFIGURATION,
            ),
            (retention.validationErrors() + browser.validationErrors()).map { it.code }.toSet(),
        )
    }

    @Test
    fun `focused policies participate in config equivalence and validation`() {
        val editing = EditingCapabilities(preferences = true)
        val retention = RetentionPolicy(maxSessions = 5)
        val browser = BrowserConfig(binding = BrowserBinding.LAN)
        val config =
            DevConsoleConfig
                .default()
                .withEditingCapabilities(editing)
                .withRetentionPolicy(retention)
                .withBrowserConfig(browser)

        assertEquals(editing, config.editingCapabilities)
        assertEquals(retention, config.retentionPolicy)
        assertEquals(browser, config.browserConfig)
        assertTrue(config.validationErrors().isEmpty())
        assertFalse(config.runtimeEquivalentTo(DevConsoleConfig.default()))
    }

    @Test
    fun `java config builder carries focused policies`() {
        val config =
            DevConsoleConfig
                .builder()
                .editingCapabilities(EditingCapabilities(files = true))
                .retentionPolicy(RetentionPolicy(maxSessions = 3))
                .browserConfig(BrowserConfig(binding = BrowserBinding.LAN))
                .build()

        assertTrue(config.editingCapabilities.files)
        assertEquals(3, config.retentionPolicy.maxSessions)
        assertEquals(BrowserBinding.LAN, config.browserConfig.binding)
    }

    @Test
    fun `legacy storage builder values remain the focused retention byte and age source`() {
        val config =
            DevConsoleConfig
                .builder()
                .storagePolicy(
                    StoragePolicy(
                        maxBytes = 5_000,
                        maxAgeMs = 6_000,
                    ),
                ).build()

        assertEquals(5_000L, config.retentionPolicy.maxBytes)
        assertEquals(6_000L, config.retentionPolicy.maxAgeMs)
        assertEquals(5_000L, config.storagePolicy.maxBytes)
        assertEquals(6_000L, config.storagePolicy.maxAgeMs)
    }
}
