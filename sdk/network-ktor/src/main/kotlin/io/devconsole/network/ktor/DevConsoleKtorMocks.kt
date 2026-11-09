/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.network.ktor

import io.devconsole.mocks.MockDecision
import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockRequest
import io.devconsole.network.NetworkCaptureContext
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.Sender
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpSendPipeline
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import io.ktor.util.pipeline.PipelinePhase

internal const val MOCKED_CAPTURE_TAG = "mocked"
internal const val MOCK_RULE_ID_CAPTURE_TAG = "mockRuleId"
internal const val MAPPED_REMOTE_CAPTURE_TAG = "mapRemote"
internal const val MOCKED_CAPTURE_TAG_VALUE = "true"

private const val HEADER_VALUE_SEPARATOR = ", "

/**
 * Applies DevConsole [MockEngine] rules to Ktor client calls, mirroring
 * `DevConsoleMockInterceptor` on OkHttp.
 *
 * Install it next to [DevConsoleKtorClientPlugin] and hand it the host's engine:
 *
 * ```
 * val client = HttpClient {
 *     install(DevConsoleKtorClientPlugin) { recorder = DevConsole.networkRecorder() }
 *     install(DevConsoleKtorMocks) { mockEngine = DevConsole.mockEngine() }
 * }
 * ```
 *
 * Interception happens in the `HttpSend` plugin rather than the engine or the receive pipeline.
 * `HttpSend` owns request execution, so returning a synthetic call from here short-circuits the
 * network without bypassing the receive pipeline that [DevConsoleKtorClientPlugin] hooks -- a
 * mocked response is therefore captured as an ordinary transaction, tagged `mocked=true` and
 * `mockRuleId=<id>`, exactly like the OkHttp adapter.
 */
public val DevConsoleKtorMocks =
    createClientPlugin("DevConsoleKtorMocks", ::DevConsoleKtorMocksConfig) {
        val engine =
            requireNotNull(pluginConfig.mockEngine) {
                "DevConsoleKtorMocks requires mockEngine = DevConsole.mockEngine()"
            }
        val httpClient = client
        httpClient.plugin(HttpSend).intercept { request ->
            sendOrMock(httpClient, engine, request)
        }
        // Status overrides and body replacements rewrite the engine's call before `Receive`, so
        // DevConsoleKtorClientPlugin captures the response the app sees.
        val rewritePhase = PipelinePhase("DevConsoleMockRewrite")
        httpClient.sendPipeline.insertPhaseAfter(HttpSendPipeline.Engine, rewritePhase)
        httpClient.sendPipeline.intercept(rewritePhase) { call ->
            val rewrite = context.attributes.getOrNull(mockResponseRewriteKey) ?: return@intercept
            if (call is HttpClientCall) proceedWith(call.rewriteMockedResponse(httpClient, context, rewrite))
        }
    }

/**
 * Configuration for [DevConsoleKtorMocks].
 */
public class DevConsoleKtorMocksConfig {
    /**
     * The DevConsole mock engine consulted for every outgoing call. Required: installing the plugin
     * without it fails immediately. When the engine is globally disabled, every call proceeds
     * untouched.
     */
    public var mockEngine: MockEngine? = null
}

/**
 * Decides once per call and either returns a synthetic response or hands the request to the rest
 * of the send pipeline. A matching rule always tags the request, including when its action ends in
 * a passthrough, so capture reports the rule that fired.
 */
private suspend fun Sender.sendOrMock(
    client: HttpClient,
    engine: MockEngine,
    request: HttpRequestBuilder,
): HttpClientCall {
    val decision = runCatching { engine.decide(request.toMockRequest(engine)) }.getOrNull()
    val matched = decision as? MockDecision.Matched ?: return execute(request)
    request.markMocked(matched.rule.id)
    return applyMockAction(client, matched.action, request)
}

private fun HttpRequestBuilder.toMockRequest(engine: MockEngine): MockRequest =
    MockRequest(
        method = method.value,
        scheme = url.protocol.name,
        host = url.host,
        path = url.encodedPath,
        query = url.parameters.entries().associate { (name, values) -> name to values.firstOrNull().orEmpty() },
        headers =
            headers.build().entries().associate { (name, values) ->
                name to values.joinToString(HEADER_VALUE_SEPARATOR)
            },
        body = bodyPreviewForMatching(engine.requestBodyLimitBytes()),
    )

/**
 * A readable copy of the request body for body predicates, or `null` when none is needed or the
 * body cannot be read safely. Streaming content ([OutgoingContent.ReadChannelContent] and
 * [OutgoingContent.WriteChannelContent]) is never touched, and a body of unknown or oversized
 * length is skipped rather than buffered -- the same rules the OkHttp adapter applies to one-shot
 * and duplex request bodies. At `HttpSend` time a `setBody("...")` value is still raw, so `String`
 * and `ByteArray` bodies are accepted too.
 */
private fun HttpRequestBuilder.bodyPreviewForMatching(limitBytes: Int?): String? {
    if (limitBytes == null) return null
    return when (val content = body) {
        is String -> content.takeIf { it.utf8Size() <= limitBytes }
        is ByteArray -> content.takeIf { it.size <= limitBytes }?.decodeToString()
        is OutgoingContent.ByteArrayContent -> content.textWithin(limitBytes)
        else -> null
    }
}

private fun OutgoingContent.ByteArrayContent.textWithin(limitBytes: Int): String? {
    val declaredLength = contentLength
    val bytes =
        if (declaredLength != null && declaredLength in 0L..limitBytes.toLong()) {
            runCatching { bytes() }.getOrNull()
        } else {
            null
        }
    return bytes?.takeIf { it.size <= limitBytes }?.decodeToString()
}

private fun String.utf8Size(): Int = encodeToByteArray().size

private fun HttpRequestBuilder.markMocked(ruleId: String) {
    updateMockTags(
        mapOf(
            MOCKED_CAPTURE_TAG to MOCKED_CAPTURE_TAG_VALUE,
            MOCK_RULE_ID_CAPTURE_TAG to ruleId,
        ),
    )
}

internal fun HttpRequestBuilder.markMappedRemote(url: String) {
    updateMockTags(mapOf(MAPPED_REMOTE_CAPTURE_TAG to url))
}

private fun HttpRequestBuilder.updateMockTags(tags: Map<String, String>) {
    val existing = attributes.getOrNull(devConsoleKtorCaptureContextKey)?.tags.orEmpty()
    attributes.put(devConsoleKtorCaptureContextKey, NetworkCaptureContext(existing + tags))
}

internal fun HttpRequestBuilder.toMockRequestData(): HttpRequestData = build()

internal fun Map<String, String>.toKtorHeaders(): Headers =
    HeadersBuilder()
        .apply {
            forEach { (name, value) -> runCatching { append(name, value) } }
        }.build()
