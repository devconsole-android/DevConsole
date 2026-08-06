package io.devconsole.state
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FeatureFlagsTest {
    @Test fun `uses session override without mutating provider default`() {
        val flags = SessionFeatureFlags(listOf(FeatureFlag("new_ui", false)))
        flags.override("new_ui", "true")
        assertEquals(true, flags.booleanValue("new_ui"))
        assertEquals("false", flags.flags().single().defaultValue)
    }

    @Test fun `exposes boolean metadata and resettable session overrides`() {
        val flags =
            SessionFeatureFlags(listOf(FeatureFlag("new_ui", false, description = "New checkout", source = "remote")))
        flags.override("new_ui", "true")

        assertEquals(FeatureFlagType.BOOLEAN, flags.flags().single().type)
        assertEquals(setOf("true", "false"), flags.flags().single().allowedValues)
        assertEquals("remote", flags.flags().single().source)
        flags.reset()
        assertEquals(false, flags.booleanValue("new_ui"))
    }

    @Test
    fun `a string flag offers named options, which is what an environment switcher needs`() {
        val flags =
            SessionFeatureFlags(
                listOf(FeatureFlag.ofOptions("api.environment", "staging", setOf("staging", "production"))),
            )

        assertEquals("staging", flags.value("api.environment"))
        flags.override("api.environment", "production")
        assertEquals("production", flags.value("api.environment"))
        assertEquals(mapOf("api.environment" to "production"), flags.overrides())
    }

    @Test
    fun `a value outside the declared options is rejected`() {
        val flags =
            SessionFeatureFlags(
                listOf(FeatureFlag.ofOptions("api.environment", "staging", setOf("staging", "production"))),
            )

        val failure = runCatching { flags.override("api.environment", "localhost") }

        assertTrue(failure.isFailure)
        assertEquals("staging", flags.value("api.environment"))
    }

    @Test
    fun `a boolean flag still reads as a boolean`() {
        val flags = SessionFeatureFlags(listOf(FeatureFlag.ofBoolean("new_ui", defaultValue = false)))

        flags.override("new_ui", "true")

        assertEquals(FeatureFlagType.BOOLEAN, flags.flags().single().type)
        assertEquals(true, flags.booleanValue("new_ui"))
    }

    @Test fun `an unregistered key reads as the empty-false default instead of throwing`() {
        val flags = SessionFeatureFlags(listOf(FeatureFlag("new_ui", false)))

        assertEquals("", flags.value("typo"))
        assertEquals(false, flags.booleanValue("typo"))
    }

    // Reproduces the production shape: override() runs on the dashboard's Ktor thread while
    // value()/booleanValue()/overrides() are read from arbitrary host threads. Before the
    // ConcurrentHashMap fix, a plain mutableMapOf() here could throw ConcurrentModificationException
    // -- or worse, silently corrupt -- when a write landed mid-iteration of a read. This test hammers
    // both sides at once across many threads and fails loudly if either happens.
    @Test
    fun `concurrent overrides and reads never throw or corrupt the map`() {
        val flagKeys = (0 until 20).map { "flag_$it" }
        val flags = SessionFeatureFlags(flagKeys.map { FeatureFlag(it, defaultValue = false) })
        val threadCount = 16
        val iterationsPerThread = 2_000
        val failures = CopyOnWriteArrayList<Throwable>()
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val tasks =
                (0 until threadCount).map { worker ->
                    Runnable {
                        ready.countDown()
                        start.await()
                        try {
                            repeat(iterationsPerThread) { iteration ->
                                val key = flagKeys[(worker + iteration) % flagKeys.size]
                                if (worker % 2 == 0) {
                                    flags.override(key, if (iteration % 2 == 0) "true" else "false")
                                } else {
                                    // Iterating the returned map is exactly the operation that a
                                    // ConcurrentModificationException would surface on a plain HashMap
                                    // snapshot racing a concurrent write to the live map.
                                    flags.overrides().forEach { (_, value) ->
                                        require(
                                            value == "true" || value == "false",
                                        )
                                    }
                                    flags.booleanValue(key)
                                    flags.value(key)
                                }
                            }
                        } catch (failure: Throwable) {
                            failures += failure
                        }
                    }
                }
            val futures = tasks.map { pool.submit(it) }
            ready.await()
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }

        assertTrue("expected no failures, got: $failures", failures.isEmpty())
    }
}
