package io.devconsole.agent

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AgentFatalException(
    message: String,
) : RuntimeException(message)

internal class AgentConnection(
    private val deviceUrl: URI,
    private val token: String?,
    private val name: String,
    private val executor: RequestExecutor,
    private val log: (String) -> Unit,
) {
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
    private val workers =
        Executors.newFixedThreadPool(WORKER_THREADS) { task -> Thread(task, "agent-job").apply { isDaemon = true } }

    @Volatile
    private var stopped = false

    @Volatile
    private var current: WebSocket? = null

    @Volatile
    private var sender: SerialSender? = null

    fun run() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (!stopped) {
            val welcomed = CompletableFuture<Unit>()
            val end = runOnce(welcomed)
            if (stopped) return
            if (welcomed.isDone) backoffMs = INITIAL_BACKOFF_MS
            if (end.fatal != null) throw AgentFatalException(end.fatal)
            log("Disconnected (${end.reason}); reconnecting in ${backoffMs / MS_PER_SECOND}s")
            Thread.sleep(backoffMs)
            backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
        }
    }

    fun stop() {
        stopped = true
        current?.sendClose(WebSocket.NORMAL_CLOSURE, "agent stopped")
        workers.shutdownNow()
    }

    private class End(
        val reason: String,
        val fatal: String? = null,
    )

    private fun runOnce(welcomed: CompletableFuture<Unit>): End {
        val closed = CompletableFuture<End>()
        return try {
            val socket = openSocket(SocketListener(closed, welcomed))
            current = socket
            sender = SerialSender(socket).also { it.send(helloFrame()) }
            closed.get()
        } catch (failure: ExecutionException) {
            failure.cause.toHandshakeEnd()
        } catch (failure: TimeoutException) {
            End("handshake timed out (${failure.javaClass.simpleName})")
        } finally {
            current = null
        }
    }

    private fun openSocket(listener: WebSocket.Listener): WebSocket {
        val builder = client.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT)
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder.buildAsync(deviceUrl.toAgentSocketUri(), listener).get(HANDSHAKE_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private fun helloFrame(): String =
        AgentFrame.encode(
            "type" to AgentFrame.HELLO,
            "name" to name,
            "os" to "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
            "version" to AGENT_VERSION,
        )

    private fun Throwable?.toHandshakeEnd(): End =
        if (this is WebSocketHandshakeException) {
            handshakeRejection(response.statusCode())
        } else {
            End("${this?.javaClass?.simpleName ?: "error"}: ${this?.message.orEmpty().take(MAX_DETAIL)}")
        }

    private fun handshakeRejection(statusCode: Int): End =
        when (statusCode) {
            HTTP_NOT_FOUND ->
                End(
                    "composer disabled",
                    fatal =
                        "The device answered 404 COMPOSER_DISABLED. Enable it in the app: " +
                            "DevConsoleConfig.composerEnabled = true.",
                )
            HTTP_UNAUTHORIZED ->
                End(
                    "unauthorized",
                    fatal =
                        "The device requires a session. Pass --code <session code from the dashboard " +
                            "Overview> or --token <access token>.",
                )
            HTTP_FORBIDDEN ->
                End(
                    "forbidden",
                    fatal =
                        "The device refused the connection (403). Connect through localhost or " +
                            "127.0.0.1 (e.g. adb forward) -- other Host names are not allowed.",
                )
            else -> End("handshake failed with HTTP $statusCode")
        }

    private class SerialSender(
        private val socket: WebSocket,
    ) {
        private var tail: CompletableFuture<*> = CompletableFuture.completedFuture(null)

        @Synchronized
        fun send(text: String) {
            tail = tail.handle { _, _ -> null }.thenCompose { socket.sendText(text, true) }
        }
    }

    private inner class SocketListener(
        private val closed: CompletableFuture<End>,
        private val welcomed: CompletableFuture<Unit>,
    ) : WebSocket.Listener {
        private val partial = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*>? {
            partial.append(data)
            if (last) {
                val text = partial.toString()
                partial.setLength(0)
                runCatching { handle(AgentFrame.decode(text)) }
                    .onFailure { log("Ignoring malformed frame: ${it.message}") }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String,
        ): CompletionStage<*>? {
            closed.complete(
                End(
                    reason = reason.ifBlank { "closed $statusCode" },
                    fatal = FATAL_CLOSE_REASONS[reason]?.let { "The device closed the connection: $it ($reason)." },
                ),
            )
            return null
        }

        override fun onError(
            webSocket: WebSocket,
            error: Throwable,
        ) {
            closed.complete(End("${error.javaClass.simpleName}: ${error.message.orEmpty().take(MAX_DETAIL)}"))
        }

        private fun handle(frame: AgentFrame) {
            when (frame["type"]) {
                AgentFrame.WELCOME -> {
                    welcomed.complete(Unit)
                    log("Connected to $deviceUrl as \"$name\" (agent ${frame["agentId"]}). Waiting for requests...")
                }
                AgentFrame.EXECUTE -> AgentJob.from(frame)?.let(::submit)
            }
        }

        private fun submit(job: AgentJob) {
            val target = "${job.method} ${job.url.substringBefore('?')}"
            log("-> $target")
            workers.execute {
                val outcome = executor.execute(job)
                when (outcome) {
                    is AgentOutcome.Result -> log("<- ${outcome.statusCode} (${outcome.durationMs} ms) $target")
                    is AgentOutcome.Failure -> log("!! ${outcome.message}")
                }
                sender?.send(outcome.toFrame())
            }
        }
    }

    private fun AgentOutcome.toFrame(): String =
        when (this) {
            is AgentOutcome.Result ->
                AgentFrame.encode(
                    buildList {
                        add("type" to AgentFrame.RESULT)
                        add("jobId" to jobId)
                        add("status" to statusCode.toString())
                        add("durationMs" to durationMs.toString())
                        headers.forEach { (headerName, value) -> add("header" to "$headerName:$value") }
                        add("body" to body)
                    },
                )
            is AgentOutcome.Failure ->
                AgentFrame.encode("type" to AgentFrame.ERROR, "jobId" to jobId, "code" to code, "message" to message)
        }

    private fun URI.toAgentSocketUri(): URI {
        val socketScheme = if (scheme.equals("https", ignoreCase = true)) "wss" else "ws"
        return URI("$socketScheme://$authority/api/v1/composer/agent")
    }

    companion object {
        const val AGENT_VERSION = "1.0.0"
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val HANDSHAKE_WAIT_SECONDS = 15L
        private const val WORKER_THREADS = 8
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MS_PER_SECOND = 1_000L
        private const val MAX_DETAIL = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404

        private val FATAL_CLOSE_REASONS =
            mapOf(
                "AUTH_REQUIRED" to "session missing or expired -- restart with a fresh --code",
                "AUTH_REVOKED" to "session was revoked or expired -- restart with a fresh --code",
                "ORIGIN_REJECTED" to "an Origin header was sent",
                "HELLO_REQUIRED" to "the agent handshake was not understood (agent/SDK version mismatch?)",
            )
    }
}
