package io.devconsole

import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.TimelineAppender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class SessionMarkerRecorderTest {
    @Test
    fun `records every required system marker with bounded non-sensitive tags`() {
        val events = mutableListOf<StoredEvent>()
        val bridge =
            CaptureTimelineBridge(
                sessionId = "session",
                appender = {
                    object : TimelineAppender {
                        override fun append(event: StoredEvent) {
                            events += event
                        }
                    }
                },
                streamHub = { null },
                nextSequence = AtomicLong()::incrementAndGet,
            )
        val recorder = SessionMarkerRecorder(bridge)

        recorder.sdkStarted("LOOPBACK")
        recorder.appForeground()
        recorder.appBackground()
        recorder.connectivityChanged(available = true, transports = setOf("WIFI"), validated = true)
        recorder.configurationChanged(orientation = "LANDSCAPE", uiMode = 32, densityDpi = 420, fontScale = 1.2f)
        recorder.dataDropped("network", 3)
        recorder.storageRecovered()
        recorder.sdkStopped("USER_REQUESTED")

        assertEquals(
            listOf(
                "system.sdk.started",
                "system.app.foreground",
                "system.app.background",
                "system.connectivity.changed",
                "system.configuration.changed",
                "system.data.dropped",
                "system.storage.recovered",
                "system.sdk.stopped",
            ),
            events.map(StoredEvent::type),
        )
        assertFalse(events.joinToString().contains("Authorization"))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), events.map(StoredEvent::sequence))
    }
}
