/**
 * @author Md. Asaduzzaman Nur
 * @since 13/08/26
 */
package io.devconsole

import io.devconsole.api.CaptureCategory
import io.devconsole.api.DevConsoleConfig
import io.devconsole.mocks.MockEngine
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.remoteconfig.RemoteConfigEntry
import io.devconsole.remoteconfig.RemoteConfigFetchInfo
import io.devconsole.remoteconfig.RemoteConfigFetchStatus
import io.devconsole.remoteconfig.RemoteConfigProvider
import io.devconsole.remoteconfig.RemoteConfigRegistry
import io.devconsole.remoteconfig.RemoteConfigSnapshot
import io.devconsole.remoteconfig.RemoteConfigSource
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigInspectorTest {
    private fun registryWith(vararg entries: RemoteConfigEntry): RemoteConfigRegistry =
        RemoteConfigRegistry().apply {
            register(
                object : RemoteConfigProvider {
                    override val id: String = "firebase"

                    override fun snapshot(): RemoteConfigSnapshot =
                        RemoteConfigSnapshot(
                            providerId = "firebase",
                            entries = entries.toList(),
                            fetchInfo =
                                RemoteConfigFetchInfo(
                                    lastFetchEpochMs = 1_755_043_200_000L,
                                    status = RemoteConfigFetchStatus.SUCCESS,
                                    minimumFetchIntervalSeconds = 3600L,
                                ),
                        )
                },
            )
        }

    private fun entry(
        key: String,
        value: String,
    ) = RemoteConfigEntry(key = key, value = value, source = RemoteConfigSource.REMOTE)

    private fun source(
        config: DevConsoleConfig,
        registry: RemoteConfigRegistry,
    ) = FullInspectorDataSource(
        InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key-1234567890".encodeToByteArray())),
        MockEngine(emptyList()),
        configSupplier = { config },
        remoteConfigRegistry = registry,
    )

    private fun enabledConfig() = DevConsoleConfig().withCaptureCategories(CaptureCategory.all())

    @Test
    fun `exposes remote config entries with their source`() {
        val snapshot = source(enabledConfig(), registryWith(entry("checkout_v2", "true"))).snapshot()

        val provider = snapshot.remoteConfig.single()
        assertEquals("firebase", provider.id)
        assertEquals("checkout_v2", provider.entries.single().key)
        assertEquals("true", provider.entries.single().value)
        assertEquals(RemoteConfigSource.REMOTE.wireName, provider.entries.single().source)
    }

    @Test
    fun `carries fetch metadata onto the ui model`() {
        val provider = source(enabledConfig(), registryWith(entry("k", "v"))).snapshot().remoteConfig.single()

        assertEquals(1_755_043_200_000L, provider.lastFetchEpochMs)
        assertEquals(RemoteConfigFetchStatus.SUCCESS.wireName, provider.status)
        assertEquals(3600L, provider.minimumFetchIntervalSeconds)
        assertNull(provider.unavailableReason)
    }

    @Test
    fun `disabling the state category empties remote config`() {
        val config = DevConsoleConfig().withCaptureCategories(CaptureCategory.all() - CaptureCategory.STATE)

        val snapshot = source(config, registryWith(entry("checkout_v2", "true"))).snapshot()

        assertTrue(snapshot.remoteConfig.isEmpty())
    }

    /**
     * The Compose surface reads in-process and never crosses the HTTP boundary, so redaction applied
     * only in the server would leave this path exposed.
     */
    @Test
    fun `redacts a sensitive key on the in-process compose path`() {
        val snapshot = source(enabledConfig(), registryWith(entry("api_key", "super-secret"))).snapshot()

        val redacted =
            snapshot.remoteConfig
                .single()
                .entries
                .single()
        assertEquals(RedactionPolicy.default().replacement, redacted.value)
        assertTrue(redacted.redacted)
    }

    @Test
    fun `keeps the key readable so you can see which value was withheld`() {
        val snapshot = source(enabledConfig(), registryWith(entry("api_key", "super-secret"))).snapshot()

        assertEquals(
            "api_key",
            snapshot.remoteConfig
                .single()
                .entries
                .single()
                .key,
        )
    }

    @Test
    fun `leaves an ordinary value untouched`() {
        val snapshot = source(enabledConfig(), registryWith(entry("checkout_v2", "true"))).snapshot()

        val plain =
            snapshot.remoteConfig
                .single()
                .entries
                .single()
        assertEquals("true", plain.value)
        assertFalse(plain.redacted)
    }

    @Test
    fun `surfaces an unavailable provider rather than dropping it`() {
        val registry =
            RemoteConfigRegistry().apply {
                register(
                    object : RemoteConfigProvider {
                        override val id: String = "firebase"

                        override fun snapshot(): RemoteConfigSnapshot = error("firebase exploded")
                    },
                )
            }

        val provider = source(enabledConfig(), registry).snapshot().remoteConfig.single()

        assertEquals("firebase exploded", provider.unavailableReason)
        assertTrue(provider.entries.isEmpty())
    }

    @Test
    fun `a null registry yields no remote config rather than failing`() {
        val plain =
            FullInspectorDataSource(
                InMemoryNetworkTransactionStore(
                    NetworkCursorCodec("network-cursor-key-1234567890".encodeToByteArray()),
                ),
                MockEngine(emptyList()),
                configSupplier = { enabledConfig() },
            )

        assertTrue(plain.snapshot().remoteConfig.isEmpty())
    }

    @Test
    fun `redaction is applied by the shared boundary both surfaces read`() {
        val entries =
            redactingBoundary().apply(
                listOf(entry("access_token", "raw"), entry("checkout_v2", "true")),
            )

        assertEquals(RedactionPolicy.default().replacement, entries.first().value)
        assertTrue(entries.first().redacted)
        assertEquals("true", entries.last().value)
        assertFalse(entries.last().redacted)
    }

    /**
     * The stock policy is an HTTP-header list matched by exact name, but Remote Config keys are
     * written snake_case and camelCase -- so `api_key` must still match the policy's `api-key`.
     */
    @Test
    fun `matches sensitive names across separator styles`() {
        val entries =
            redactingBoundary().apply(
                listOf(entry("api_key", "raw"), entry("apiKey", "raw"), entry("API-KEY", "raw")),
            )

        assertTrue(entries.all { it.redacted })
        assertTrue(entries.all { it.value == RedactionPolicy.default().replacement })
    }

    @Test
    fun `a bearer token inside an ordinary value is still scrubbed`() {
        val entries = redactingBoundary().apply(listOf(entry("welcome_banner", "Bearer abc123def")))

        assertTrue(entries.single().redacted)
        assertFalse(entries.single().value.contains("abc123def"))
    }

    @Test
    fun `a separator-free ordinary key is not over-redacted`() {
        val entries = redactingBoundary().apply(listOf(entry("checkout_v2", "true"), entry("monkey", "yes")))

        assertTrue(entries.none { it.redacted })
    }

    private fun redactingBoundary() =
        RedactingRemoteConfig(RedactionEngine(RedactionPolicy.default()), RedactionPolicy.default())
}
