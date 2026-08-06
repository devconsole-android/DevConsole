/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.network.okhttp

import io.devconsole.network.NetworkTransactionRecorder
import okhttp3.EventListener
import okhttp3.OkHttpClient

/**
 * One-call installer for the OkHttp adapter.
 *
 * Getting real DNS/TCP/TLS/wait/download timing phases out of this module requires three
 * coordinated steps: construct a [DevConsoleOkHttpEventListenerFactory], set it as the client's
 * `eventListenerFactory`, and pass that *same instance* to [DevConsoleOkHttpInterceptor]. Miss any
 * one of the three and every phase silently reads `null` forever -- there is no error, just an
 * empty timing bar. This function does all three in one call:
 * ```
 * val client = OkHttpClient.Builder()
 *     .installDevConsole(DevConsole.networkRecorder())
 *     .build()
 * ```
 *
 * If the host already has its own [EventListener.Factory] it wants notified (an APM/tracing tool,
 * say), pass it as [existingEventListenerFactory]. DevConsole chains to it as a delegate --
 * [DevConsoleOkHttpEventListenerFactory] forwards every callback to it in addition to recording
 * timing phases here -- so installing DevConsole never silently drops a host's own listener. Do
 * this instead of calling `.eventListenerFactory(...)` on the builder yourself first: OkHttp allows
 * only one `eventListenerFactory` per client, [OkHttpClient.Builder] exposes no public getter to
 * read one back, and a second call to `.eventListenerFactory(...)` after this one would silently
 * replace what this function just installed.
 *
 * Individual phases are legitimately `null` even when wired correctly: a pooled connection skips
 * DNS/connect, a plaintext request has no TLS handshake, and a cached response that never touches
 * the network has no phases at all. That is by design, not a sign the installer is missing
 * something.
 *
 * The manual three-step form documented on [DevConsoleOkHttpEventListenerFactory] remains available
 * and fully supported for callers who want to manage the interceptor and event listener lifecycles
 * separately.
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

/**
 * Java-friendly mirror of [installDevConsole]. Kotlin extension functions are not callable as
 * instance methods from Java, so this object gives Java hosts the same one-call installer:
 * ```java
 * OkHttpClient client = DevConsoleOkHttp.install(new OkHttpClient.Builder(), DevConsole.networkRecorder())
 *         .addInterceptor(new DevConsoleMockInterceptor(DevConsole.mockEngine()))
 *         .build();
 * ```
 */
object DevConsoleOkHttp {
    @JvmStatic
    @JvmOverloads
    fun install(
        builder: OkHttpClient.Builder,
        recorder: NetworkTransactionRecorder,
        existingEventListenerFactory: EventListener.Factory? = null,
    ): OkHttpClient.Builder = builder.installDevConsole(recorder, existingEventListenerFactory)
}
