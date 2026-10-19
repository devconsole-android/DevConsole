package io.devconsole.agent

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Base64

internal class AgentBody(
    val bytes: ByteArray,
    val contentType: String?,
)

internal class AgentJob(
    val jobId: String,
    val method: String,
    val url: String,
    val timeoutMs: Int,
    val headers: List<Pair<String, String>>,
    val body: AgentBody?,
) {
    companion object {
        fun from(frame: AgentFrame): AgentJob? {
            val jobId = frame["jobId"]?.takeIf(String::isNotBlank)
            val url = frame["url"]?.takeIf(String::isNotBlank)
            return if (jobId == null || url == null) {
                null
            } else {
                AgentJob(
                    jobId = jobId,
                    method = frame["method"].orEmpty().uppercase().ifBlank { "GET" },
                    url = url,
                    timeoutMs = frame["timeoutMs"]?.toIntOrNull()?.coerceIn(1, MAX_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS,
                    headers =
                        frame.getAll("header").mapNotNull { line ->
                            line
                                .indexOf(
                                    ':',
                                ).takeIf { it > 0 }
                                ?.let { line.take(it).trim() to line.drop(it + 1).trim() }
                        },
                    body =
                        frame["bodyBase64"]
                            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                            ?.let { AgentBody(it, frame["contentType"]) },
                )
            }
        }

        private const val DEFAULT_TIMEOUT_MS = 15_000
        private const val MAX_TIMEOUT_MS = 120_000
    }
}

internal sealed interface AgentOutcome {
    val jobId: String

    class Result(
        override val jobId: String,
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: String,
        val durationMs: Long,
    ) : AgentOutcome

    class Failure(
        override val jobId: String,
        val code: String,
        val message: String,
    ) : AgentOutcome
}

internal class RequestExecutor(
    private val policy: HostPolicy = HostPolicy(),
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) {
    init {
        // The JDK client refuses a custom Host header unless this is set before the first client exists
        if (System.getProperty(ALLOW_RESTRICTED) == null) System.setProperty(ALLOW_RESTRICTED, "host")
    }

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build()

    fun execute(job: AgentJob): AgentOutcome {
        policy.rejectionFor(job.url)?.let {
            return AgentOutcome.Failure(job.jobId, AgentFrame.ERROR_HOST_REJECTED, it)
        }
        val startedAt = System.nanoTime()
        return try {
            val response = client.send(job.toHttpRequest(), HttpResponse.BodyHandlers.ofInputStream())
            val body = response.body().use { it.readBounded(maxResponseBytes) }.decodeToString()
            AgentOutcome.Result(
                jobId = job.jobId,
                statusCode = response.statusCode(),
                headers =
                    response
                        .headers()
                        .map()
                        .filterKeys { !it.startsWith(":") }
                        .mapValues { (_, values) -> values.joinToString(",") },
                body = body,
                durationMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI,
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            AgentOutcome.Failure(job.jobId, AgentFrame.ERROR_FAILED, "Interrupted")
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            AgentOutcome.Failure(job.jobId, AgentFrame.ERROR_FAILED, failure.describe(job))
        }
    }

    private fun AgentJob.toHttpRequest(): HttpRequest {
        val builder =
            HttpRequest
                .newBuilder(URI(url))
                .timeout(Duration.ofMillis(timeoutMs.toLong()))
                .method(
                    method,
                    body?.let { HttpRequest.BodyPublishers.ofByteArray(it.bytes) }
                        ?: HttpRequest.BodyPublishers.noBody(),
                )
        headers
            .filterNot { (name, _) -> name.lowercase() in JDK_MANAGED_HEADERS }
            .forEach { (name, value) -> builder.header(name, value) }
        val defaultContentType = body?.contentType
        if (defaultContentType != null && headers.none { it.first.equals("Content-Type", ignoreCase = true) }) {
            builder.header("Content-Type", defaultContentType)
        }
        return builder.build()
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (output.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun Exception.describe(job: AgentJob): String {
        val host = runCatching { URI(job.url).host }.getOrNull().orEmpty()
        return when (this) {
            is HttpConnectTimeoutException -> "Connecting to $host timed out after ${job.timeoutMs} ms"
            is HttpTimeoutException -> "Request timed out after ${job.timeoutMs} ms"
            is java.net.ConnectException -> "Could not connect to $host (connection refused or host unreachable)"
            is java.nio.channels.UnresolvedAddressException,
            is java.net.UnknownHostException,
            -> "Unable to resolve host $host"
            is javax.net.ssl.SSLException -> "TLS error talking to $host: ${message.orEmpty().take(MAX_DETAIL)}"
            else -> "${javaClass.simpleName}: ${message.orEmpty().take(MAX_DETAIL)}"
        }
    }

    private companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 1_024 * 1_024
        const val BUFFER_BYTES = 8 * 1_024
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_DETAIL = 200
        const val ALLOW_RESTRICTED = "jdk.httpclient.allowRestrictedHeaders"

        val JDK_MANAGED_HEADERS = setOf("connection", "content-length", "expect", "upgrade")
    }
}
