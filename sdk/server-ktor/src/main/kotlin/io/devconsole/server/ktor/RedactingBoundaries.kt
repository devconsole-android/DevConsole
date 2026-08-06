package io.devconsole.server.ktor

import io.devconsole.composer.ComposerCollection
import io.devconsole.composer.ComposerCollectionStore
import io.devconsole.composer.ComposerRequest
import io.devconsole.security.RedactionEngine
import io.devconsole.server.api.CommandAuditEvent
import io.devconsole.server.api.CommandAuditLog

/**
 * Applies redaction both when an event is stored and when a delegate returns it. The read boundary
 * protects lower-privilege sessions even when a custom or legacy store already contains raw data.
 */
internal class RedactingCommandAuditLog(
    private val delegate: CommandAuditLog,
    private val redaction: RedactionEngine,
) : CommandAuditLog {
    override fun record(event: CommandAuditEvent) {
        delegate.record(event.redacted())
    }

    override fun events(): List<CommandAuditEvent> = delegate.events().map { it.redacted() }

    private fun CommandAuditEvent.redacted(): CommandAuditEvent =
        copy(
            browserSessionId = redaction.redactText(browserSessionId),
            commandType = redaction.redactText(commandType),
            target = redaction.redactText(target),
            parameters = redaction.redactFields(parameters),
        )
}

/**
 * Uses the same two boundaries for composer collections. A host-supplied store cannot accidentally
 * reintroduce raw URL/query/header/body values into READ_ONLY responses.
 */
internal class RedactingComposerCollectionStore(
    private val delegate: ComposerCollectionStore,
    private val redaction: RedactionEngine,
) : ComposerCollectionStore {
    override fun save(
        name: String,
        request: ComposerRequest,
    ): ComposerCollection =
        delegate
            .save(redaction.redactText(name), request.redacted(redaction))
            .redacted()

    override fun collections(): List<ComposerCollection> = delegate.collections().map { it.redacted() }

    override fun remove(id: String): Boolean = delegate.remove(id)

    private fun ComposerCollection.redacted(): ComposerCollection =
        copy(
            name = redaction.redactText(name),
            request = request.redacted(redaction),
        )
}
