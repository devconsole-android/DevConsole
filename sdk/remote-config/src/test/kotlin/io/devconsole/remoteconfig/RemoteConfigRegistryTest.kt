/**
 * @author Md. Asaduzzaman Nur
 * @since 13/08/26
 */
package io.devconsole.remoteconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

class RemoteConfigRegistryTest {
    @Test
    fun `snapshot returns the registered provider's entries`() {
        val registry = RemoteConfigRegistry()
        registry.register(provider("firebase", entry("checkout_v2", "true", RemoteConfigSource.REMOTE)))

        val snapshot = registry.snapshot("firebase")

        assertNotNull(snapshot)
        assertEquals(listOf("checkout_v2"), snapshot!!.entries.map { it.key })
        assertEquals(RemoteConfigSource.REMOTE, snapshot.entries.single().source)
    }

    @Test
    fun `unknown provider id reads as null rather than throwing`() {
        assertNull(RemoteConfigRegistry().snapshot("absent"))
    }

    @Test
    fun `duplicate provider id is rejected`() {
        val registry = RemoteConfigRegistry()
        registry.register(provider("firebase"))

        val failure = runCatching { registry.register(provider("firebase")) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `blank provider id is rejected`() {
        val failure = runCatching { RemoteConfigRegistry().register(provider("  ")) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a provider that throws degrades to an unavailable snapshot instead of propagating`() {
        val registry = RemoteConfigRegistry()
        registry.register(
            object : RemoteConfigProvider {
                override val id: String = "broken"

                override fun snapshot(): RemoteConfigSnapshot = error("firebase exploded")
            },
        )

        val snapshot = registry.snapshot("broken")

        assertNotNull(snapshot)
        assertEquals("firebase exploded", snapshot!!.unavailableReason)
        assertTrue(snapshot.entries.isEmpty())
    }

    @Test
    fun `an unavailable snapshot reports no confident fetch status`() {
        val registry = RemoteConfigRegistry()
        registry.register(
            object : RemoteConfigProvider {
                override val id: String = "broken"

                override fun snapshot(): RemoteConfigSnapshot = error("boom")
            },
        )

        val fetch = registry.snapshot("broken")!!.fetchInfo

        assertEquals(RemoteConfigFetchStatus.UNKNOWN, fetch.status)
        assertNull(fetch.lastFetchEpochMs)
        assertNull(fetch.minimumFetchIntervalSeconds)
    }

    @Test
    fun `a throwable with no message falls back to its class name`() {
        val registry = RemoteConfigRegistry()
        registry.register(
            object : RemoteConfigProvider {
                override val id: String = "broken"

                // Deliberately not error(): that always attaches a message, and a *message-less*
                // throwable is precisely the case this test covers.
                @Suppress("UseCheckOrError")
                override fun snapshot(): RemoteConfigSnapshot = throw IllegalStateException()
            },
        )

        assertEquals("IllegalStateException", registry.snapshot("broken")!!.unavailableReason)
    }

    @Test
    fun `an oversized value is truncated by the registry rather than by each provider`() {
        val registry = RemoteConfigRegistry()
        val oversized = "x".repeat(RemoteConfigRegistry.MAX_VALUE_LENGTH + 100)
        registry.register(provider("firebase", entry("blob", oversized, RemoteConfigSource.REMOTE)))

        val stored = registry.snapshot("firebase")!!.entries.single()

        assertEquals(RemoteConfigRegistry.MAX_VALUE_LENGTH, stored.value.length)
        assertTrue(stored.truncated)
    }

    @Test
    fun `a value at the cap is not marked truncated`() {
        val registry = RemoteConfigRegistry()
        val exact = "x".repeat(RemoteConfigRegistry.MAX_VALUE_LENGTH)
        registry.register(provider("firebase", entry("blob", exact, RemoteConfigSource.REMOTE)))

        val stored = registry.snapshot("firebase")!!.entries.single()

        assertEquals(exact, stored.value)
        assertTrue(!stored.truncated)
    }

    @Test
    fun `snapshots returns every provider in registration order`() {
        val registry = RemoteConfigRegistry()
        registry.register(provider("firebase"))
        registry.register(provider("configcat"))

        assertEquals(listOf("firebase", "configcat"), registry.snapshots().map { it.providerId })
        assertEquals(listOf("firebase", "configcat"), registry.providerIds())
    }

    @Test
    fun `a provider reporting an id different from its registration keeps the registered id`() {
        val registry = RemoteConfigRegistry()
        registry.register(
            object : RemoteConfigProvider {
                override val id: String = "firebase"

                override fun snapshot(): RemoteConfigSnapshot =
                    RemoteConfigSnapshot(
                        providerId = "something-else",
                        entries = emptyList(),
                        fetchInfo = unknownFetch(),
                    )
            },
        )

        assertEquals("firebase", registry.snapshot("firebase")!!.providerId)
    }

    private fun unknownFetch() =
        RemoteConfigFetchInfo(
            lastFetchEpochMs = null,
            status = RemoteConfigFetchStatus.UNKNOWN,
            minimumFetchIntervalSeconds = null,
        )

    @Test
    fun `clear drops every registration so the same id can be registered again`() {
        val registry = RemoteConfigRegistry()
        registry.register(provider("firebase", entry("k", "old", RemoteConfigSource.REMOTE)))

        registry.clear()
        // The point of clear(): re-registering the same id must be accepted, not refused as a
        // duplicate. A stop() -> initialize() carrying a replacement adapter under the same id
        // would otherwise leave every surface reading through the torn-down original.
        registry.register(provider("firebase", entry("k", "new", RemoteConfigSource.REMOTE)))

        assertEquals(listOf("firebase"), registry.providerIds())
        val reread = registry.snapshots().single()
        assertEquals("new", reread.entries.single().value)
    }

    /**
     * Registering while reading is a documented path (`DevConsole.registerRemoteConfigProvider`
     * from a post-fetch listener) and the readers are the Ktor thread and the inspector's
     * dispatcher, so an unguarded `LinkedHashMap` would throw `ConcurrentModificationException`
     * into the host app -- the one thing the read path promises never to do.
     */
    @Test
    fun `registering while snapshots is iterating does not throw into the reader`() {
        val registry = RemoteConfigRegistry()
        repeat(INITIAL_PROVIDERS) { i ->
            registry.register(provider("p$i", entry("k", "v", RemoteConfigSource.REMOTE)))
        }
        val failures = CopyOnWriteArrayList<Throwable>()
        val start = CountDownLatch(1)

        val writer =
            Thread {
                start.await()
                repeat(LATE_PROVIDERS) { i ->
                    runCatching { registry.register(provider("late$i", entry("k", "v", RemoteConfigSource.REMOTE))) }
                }
            }
        val reader =
            Thread {
                start.await()
                repeat(READ_ROUNDS) {
                    runCatching { registry.snapshots() }.onFailure(failures::add)
                }
            }
        writer.start()
        reader.start()
        start.countDown()
        writer.join()
        reader.join()

        assertTrue("reader saw ${failures.firstOrNull()}", failures.isEmpty())
    }

    private companion object {
        const val INITIAL_PROVIDERS = 20
        const val LATE_PROVIDERS = 200
        const val READ_ROUNDS = 200
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
}
