package io.devconsole.composer

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import java.util.UUID

/**
 * Deliberately small cURL importer. It tokenizes text only and never invokes a shell, expands
 * environment variables, follows command substitutions, or reads files from the host device.
 */
object ComposerCurlImporter {
    fun import(command: String): Result<ComposerRequest> =
        runCatching {
            val tokens = tokenize(command)
            require(tokens.firstOrNull() == "curl") { "Command must begin with curl" }
            var method: String? = null
            var url: String? = null
            var body: String? = null
            var followRedirects = false
            var timeoutMs = 15_000
            val headers = linkedMapOf<String, String>()
            var index = 1
            while (index < tokens.size) {
                when (val token = tokens[index]) {
                    "-X", "--request" -> method = tokens.valueAfter(index++).uppercase()
                    "-H", "--header" -> {
                        val header = tokens.valueAfter(index++)
                        val separator = header.indexOf(':')
                        require(separator > 0) { "Headers must use Name: Value" }
                        headers[header.substring(0, separator).trim()] = header.substring(separator + 1).trim()
                    }
                    "-d", "--data", "--data-raw", "--data-binary" -> {
                        body = tokens.valueAfter(index++)
                        require(!body.startsWith('@')) { "File-backed cURL bodies are not supported" }
                    }
                    "--url" -> url = tokens.valueAfter(index++)
                    "-L", "--location" -> followRedirects = true
                    "--max-time" -> timeoutMs = (tokens.valueAfter(index++).toDouble() * 1_000).toInt().coerceAtLeast(1)
                    else -> {
                        require(!token.startsWith('-')) { "Unsupported cURL option: $token" }
                        require(url == null) { "Only one URL is supported" }
                        url = token
                    }
                }
                index += 1
            }
            val target = requireNotNull(url) { "cURL command has no URL" }
            require(target.startsWith("http://") || target.startsWith("https://")) { "Only HTTP(S) URLs are supported" }
            val resolvedMethod = method ?: if (body == null) "GET" else "POST"
            ComposerRequest(
                method = resolvedMethod,
                url = target,
                headers = headers,
                body = body,
                bodyType =
                    when {
                        body == null -> ComposerBodyType.NONE
                        headers.entries.any {
                            it.key.equals(
                                "Content-Type",
                                true,
                            ) &&
                                it.value.contains("json", true)
                        } -> ComposerBodyType.JSON
                        else -> ComposerBodyType.TEXT
                    },
                timeoutMs = timeoutMs,
                followRedirects = followRedirects,
            )
        }

    private fun List<String>.valueAfter(index: Int): String =
        getOrNull(index + 1) ?: error("Missing value for ${this[index]}")

    private fun tokenize(command: String): List<String> {
        val output = mutableListOf<String>()
        val value = StringBuilder()
        var quote: Char? = null
        var escaped = false

        fun flush() {
            if (value.isNotEmpty()) {
                output += value.toString()
                value.clear()
            }
        }
        command.forEach { char ->
            when {
                escaped -> {
                    value.append(char)
                    escaped = false
                }
                char == '\\' && quote != '\'' -> escaped = true
                quote != null && char == quote -> quote = null
                quote == null && (char == '\'' || char == '"') -> quote = char
                quote == null && char.isWhitespace() -> flush()
                else -> value.append(char)
            }
        }
        require(!escaped && quote == null) { "Unterminated cURL quoting" }
        flush()
        return output
    }
}

data class ComposerCollection(
    val id: String,
    val name: String,
    val request: ComposerRequest,
)

interface ComposerCollectionStore {
    fun save(
        name: String,
        request: ComposerRequest,
    ): ComposerCollection

    fun collections(): List<ComposerCollection>

    fun remove(id: String): Boolean
}

/** Ephemeral store; secret variable values are intentionally stripped before retaining a collection. */
class InMemoryComposerCollectionStore(
    private val redaction: RedactionEngine = RedactionEngine(RedactionPolicy.default()),
) : ComposerCollectionStore {
    private val entries = linkedMapOf<String, ComposerCollection>()

    override fun save(
        name: String,
        request: ComposerRequest,
    ): ComposerCollection {
        require(name.isNotBlank()) { "Collection name must not be blank" }
        val redactedRequest = request.redacted(redaction)
        val safeRequest =
            redactedRequest
                .copy(
                    variables =
                        redactedRequest.variables.map { variable ->
                            if (variable.secret) variable.copy(value = "") else variable
                        },
                ).detached()
        val stored = ComposerCollection(UUID.randomUUID().toString(), redaction.redactText(name.trim()), safeRequest)
        entries[stored.id] = stored
        return stored.detached()
    }

    override fun collections(): List<ComposerCollection> = entries.values.map { it.detached() }

    override fun remove(id: String): Boolean = entries.remove(id) != null

    private fun ComposerCollection.detached(): ComposerCollection = copy(request = request.detached())

    private fun ComposerRequest.detached(): ComposerRequest =
        copy(binaryBody = binaryBody?.copy(bytes = binaryBody.bytes.copyOf()))
}
