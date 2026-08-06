/**
 * @author Shakib
 * @since 04/08/26
 */
package io.devconsole.network.okhttp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure arithmetic over [CallPhaseTimestamps], with the clock injected so every duration is exact
 * and the tests never depend on real elapsed time.
 */
class CallPhaseTimestampsTest {
    @Test
    fun `phases never observed stay null rather than becoming zero`() {
        val timings = CallPhaseTimestamps().snapshot()

        assertNull(timings.dnsMs)
        assertNull(timings.connectMs)
        assertNull(timings.tlsMs)
        assertNull(timings.sendMs)
        assertNull(timings.waitMs)
        assertNull(timings.receiveMs)
    }

    @Test
    fun `dns phase duration is dnsEnd minus dnsStart`() {
        val clock = FakeClock()
        val timestamps = CallPhaseTimestamps(clock)

        clock.at(1) { timestamps.record(CallPhaseMark.DNS_START) }
        clock.at(6) { timestamps.record(CallPhaseMark.DNS_END) }

        assertEquals(5L, timestamps.snapshot().dnsMs)
    }

    @Test
    fun `plaintext connect has no TLS phase and connectMs is the full raw span`() {
        val clock = FakeClock()
        val timestamps = CallPhaseTimestamps(clock)

        clock.at(0) { timestamps.record(CallPhaseMark.CONNECT_START) }
        clock.at(4) { timestamps.record(CallPhaseMark.CONNECT_END) }

        val timings = timestamps.snapshot()
        assertNull(timings.tlsMs)
        assertEquals(4L, timings.connectMs)
    }

    @Test
    fun `connect phase excludes the nested TLS handshake it contains`() {
        val clock = FakeClock()
        val timestamps = CallPhaseTimestamps(clock)

        // OkHttp nests the TLS handshake inside connectStart..connectEnd for HTTPS: TCP takes 2ms,
        // then TLS takes 8ms, and connectEnd fires right as the handshake finishes.
        clock.at(0) { timestamps.record(CallPhaseMark.CONNECT_START) }
        clock.at(2) { timestamps.record(CallPhaseMark.SECURE_CONNECT_START) }
        clock.at(10) { timestamps.record(CallPhaseMark.SECURE_CONNECT_END) }
        clock.at(10) { timestamps.record(CallPhaseMark.CONNECT_END) }

        val timings = timestamps.snapshot()
        assertEquals(8L, timings.tlsMs)
        assertEquals(2L, timings.connectMs)
    }

    @Test
    fun `send and wait fall back to requestHeadersEnd when there is no request body`() {
        val clock = FakeClock()
        val timestamps = CallPhaseTimestamps(clock)

        clock.at(0) { timestamps.record(CallPhaseMark.REQUEST_HEADERS_START) }
        clock.at(3) { timestamps.record(CallPhaseMark.REQUEST_HEADERS_END) } // GET request: no request body mark
        clock.at(9) { timestamps.record(CallPhaseMark.RESPONSE_HEADERS_START) }

        val timings = timestamps.snapshot()
        assertEquals(3L, timings.sendMs)
        assertEquals(6L, timings.waitMs)
    }

    @Test
    fun `send extends to requestBodyEnd and wait measures TTFB from there when a body is sent`() {
        val clock = FakeClock()
        val timestamps = CallPhaseTimestamps(clock)

        clock.at(0) { timestamps.record(CallPhaseMark.REQUEST_HEADERS_START) }
        clock.at(1) { timestamps.record(CallPhaseMark.REQUEST_HEADERS_END) }
        clock.at(4) { timestamps.record(CallPhaseMark.REQUEST_BODY_END) }
        clock.at(12) { timestamps.record(CallPhaseMark.RESPONSE_HEADERS_START) }

        val timings = timestamps.snapshot()
        assertEquals(4L, timings.sendMs) // headers + body write, not just headers
        assertEquals(8L, timings.waitMs) // TTFB measured from body end, not headers end
    }

    @Test
    fun `receive phase duration is responseBodyEnd minus responseBodyStart`() {
        val clock = FakeClock()
        val timestamps = CallPhaseTimestamps(clock)

        clock.at(0) { timestamps.record(CallPhaseMark.RESPONSE_BODY_START) }
        clock.at(7) { timestamps.record(CallPhaseMark.RESPONSE_BODY_END) }

        assertEquals(7L, timestamps.snapshot().receiveMs)
    }

    @Test
    fun `a phase with only a start observed stays null rather than reporting a partial duration`() {
        val clock = FakeClock()
        val timestamps = CallPhaseTimestamps(clock)

        clock.at(0) { timestamps.record(CallPhaseMark.CONNECT_START) }
        // connectEnd never observed -- e.g. the call failed mid-connect.

        assertNull(timestamps.snapshot().connectMs)
    }

    /** Millisecond-granularity fake nanoTime clock so tests read as exact millisecond deltas. */
    private class FakeClock : () -> Long {
        private var nowNanos = 0L

        override fun invoke(): Long = nowNanos

        fun at(
            millis: Long,
            action: () -> Unit,
        ) {
            nowNanos = millis * 1_000_000L
            action()
        }
    }
}
