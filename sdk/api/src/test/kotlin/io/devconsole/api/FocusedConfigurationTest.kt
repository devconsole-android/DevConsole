package io.devconsole.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusedConfigurationTest {
    @Test
    fun `editing is enabled by default`() {
        val policy = EditingCapabilities()

        assertTrue(policy.preferences)
        assertTrue(policy.featureFlags)
        assertTrue(policy.database)
        assertTrue(policy.files)
        assertTrue(policy.mocks)
        assertTrue(policy.requestExecution)
        assertTrue(policy.captureRules)
    }

    @Test
    fun `read only opts mocks back out`() {
        assertFalse(EditingCapabilities(mocks = false).mocks)
    }

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
    fun `java builder starts from the editable default`() {
        assertTrue(EditingCapabilities.builder().build().mocks)
        assertFalse(
            EditingCapabilities
                .builder()
                .mocks(false)
                .build()
                .mocks,
        )
    }

    @Test
    fun `default config makes every capability editable`() {
        val editing = DevConsoleConfig.default().editingCapabilities

        assertTrue(editing.mocks)
        assertTrue(editing.preferences)
        assertTrue(editing.database)
        assertTrue(editing.files)
        assertTrue(editing.captureRules)
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
    fun `java builder disables only requested editing capabilities`() {
        val policy =
            EditingCapabilities
                .builder()
                .preferences(false)
                .mocks(true)
                .build()

        assertFalse(policy.preferences)
        assertTrue(policy.mocks)
        assertTrue(policy.database)
        assertTrue(policy.files)
        assertTrue(policy.requestExecution)
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
        assertEquals(BrowserSecurity.NONE, DevConsoleConfig.default().browserSecurity)
    }

    @Test
    fun `browser security is opt in and participates in config copies`() {
        val secured = DevConsoleConfig.default().withBrowserSecurity(BrowserSecurity.SESSION_CODE)

        assertEquals(BrowserSecurity.SESSION_CODE, secured.browserSecurity)
        assertFalse(secured.runtimeEquivalentTo(DevConsoleConfig.default()))
        assertEquals(
            BrowserSecurity.SESSION_CODE,
            DevConsoleConfig
                .builder()
                .browserSecurity(BrowserSecurity.SESSION_CODE)
                .build()
                .browserSecurity,
        )
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
