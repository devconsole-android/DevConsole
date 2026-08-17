package io.devconsole.mocks

/**
 * Process-wide handle on the [MockEngine] the running DevConsole owns.
 *
 * This exists to close a module-layering gap, not to be a general service locator. The one-call
 * OkHttp installer lives in `:sdk:network-okhttp`, which sits below the facade and so cannot call
 * `DevConsole.mockEngine()`; without a handle it could only wire mocking if the host passed the
 * engine in by hand, which is exactly the extra line the default is meant to remove. The enabled
 * facade publishes its engine here at initialize and the installer reads it back.
 *
 * The protected (no-op) facade publishes nothing, so a release build reads `null` and the no-op
 * installer wires no mock interceptor at all.
 *
 * Hosts do not call this. Pass a [MockEngine] to the installer explicitly if you want to bypass it.
 */
object MockEngineRegistry {
    @Volatile
    private var engine: MockEngine? = null

    /** Replaces whatever was published before -- `initialize` may be called more than once. */
    fun publish(engine: MockEngine) {
        this.engine = engine
    }

    /** Null before `DevConsole.initialize` succeeds, and on a protected build. */
    fun active(): MockEngine? = engine

    fun clear() {
        engine = null
    }
}
