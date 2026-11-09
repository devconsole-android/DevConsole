/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.remoteconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigOverridesTest {
    @Test
    fun `an override replaces the value and keeps the provider value and source`() {
        val registry = RemoteConfigRegistry()
        registry.register(provider("firebase", entry("checkout_v2", "false", RemoteConfigSource.REMOTE)))

        registry.overrides.setOverride("firebase", "checkout_v2", "true")

        val stored = registry.snapshot("firebase")!!.entries.single()
        assertEquals("true", stored.value)
        assertEquals(RemoteConfigSource.OVERRIDE, stored.source)
        assertEquals("false", stored.providerValue)
        assertEquals(RemoteConfigSource.REMOTE, stored.providerSource)
    }

    @Test
    fun `clearing an override restores the provider value`() {
        val registry = RemoteConfigRegistry()
        registry.register(provider("firebase", entry("checkout_v2", "false", RemoteConfigSource.REMOTE)))
        registry.overrides.setOverride("firebase", "checkout_v2", "true")

        registry.overrides.clearOverride("firebase", "checkout_v2")

        val restored = registry.snapshot("firebase")!!.entries.single()
        assertEquals("false", restored.value)
        assertEquals(RemoteConfigSource.REMOTE, restored.source)
        assertNull(restored.providerValue)
        assertNull(restored.providerSource)
    }

    @Test
    fun `an override for an unreported key is appended after the provider entries in key order`() {
        val registry = RemoteConfigRegistry()
        registry.register(
            provider(
                "firebase",
                entry("zeta", "1", RemoteConfigSource.REMOTE),
                entry("alpha", "2", RemoteConfigSource.REMOTE),
            ),
        )
        registry.overrides.setOverride("firebase", "unreported-b", "b")
        registry.overrides.setOverride("firebase", "unreported-a", "a")

        val entries = registry.snapshot("firebase")!!.entries

        assertEquals(
            listOf("zeta", "alpha", "unreported-a", "unreported-b"),
            entries.map { it.key },
        )
        val added = entries.last()
        assertEquals("b", added.value)
        assertEquals(RemoteConfigSource.OVERRIDE, added.source)
        assertNull(added.providerValue)
        assertNull(added.providerSource)
    }

    @Test
    fun `an unavailable provider keeps an empty entry list even when overrides name it`() {
        val registry = RemoteConfigRegistry()
        registry.register(
            object : RemoteConfigProvider {
                override val id: String = "broken"

                override fun snapshot(): RemoteConfigSnapshot = error("boom")
            },
        )
        registry.overrides.setOverride("broken", "key", "override")

        val snapshot = registry.snapshot("broken")!!

        assertTrue(snapshot.entries.isEmpty())
        assertEquals("boom", snapshot.unavailableReason)
    }

    @Test
    fun `a snapshot that reports itself unavailable does not gain override entries`() {
        val registry = RemoteConfigRegistry()
        registry.register(
            object : RemoteConfigProvider {
                override val id: String = "firebase"

                override fun snapshot(): RemoteConfigSnapshot =
                    RemoteConfigSnapshot(
                        providerId = id,
                        entries = emptyList(),
                        fetchInfo = unknownFetch(),
                        unavailableReason = "unreadable",
                    )
            },
        )
        registry.overrides.setOverride("firebase", "key", "override")

        val snapshot = registry.snapshot("firebase")!!

        assertTrue(snapshot.entries.isEmpty())
        assertEquals("unreadable", snapshot.unavailableReason)
    }

    @Test
    fun `overrideValue answers from the store even when the provider never reported the key`() {
        val registry = RemoteConfigRegistry()
        registry.overrides.setOverride("firebase", "checkout_v2", "true")

        assertEquals("true", registry.overrideValue("firebase", "checkout_v2"))
        assertNull(registry.overrideValue("firebase", "missing"))
        assertNull(registry.overrideValue("configcat", "checkout_v2"))
    }

    @Test
    fun `clear keeps overrides because they are user intent rather than session state`() {
        val registry = RemoteConfigRegistry()
        registry.register(provider("firebase", entry("key", "remote", RemoteConfigSource.REMOTE)))
        registry.overrides.setOverride("firebase", "key", "override")

        registry.clear()

        assertEquals("override", registry.overrideValue("firebase", "key"))
        assertTrue(registry.providerIds().isEmpty())
        registry.register(provider("firebase", entry("key", "remote", RemoteConfigSource.REMOTE)))
        assertEquals(
            "override",
            registry
                .snapshot("firebase")!!
                .entries
                .single()
                .value,
        )
    }

    @Test
    fun `an override is applied under the id the provider was registered with`() {
        val registry = RemoteConfigRegistry()
        registry.register(
            object : RemoteConfigProvider {
                override val id: String = "firebase"

                override fun snapshot(): RemoteConfigSnapshot =
                    RemoteConfigSnapshot(
                        providerId = "reported-differently",
                        entries = listOf(entry("key", "remote", RemoteConfigSource.REMOTE)),
                        fetchInfo = unknownFetch(),
                    )
            },
        )
        registry.overrides.setOverride("firebase", "key", "override")
        registry.overrides.setOverride("reported-differently", "key", "ignored")

        val snapshot = registry.snapshot("firebase")!!

        assertEquals("firebase", snapshot.providerId)
        assertEquals("override", snapshot.entries.single().value)
    }

    @Test
    fun `an override applies only to its own provider`() {
        val registry = RemoteConfigRegistry()
        registry.register(provider("firebase", entry("key", "remote", RemoteConfigSource.REMOTE)))
        registry.register(provider("configcat", entry("key", "remote", RemoteConfigSource.REMOTE)))
        registry.overrides.setOverride("firebase", "key", "override")

        val (firebase, configcat) = registry.snapshots()

        assertEquals("override", firebase.entries.single().value)
        assertEquals("remote", configcat.entries.single().value)
        assertNull(configcat.entries.single().providerValue)
    }

    @Test
    fun `the provider value is captured after the registry cap`() {
        val registry = RemoteConfigRegistry()
        val oversized = "x".repeat(RemoteConfigRegistry.MAX_VALUE_LENGTH + 10)
        registry.register(provider("firebase", entry("blob", oversized, RemoteConfigSource.REMOTE)))
        registry.overrides.setOverride("firebase", "blob", "short")

        val stored = registry.snapshot("firebase")!!.entries.single()

        assertEquals(RemoteConfigRegistry.MAX_VALUE_LENGTH, stored.providerValue!!.length)
        assertTrue(stored.truncated)
    }
}

