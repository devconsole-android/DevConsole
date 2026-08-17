/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.network.okhttp

import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockEngineRegistry
import io.devconsole.network.NetworkTransactionRecorder
import okhttp3.EventListener
import okhttp3.OkHttpClient

/**
 * Protected-build adapter: mirrors the enabled module's `installDevConsole` signature exactly so
 * host call sites compile unchanged across variants, but wires only no-op components -- the
 * resulting listener factory forwards to [existingEventListenerFactory] without allocating capture
 * state, and the interceptor added alongside it never inspects or records the call.
 *
 * [mockEngine] is accepted and discarded. Its default reads [MockEngineRegistry], which nothing
 * populates on a protected build -- the no-op facade publishes no engine -- so it resolves to `null`
 * anyway; taking the parameter at all is purely so a host that passes one explicitly still compiles
 * in release. No mock interceptor is ever added here, and no rule can alter release traffic.
 */
@JvmOverloads
fun OkHttpClient.Builder.installDevConsole(
    recorder: NetworkTransactionRecorder,
    existingEventListenerFactory: EventListener.Factory? = null,
    @Suppress("UNUSED_PARAMETER") mockEngine: MockEngine? = MockEngineRegistry.active(),
): OkHttpClient.Builder {
    val listenerFactory = DevConsoleOkHttpEventListenerFactory(existingEventListenerFactory)
    return eventListenerFactory(listenerFactory)
        .addInterceptor(DevConsoleOkHttpInterceptor(recorder, listenerFactory))
}

/** Protected-build mirror of [io.devconsole.network.okhttp.DevConsoleOkHttp] for Java hosts. */
object DevConsoleOkHttp {
    @JvmStatic
    @JvmOverloads
    fun install(
        builder: OkHttpClient.Builder,
        recorder: NetworkTransactionRecorder,
        existingEventListenerFactory: EventListener.Factory? = null,
        mockEngine: MockEngine? = MockEngineRegistry.active(),
    ): OkHttpClient.Builder = builder.installDevConsole(recorder, existingEventListenerFactory, mockEngine)
}
