package io.devconsole.server.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CommandAuditLogTest {
    @Test
    fun `audit log stores immutable redacted command records`() {
        val log = InMemoryCommandAuditLog()
        val parameters = mutableMapOf("before" to "<redacted>", "after" to "true")

        log.record(
            CommandAuditEvent(10, "browser-1", "flag.override", "new_ui", CommandAuditResult.SUCCESS, parameters),
        )
        parameters["after"] = "false"

        val record = log.events().single()
        assertEquals("true", record.parameters.getValue("after"))
        assertFalse(record.parameters.values.any { it == "secret" })
    }

    @Test
    fun `audit log respects max capacity and evicts oldest items`() {
        val log = InMemoryCommandAuditLog(maxCapacity = 3)
        repeat(5) { index ->
            log.record(CommandAuditEvent(10L + index, "browser-1", "cmd-$index", "target", CommandAuditResult.SUCCESS))
        }

        val events = log.events()
        assertEquals(3, events.size)
        assertEquals("cmd-2", events[0].commandType)
        assertEquals("cmd-4", events[2].commandType)
    }
}
