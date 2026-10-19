package io.devconsole.server.ktor

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class ComposerAgentRegistry {
    private val connections = ConcurrentHashMap<String, ComposerAgentConnection>()

    fun register(
        name: String,
        os: String,
        version: String,
        address: String,
        sendFrame: suspend (String) -> Unit,
    ): ComposerAgentConnection? {
        if (connections.size >= MAX_AGENTS) return null
        val info =
            ComposerAgentInfo(
                id = UUID.randomUUID().toString().take(ID_LENGTH),
                name = name.cleanLabel().ifBlank { "Agent" },
                os = os.cleanLabel().ifBlank { "unknown" },
                version = version.cleanLabel().ifBlank { "unknown" },
                address = address.cleanLabel(),
                connectedAtEpochMs = System.currentTimeMillis(),
            )
        return ComposerAgentConnection(info, sendFrame).also { connections[info.id] = it }
    }

    fun unregister(connection: ComposerAgentConnection) {
        connections.remove(connection.info.id, connection)
        connection.failPendingReplies()
    }

    fun findByExecutor(executor: String): ComposerAgentConnection? =
        executor
            .takeIf { it.startsWith(AGENT_EXECUTOR_PREFIX) }
            ?.removePrefix(AGENT_EXECUTOR_PREFIX)
            ?.let(connections::get)

    fun list(): List<ComposerAgentInfo> = connections.values.map { it.info }.sortedBy { it.connectedAtEpochMs }

    private fun String.cleanLabel(): String = filterNot { it.isISOControl() }.trim().take(MAX_LABEL_LENGTH)

    companion object {
        const val AGENT_EXECUTOR_PREFIX = "agent:"
        private const val MAX_AGENTS = 8
        private const val ID_LENGTH = 12
        private const val MAX_LABEL_LENGTH = 64
    }
}
