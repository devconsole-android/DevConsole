/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InspectorRemoteConfigOverrideTest {
    @Test
    fun `an entry without an override has no provider value line`() {
        val entry = InspectorRemoteConfigEntryUi(key = "checkout_v2", value = "on", source = "remote")

        assertNull(remoteConfigProviderValueLabel(entry))
    }

    @Test
    fun `an overridden entry names the provider value and its source`() {
        val entry =
            InspectorRemoteConfigEntryUi(
                key = "checkout_v2",
                value = "on",
                source = "override",
                providerValue = "off",
                providerSource = "remote",
            )

        assertEquals("Provider value: off (remote)", remoteConfigProviderValueLabel(entry))
    }

    @Test
    fun `an overridden entry with no provider value falls back to an unknown marker`() {
        val entry =
            InspectorRemoteConfigEntryUi(
                key = "checkout_v2",
                value = "on",
                source = "override",
                providerSource = "default",
            )

        assertEquals("Provider value: <unknown> (default)", remoteConfigProviderValueLabel(entry))
    }

    @Test
    fun `the row value line names the provider value only while overridden`() {
        val plain = InspectorRemoteConfigEntryUi(key = "promo", value = "Free delivery", source = "remote")
        val overridden =
            plain.copy(
                value = "Set locally",
                source = "override",
                providerValue = "Free delivery",
                providerSource = "remote",
            )

        assertEquals("Free delivery", remoteConfigRowSubtitle(plain))
        assertEquals("Set locally · provider: Free delivery", remoteConfigRowSubtitle(overridden))
    }

    @Test
    fun `a redacted overridden row never shows either value`() {
        val entry =
            InspectorRemoteConfigEntryUi(
                key = "api_key",
                value = "secret",
                source = "override",
                redacted = true,
                providerValue = "older-secret",
                providerSource = "remote",
            )

        assertEquals("<redacted>", remoteConfigRowSubtitle(entry))
    }
}
