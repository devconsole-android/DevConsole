package io.devconsole.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StateProviderTest {
    @Test
    fun `registry evaluates named providers only when a snapshot is requested`() {
        var evaluations = 0
        val registry = StateRegistry()
        registry.register(
            stateProvider("session") {
                evaluations += 1
                StateSnapshot(mapOf("signedIn" to StateValue.BooleanValue(true)))
            },
        )

        assertEquals(0, evaluations)
        assertEquals(
            StateValue.BooleanValue(true),
            registry.snapshot("session")!!.values.getValue("signedIn"),
        )
        assertEquals(1, evaluations)
    }

    @Test
    fun `state values model redaction unavailable values and binary metadata without host reflection`() {
        val snapshot =
            StateSnapshot(
                mapOf(
                    "token" to StateValue.Redacted,
                    "cache" to StateValue.Unavailable("not initialized"),
                    "avatar" to StateValue.BinaryMetadata(byteLength = 12, mediaType = "image/png"),
                ),
            )

        assertEquals(StateValue.Redacted, snapshot.values.getValue("token"))
        assertTrue(snapshot.values.getValue("cache") is StateValue.Unavailable)
        assertEquals(12, (snapshot.values.getValue("avatar") as StateValue.BinaryMetadata).byteLength)
    }

    @Test
    fun `mutators are separate host-declared commands rather than reflective state access`() {
        val registry = StateRegistry()
        registry.register(
            object : StateProvider {
                override val id = "session"

                override fun snapshot() = StateSnapshot(emptyMap())

                override val mutators =
                    listOf(
                        StateMutator("clear-cache", "{\"type\":\"object\"}") {
                            StateMutationResult.Success(
                                StateSnapshot(mapOf("cleared" to StateValue.BooleanValue(true))),
                            )
                        },
                    )
            },
        )

        val result = registry.mutate("session", "clear-cache", "{}")

        assertEquals(
            StateValue.BooleanValue(true),
            (result as StateMutationResult.Success).snapshot.values.getValue("cleared"),
        )
    }

    @Test
    fun `mutators(id) returns a defensive copy, not a live handle into a mutable-list-backed provider`() {
        val registry = StateRegistry()
        val backingList =
            mutableListOf(StateMutator("clear-cache") { StateMutationResult.Success(StateSnapshot(emptyMap())) })
        registry.register(
            object : StateProvider {
                override val id = "session"

                override fun snapshot() = StateSnapshot(emptyMap())

                // Deliberately backed by the same live MutableList a provider implementation might
                // reasonably use internally, to prove StateRegistry.mutators(id) never hands that
                // instance back out.
                override val mutators: List<StateMutator> get() = backingList
            },
        )

        val firstRead = registry.mutators("session")
        assertNotSame("must be a copy, not the provider's own list instance", backingList, firstRead)

        backingList.add(StateMutator("evict-all") { StateMutationResult.Success(StateSnapshot(emptyMap())) })

        assertEquals("a snapshot taken before the mutation must not observe it", 1, firstRead.size)
        assertEquals(
            "a fresh read still reflects the provider's current catalogue",
            2,
            registry.mutators("session").size,
        )
    }

    @Test
    fun `mutators(id) is empty, not null, for an unknown provider id`() {
        val registry = StateRegistry()

        assertTrue(registry.mutators("no-such-provider").isEmpty())
    }
}
