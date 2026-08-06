package io.devconsole.server.api

enum class CommandAuditResult { SUCCESS, REJECTED, FAILED }

data class CommandAuditEvent(
    val timestampEpochMs: Long,
    val browserSessionId: String,
    val commandType: String,
    val target: String,
    val result: CommandAuditResult,
    val parameters: Map<String, String> = emptyMap(),
) {
    init {
        require(commandType.isNotBlank()) { "Command type must not be blank" }
        require(target.isNotBlank()) { "Command target must not be blank" }
    }
}

interface CommandAuditLog {
    fun record(event: CommandAuditEvent)

    fun events(): List<CommandAuditEvent>
}

class InMemoryCommandAuditLog(
    private val maxCapacity: Int = DEFAULT_MAX_CAPACITY,
) : CommandAuditLog {
    private val records = ArrayDeque<CommandAuditEvent>()

    @Synchronized
    override fun record(event: CommandAuditEvent) {
        if (records.size >= maxCapacity) {
            records.removeFirst()
        }
        records.addLast(event.copy(parameters = event.parameters.toMap()))
    }

    @Synchronized
    override fun events(): List<CommandAuditEvent> = records.map { it.copy(parameters = it.parameters.toMap()) }

    companion object {
        const val DEFAULT_MAX_CAPACITY = 500
    }
}
