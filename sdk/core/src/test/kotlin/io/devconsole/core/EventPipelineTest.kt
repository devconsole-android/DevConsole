package io.devconsole.core

import io.devconsole.api.EventSeverity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class EventPipelineTest {
    @Test
    fun `FR-TIME-001 publishes session and monotonic sequence fields`() =
        runTest {
            val sessionId = UUID.randomUUID()
            val pipeline = EventPipeline(sessionId = sessionId, capacity = 2)

            val event = pipeline.publish(EventDraft.system("ready"))

            assertEquals(sessionId, event.sessionId)
            assertEquals(1L, event.sequence)
            assertTrue(event.monotonicNanos > 0)
        }

    @Test
    fun `FR-TIME-003 full buffer drops low priority instead of blocking`() =
        runTest {
            val pipeline = EventPipeline(sessionId = UUID.randomUUID(), capacity = 1)

            pipeline.publish(EventDraft.system("first", severity = EventSeverity.DEBUG))
            pipeline.publish(EventDraft.system("second", severity = EventSeverity.DEBUG))

            assertEquals(1L, pipeline.health.value.droppedEventCount)
            assertEquals(listOf("second"), pipeline.snapshot().map { it.summary })
        }

    @Test
    fun `redacts event content before it reaches the observable bus`() =
        runTest {
            val pipeline = EventPipeline(UUID.randomUUID(), capacity = 2)

            pipeline.publish(
                EventDraft(
                    pluginId = "network",
                    type = "network.request",
                    severity = EventSeverity.INFO,
                    summary = "Authorization: Bearer raw-token",
                    tags = mapOf("Authorization" to "Bearer another-token", "route" to "/orders"),
                ),
            )

            val event = pipeline.snapshot().single()
            assertFalse(event.summary.contains("raw-token"))
            assertEquals("<redacted>", event.tags.getValue("Authorization"))
            assertEquals("/orders", event.tags.getValue("route"))
        }

    @Test
    fun `overflow preserves higher severity events and reports the dropped event`() {
        val drops = mutableListOf<EventDropNotice>()
        val pipeline =
            EventPipeline(UUID.randomUUID(), capacity = 2)
                .withDropSink(drops::add)

        pipeline.publish(EventDraft("network", "request", EventSeverity.INFO, "info"))
        pipeline.publish(EventDraft("crash", "failure", EventSeverity.ERROR, "error"))
        pipeline.publish(EventDraft("state", "change", EventSeverity.WARN, "warn"))

        assertEquals(listOf("error", "warn"), pipeline.snapshot().map { it.summary })
        assertEquals("network", drops.single().droppedPluginId)
        assertEquals(EventSeverity.INFO, drops.single().droppedSeverity)
    }

    @Test
    fun `lower severity incoming event is dropped before buffered errors`() {
        val pipeline = EventPipeline(UUID.randomUUID(), capacity = 2)
        pipeline.publish(EventDraft("crash", "first", EventSeverity.ERROR, "error one"))
        pipeline.publish(EventDraft("crash", "second", EventSeverity.ERROR, "error two"))

        pipeline.publish(EventDraft("network", "trace", EventSeverity.DEBUG, "debug"))

        assertEquals(listOf("error one", "error two"), pipeline.snapshot().map { it.summary })
        assertEquals(1L, pipeline.health.value.droppedEventCount)
    }

    @Test
    fun `plugin overflow policy can bound one producer and drop its latest event`() {
        val pipeline =
            EventPipeline(UUID.randomUUID(), capacity = 4)
                .withPluginOverflowPolicies(
                    mapOf(
                        "network" to
                            PluginOverflowPolicy(
                                maxBufferedEvents = 1,
                                strategy = EventOverflowStrategy.DROP_LATEST,
                            ),
                    ),
                )
        pipeline.publish(EventDraft("network", "request", EventSeverity.INFO, "first network"))
        pipeline.publish(EventDraft("system", "marker", EventSeverity.INFO, "system"))

        pipeline.publish(EventDraft("network", "request", EventSeverity.ERROR, "second network"))

        assertEquals(listOf("first network", "system"), pipeline.snapshot().map { it.summary })
        assertEquals(1L, pipeline.health.value.droppedEventCount)
    }
}