class InMemoryRemoteConfigOverrideStoreTest {
    @Test
    fun `overrides keep insertion order for providers and keys`() {
        val initial: Map<String, Map<String, String>> =
            linkedMapOf(
                "second" to linkedMapOf("z" to "1", "a" to "2"),
                "first" to linkedMapOf("key" to "value"),
            )

        val store = InMemoryRemoteConfigOverrideStore(initial)

        assertEquals(listOf("second", "first"), store.overrides().keys.toList())
        assertEquals(listOf("z", "a"), store.overrides()["second"]!!.keys.toList())
    }

    @Test
    fun `overrides returns a copy that cannot mutate the store`() {
        val store = InMemoryRemoteConfigOverrideStore()
        store.setOverride("firebase", "key", "value")

        @Suppress("UNCHECKED_CAST")
        val copy = store.overrides() as MutableMap<*, *>
        copy.clear()

        assertEquals(mapOf("firebase" to mapOf("key" to "value")), store.overrides())
    }

    @Test
    fun `initial overrides are applied and validated`() {
        val store = InMemoryRemoteConfigOverrideStore(mapOf("firebase" to mapOf("key" to "value")))

        assertEquals("value", store.overrides()["firebase"]?.get("key"))
        assertIllegalArgument {
            InMemoryRemoteConfigOverrideStore(mapOf("  " to mapOf("key" to "value")))
        }
    }

    @Test
    fun `setting an override again replaces the previous value`() {
        val store = InMemoryRemoteConfigOverrideStore()

        store.setOverride("firebase", "key", "one")
        store.setOverride("firebase", "key", "two")

        assertEquals(mapOf("key" to "two"), store.overrides()["firebase"])
    }

