package io.devconsole.mocks

import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The registry exists so `installDevConsole` can wire mocking without the host naming an engine.
 * `:sdk:network-okhttp` sits below the facade and cannot call `DevConsole.mockEngine()`; the enabled
 * facade publishes here at initialize instead, and the installer reads it back.
 *
 * Deliberately process-wide, which is the same lifetime `DevConsole` itself already has: one engine
 * per app, created once at initialize.
 */
class MockEngineRegistryTest {
    @After fun tearDown() = MockEngineRegistry.clear()

    @Test fun `no engine is active until one is published`() {
        MockEngineRegistry.clear()

        assertNull(MockEngineRegistry.active())
    }

    @Test fun `the published engine is what callers read back`() {
        val engine = MockEngine(listOf(MockRule("rule", 1, path = "/orders")))

        MockEngineRegistry.publish(engine)

        assertSame(engine, MockEngineRegistry.active())
    }

    /**
     * `initialize` is documented as re-callable, so a second publish has to win outright. Leaving the
     * first engine in place would point every OkHttp client built afterwards at rules the dashboard
     * is no longer writing to.
     */
    @Test fun `a later publish replaces the engine wholesale`() {
        val first = MockEngine(listOf(MockRule("first", 1, path = "/a")))
        val second = MockEngine(listOf(MockRule("second", 1, path = "/b")))

        MockEngineRegistry.publish(first)
        MockEngineRegistry.publish(second)

        assertSame(second, MockEngineRegistry.active())
    }

    @Test fun `clearing drops the engine`() {
        MockEngineRegistry.publish(MockEngine(listOf(MockRule("rule", 1, path = "/orders"))))

        MockEngineRegistry.clear()

        assertNull(MockEngineRegistry.active())
    }
}
