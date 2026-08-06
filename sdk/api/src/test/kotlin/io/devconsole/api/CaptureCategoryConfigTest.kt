/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCategoryConfigTest {
    @Test
    fun `default config enables every capture category`() {
        val config = DevConsoleConfig.default()

        assertEquals(CaptureCategory.all(), config.captureCategories)
        CaptureCategory.entries.forEach { assertTrue(config.capturesCategory(it)) }
    }

    @Test
    fun `withCaptureCategories preserves every other additive policy`() {
        val editing = EditingCapabilities(preferences = true)
        val crash = CrashPolicy(crashCaptureEnabled = false)
        val config =
            DevConsoleConfig
                .default()
                .withEditingCapabilities(editing)
                .withCrashPolicy(crash)
                .withCaptureCategories(CaptureCategory.SOCKET, CaptureCategory.MQTT)

        assertEquals(setOf(CaptureCategory.SOCKET, CaptureCategory.MQTT), config.captureCategories)
        assertEquals(editing, config.editingCapabilities)
        assertEquals(crash, config.crashPolicy)
    }

    @Test
    fun `runtimeEquivalentTo returns false when only categories differ`() {
        val base = DevConsoleConfig.default()
        val narrowed = base.withCaptureCategories(CaptureCategory.NETWORK)

        assertFalse(base.runtimeEquivalentTo(narrowed))
        assertTrue(base.runtimeEquivalentTo(base.withCaptureCategories(CaptureCategory.all())))
    }

    @Test
    fun `empty capture category set is legal and reports exactly one validation error`() {
        val config = DevConsoleConfig.default().withCaptureCategories(CaptureCategory.none())

        val errors = config.validationErrors()

        assertEquals(1, errors.count { it.code == ConfigValidationCode.NO_CAPTURE_CATEGORIES_ENABLED })
    }

    @Test
    fun `wireNames order is stable declaration order regardless of set iteration order`() {
        val values = setOf(CaptureCategory.MOCKS, CaptureCategory.NETWORK, CaptureCategory.CRASHES)

        assertEquals(listOf("network", "crashes", "mocks"), CaptureCategory.wireNames(values))
    }

    @Test
    fun `fromWireName resolves case-insensitively and returns null for unknown values`() {
        assertEquals(CaptureCategory.SOCKET, CaptureCategory.fromWireName("SOCKET"))
        assertEquals(null, CaptureCategory.fromWireName("bogus"))
        assertEquals(null, CaptureCategory.fromWireName(null))
    }

    @Test
    fun `java builder configures capture categories`() {
        val config =
            DevConsoleConfig
                .builder()
                .captureCategories(CaptureCategory.of(CaptureCategory.SOCKET, CaptureCategory.MQTT))
                .build()

        assertEquals(setOf(CaptureCategory.SOCKET, CaptureCategory.MQTT), config.captureCategories)
    }

    @Test
    fun `builder addCaptureCategory accumulates onto the default set`() {
        val config =
            DevConsoleConfig
                .builder()
                .captureCategories(emptySet())
                .addCaptureCategory(CaptureCategory.LOGS)
                .addCaptureCategory(CaptureCategory.CRASHES)
                .build()

        assertEquals(setOf(CaptureCategory.LOGS, CaptureCategory.CRASHES), config.captureCategories)
    }
}
