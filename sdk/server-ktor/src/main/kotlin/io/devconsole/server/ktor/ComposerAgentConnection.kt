package io.devconsole.server.ktor

import io.devconsole.composer.ComposerDestinationRejectedException
import io.devconsole.composer.ComposerResponse
import io.devconsole.composer.ComposerTransport
import io.devconsole.composer.ResolvedComposerRequest
import io.devconsole.composer.wireBody
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.util.encodeBase64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class ComposerAgentInfo(
    val id: String,
    val name: String,
    val os: String,
    val version: String,
    val address: String,
    val connectedAtEpochMs: Long,
) {
    val executorLabel: String get() = "${ComposerAgentRegistry.AGENT_EXECUTOR_PREFIX}$name"
}

internal class ComposerAgentUnavailableException(
    message: String,
) : RuntimeException(message)

internal class ComposerAgentTimeoutException : RuntimeException("Composer agent did not answer in time")

internal class ComposerAgentFailedException(
    message: String,
) : RuntimeException(message)

private sealed interface AgentReply {
    class Success(
        val response: ComposerResponse,
    ) : AgentReply

    class Failure(
        val code: String,
        val message: String,
    ) : AgentReply
}

internal class ComposerAgentConnection(
    val info: ComposerAgentInfo,
    private val sendFrame: suspend (String) -> Unit,
) {
    private val pendingReplies = ConcurrentHashMap<String, CompletableDeferred<AgentReply>>()
    private val closed = AtomicBoolean(false)

    suspend fun execute(request: ResolvedComposerRequest): ComposerResponse {
        ensureAvailable()
        val jobId = UUID.randomUUID().toString()
        val reply = CompletableDeferred<AgentReply>()
        pendingReplies[jobId] = reply
        try {
            ensureAvailable()
            sendFrame(request.toExecuteFrame(jobId))
            val awaited =
                withTimeoutOrNull(request.timeoutMs + REPLY_GRACE_MS) { reply.await() }
                    ?: throw ComposerAgentTimeoutException()
            return awaited.unwrap(request)
        } finally {
            pendingReplies.remove(jobId)
        }
    }

    fun handleReply(frame: Parameters) {
        val reply = pendingReplies[frame["jobId"].orEmpty()] ?: return
        when (frame["type"]) {
            ComposerAgentFrames.RESULT -> reply.complete(AgentReply.Success(frame.toComposerResponse()))
            ComposerAgentFrames.ERROR ->
                reply.complete(
                    AgentReply.Failure(
                        code = frame["code"].orEmpty(),
                        message = frame["message"].orEmpty().take(MAX_ERROR_MESSAGE_CHARS),
                    ),
                )
        }
    }

    fun failPendingReplies() {
        closed.set(true)
        pendingReplies.values.forEach { it.complete(AgentReply.Failure(DISCONNECTED, DISCONNECTED_MESSAGE)) }
    }

    private fun ensureAvailable() {
        if (closed.get()) throw ComposerAgentUnavailableException(DISCONNECTED_MESSAGE)
        if (pendingReplies.size >= MAX_PENDING_JOBS) throw ComposerAgentUnavailableException("Composer agent is busy")
    }

    private fun AgentReply.unwrap(request: ResolvedComposerRequest): ComposerResponse =
        when (this) {
            is AgentReply.Success -> response
            is AgentReply.Failure ->
                throw when (code) {
                    ComposerAgentFrames.ERROR_HOST_REJECTED -> ComposerDestinationRejectedException(request.url)
                    DISCONNECTED -> ComposerAgentUnavailableException(message)
                    else -> ComposerAgentFailedException(message)
                }
        }

    private fun ResolvedComposerRequest.toExecuteFrame(jobId: String): String {
        val body = wireBody()
        return buildList {
            add("type" to ComposerAgentFrames.EXECUTE)
            add("jobId" to jobId)
            add("method" to method)
            add("url" to url)
            add("timeoutMs" to timeoutMs.toString())
            headers.forEach { (name, value) -> add("header" to "$name:$value") }
            if (body != null) {
                add("bodyBase64" to body.bytes.encodeBase64())
                add("contentType" to body.contentType)
            }
        }.formUrlEncode()
    }

    private fun Parameters.toComposerResponse(): ComposerResponse =
        ComposerResponse(
            statusCode = this["status"]?.toIntOrNull() ?: 0,
            headers =
                getAll("header")
                    .orEmpty()
                    .take(MAX_RESPONSE_HEADERS)
                    .mapNotNull { line ->
                        line.indexOf(':').takeIf { it > 0 }?.let { line.take(it) to line.drop(it + 1) }
                    }.toMap(),
            body = this["body"],
            durationMs = this["durationMs"]?.toLongOrNull(),
        )

    private companion object {
        const val DISCONNECTED = "DISCONNECTED"
        const val DISCONNECTED_MESSAGE = "Composer agent disconnected"
        const val MAX_PENDING_JOBS = 16
        const val REPLY_GRACE_MS = 5_000L
        const val MAX_ERROR_MESSAGE_CHARS = 512
        const val MAX_RESPONSE_HEADERS = 200
    }
}

internal class RemoteAgentComposerTransport(
    private val agent: ComposerAgentConnection,
) : ComposerTransport {
    override fun execute(request: ResolvedComposerRequest): ComposerResponse = runBlocking { agent.execute(request) }
}
