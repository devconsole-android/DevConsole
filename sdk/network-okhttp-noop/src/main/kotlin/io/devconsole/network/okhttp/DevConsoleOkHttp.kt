/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.network.okhttp

import io.devconsole.network.NetworkTransactionRecorder
import okhttp3.EventListener
import okhttp3.OkHttpClient

/**
 * Protected-build adapter: mirrors the enabled module's `installDevConsole` signature exactly so
 * host call sites compile unchanged across variants, but wires only no-op components -- the
 * resulting listener factory forwards to [existingEventListenerFactory] without allocating capture
 * state, and the interceptor added alongside it never inspects or records the call.
 */
@JvmOverloads
fun OkHttpClient.Builder.installDevConsole(
    recorder: NetworkTransactionRecorder,
    existingEventListenerFactory: EventListener.Factory? = null,
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
    ): OkHttpClient.Builder = builder.installDevConsole(recorder, existingEventListenerFactory)
}