    @Test
    fun `clearing removes only the requested pair and drops the empty provider`() {
        val store = InMemoryRemoteConfigOverrideStore()
        store.setOverride("firebase", "first", "1")
        store.setOverride("firebase", "second", "2")

        store.clearOverride("firebase", "first")

        assertEquals(mapOf("second" to "2"), store.overrides()["firebase"])
        store.clearOverride("firebase", "second")
        assertTrue(store.overrides().isEmpty())
    }

    @Test
    fun `a listener fires on set, clear and clearAll`() {
        val store = InMemoryRemoteConfigOverrideStore()
        var fired = 0
        store.addListener { fired++ }

        store.setOverride("firebase", "key", "value")
        store.clearOverride("firebase", "key")
        store.setOverride("firebase", "key", "value")
        store.clearAll()

        assertEquals(4, fired)
    }

    @Test
    fun `a removed listener stops receiving notifications`() {
        val store = InMemoryRemoteConfigOverrideStore()
        var fired = 0
        val listener = RemoteConfigOverrideListener { fired++ }
        store.addListener(listener)
        store.removeListener(listener)

        store.setOverride("firebase", "key", "value")

        assertEquals(0, fired)
    }

    @Test
    fun `a throwing listener is contained and does not stop the others`() {
        val store = InMemoryRemoteConfigOverrideStore()
        val healthyCalls = mutableListOf<Unit>()
        store.addListener { error("listener exploded") }
        store.addListener { healthyCalls.add(Unit) }

        store.setOverride("firebase", "key", "value")

        assertEquals(1, healthyCalls.size)
        assertEquals("value", store.overrides()["firebase"]?.get("key"))
    }

    @Test
    fun `blank provider ids and keys are rejected`() {
        val store = InMemoryRemoteConfigOverrideStore()

        assertIllegalArgument { store.setOverride("  ", "key", "value") }
        assertIllegalArgument { store.clearOverride("  ", "key") }
        assertIllegalArgument { store.setOverride("firebase", "  ", "value") }
        assertIllegalArgument { store.clearOverride("firebase", "  ") }
    }

    @Test
    fun `a key at the cap is accepted and one character more is rejected`() {
        val store = InMemoryRemoteConfigOverrideStore()
        val atCap = "k".repeat(InMemoryRemoteConfigOverrideStore.MAX_KEY_LENGTH)

        store.setOverride("firebase", atCap, "value")

        assertEquals("value", store.overrides()["firebase"]?.get(atCap))
        assertIllegalArgument { store.setOverride("firebase", "${atCap}k", "value") }
    }

    @Test
    fun `a value at the cap is accepted and one character more is rejected`() {
        val store = InMemoryRemoteConfigOverrideStore()
        val atCap = "v".repeat(RemoteConfigRegistry.MAX_VALUE_LENGTH)

        store.setOverride("firebase", "key", atCap)

        assertEquals(atCap, store.overrides()["firebase"]?.get("key"))
        assertIllegalArgument { store.setOverride("firebase", "key", "${atCap}v") }
    }
}

private fun assertIllegalArgument(block: () -> Unit) {
    val failure = runCatching(block).exceptionOrNull()

    assertTrue("expected IllegalArgumentException but was $failure", failure is IllegalArgumentException)
}

private fun entry(
    key: String,
    value: String,
    source: RemoteConfigSource,
) = RemoteConfigEntry(key = key, value = value, source = source)

private fun provider(
    id: String,
    vararg entries: RemoteConfigEntry,
): RemoteConfigProvider =
    object : RemoteConfigProvider {
        override val id: String = id

        override fun snapshot(): RemoteConfigSnapshot =
            RemoteConfigSnapshot(
                providerId = id,
                entries = entries.toList(),
                fetchInfo = unknownFetch(),
            )
    }

private fun unknownFetch() =
    RemoteConfigFetchInfo(
        lastFetchEpochMs = null,
        status = RemoteConfigFetchStatus.UNKNOWN,
        minimumFetchIntervalSeconds = null,
    )
