package io.devconsole.server.ktor

import io.devconsole.composer.ComposerCollection
import io.devconsole.composer.ComposerCollectionStore
import io.devconsole.composer.ComposerRequest
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.CommandAuditEvent
import io.devconsole.server.api.CommandAuditResult
import io.devconsole.server.api.InMemoryCommandAuditLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactingBoundariesTest {
    private val redaction = RedactionEngine(RedactionPolicy.default())

    @Test
    fun `audit read boundary sanitizes legacy raw records`() {
        val delegate =
            InMemoryCommandAuditLog().apply {
                record(
                    CommandAuditEvent(
                        timestampEpochMs = 1L,
                        browserSessionId = "browser",
                        commandType = "composer.import",
                        target = "GET",
                        result = CommandAuditResult.SUCCESS,
                        parameters = mapOf("url" to "https://api.test?access_token=legacy-canary"),
                    ),
                )
            }

        val event = RedactingCommandAuditLog(delegate, redaction).events().single()

        assertFalse(event.toString().contains("legacy-canary"))
        assertTrue(event.parameters.getValue("url").contains("<redacted>"))
    }

    @Test
    fun `collection read boundary sanitizes a custom store returning raw requests`() {
        val delegate =
            object : ComposerCollectionStore {
                override fun save(
                    name: String,
                    request: ComposerRequest,
                ) = ComposerCollection("saved", name, request)

                override fun collections() =
                    listOf(
                        ComposerCollection(
                            id = "legacy",
                            name = "raw",
                            request = ComposerRequest("GET", "https://api.test?access_token=legacy-canary"),
                        ),
                    )

                override fun remove(id: String) = false
            }

        val collection = RedactingComposerCollectionStore(delegate, redaction).collections().single()

        assertFalse(collection.toString().contains("legacy-canary"))
        assertTrue(collection.request.url.contains("<redacted>"))
    }
}
