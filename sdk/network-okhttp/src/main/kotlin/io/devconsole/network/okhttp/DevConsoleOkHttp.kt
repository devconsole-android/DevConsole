/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.network.okhttp

import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockEngineRegistry
import io.devconsole.mocks.okhttp.DevConsoleMockInterceptor
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
 * **Mock rules come with it.** [mockEngine] defaults to whatever engine the running DevConsole
 * published to [MockEngineRegistry], so the dashboard's Mocks screen works off this one call and the
 * separate `.addInterceptor(DevConsoleMockInterceptor(DevConsole.mockEngine()))` line is no longer
 * needed -- keeping it is harmless, since a second interceptor recognises the first and stands down.
 * Nothing changes for traffic until a rule matches: with no rules, or before `DevConsole.initialize`
 * has published an engine, every call passes straight through. Pass an explicit engine to mock
 * against something other than the live one, or `null` to install no mock interceptor at all.
 *
 * The mock interceptor is added *after* the capture interceptor, which is what keeps a served mock
 * visible in the network inspector (tagged `mocked`) rather than invisible to it.
 *
 * The manual three-step form documented on [DevConsoleOkHttpEventListenerFactory] remains available
 * and fully supported for callers who want to manage the interceptor and event listener lifecycles
 * separately.
 */
@JvmOverloads
fun OkHttpClient.Builder.installDevConsole(
    recorder: NetworkTransactionRecorder,
    existingEventListenerFactory: EventListener.Factory? = null,
    mockEngine: MockEngine? = MockEngineRegistry.active(),
): OkHttpClient.Builder {
    val listenerFactory = DevConsoleOkHttpEventListenerFactory(existingEventListenerFactory)
    return eventListenerFactory(listenerFactory)
        .addInterceptor(DevConsoleOkHttpInterceptor(recorder, listenerFactory))
        .apply { mockEngine?.let { addInterceptor(DevConsoleMockInterceptor(it)) } }
}

/**
 * Java-friendly mirror of [installDevConsole]. Kotlin extension functions are not callable as
 * instance methods from Java, so this object gives Java hosts the same one-call installer, mock
 * rules included:
 * ```java
 * OkHttpClient client = DevConsoleOkHttp.install(new OkHttpClient.Builder(), DevConsole.networkRecorder())
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
        mockEngine: MockEngine? = MockEngineRegistry.active(),
    ): OkHttpClient.Builder = builder.installDevConsole(recorder, existingEventListenerFactory, mockEngine)
}
