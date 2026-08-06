/**
 * @author Shakib
 * @since 04/08/26
 */
package io.devconsole.network.okhttp

import io.devconsole.network.NetworkTimingPhases
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges OkHttp's [EventListener] callbacks -- the only place DNS/connect/TLS/wait/download phase
 * boundaries are observable -- into the [NetworkTimingPhases] that [DevConsoleOkHttpInterceptor]
 * attaches to a recorded transaction.
 *
 * Install alongside the interceptor and hand the *same instance* to both, since
 * [DevConsoleOkHttpInterceptor] reads phase state back out of this factory:
 * ```
 * val listenerFactory = DevConsoleOkHttpEventListenerFactory()
 * val client = OkHttpClient.Builder()
 *     .eventListenerFactory(listenerFactory)
 *     .addInterceptor(DevConsoleOkHttpInterceptor(DevConsole.networkRecorder(), listenerFactory))
 *     .build()
 * ```
 *
 * OkHttp only allows one `eventListenerFactory` per client. Pass an existing one as [delegate] to
 * chain it -- every callback still reaches it, in addition to being tracked here.
 *
 * State is keyed by the OkHttp [Call] instance, which is identity-equal per call and never reused,
 * so concurrent calls never cross-talk. Entries are removed once [DevConsoleOkHttpInterceptor] has
 * read them and, as a safety net for a call this factory tracks but the interceptor never sees (for
 * example this factory installed without the interceptor, or a chain that throws before reaching
 * it), on `callEnd`/`callFailed` as well -- so nothing is retained past the call's lifetime.
 */
class DevConsoleOkHttpEventListenerFactory
    @JvmOverloads
    constructor(
        private val delegate: EventListener.Factory? = null,
    ) : EventListener.Factory {
        private val timestampsByCall = ConcurrentHashMap<Call, CallPhaseTimestamps>()

        override fun create(call: Call): EventListener {
            val timestamps = CallPhaseTimestamps()
            timestampsByCall[call] = timestamps
            return PhaseTrackingEventListener(
                timestamps = timestamps,
                delegate = delegate?.create(call),
                onCallFinished = { timestampsByCall.remove(call) },
            )
        }

        /** Snapshot of whatever phases have been observed for [call] so far. Never removes state. */
        internal fun timingsFor(call: Call): NetworkTimingPhases =
            timestampsByCall[call]?.snapshot() ?: NetworkTimingPhases()

        /** Drops any retained state for [call]. Safe to call even when nothing is retained. */
        internal fun forget(call: Call) {
            timestampsByCall.remove(call)
        }

        /** Test-only visibility into leak behaviour: how many in-flight calls this factory is still tracking. */
        internal fun trackedCallCount(): Int = timestampsByCall.size
    }

/** The individual boundary timestamps an [okhttp3.EventListener] observes over a call's lifetime. */
internal enum class CallPhaseMark {
    DNS_START,
    DNS_END,
    CONNECT_START,
    CONNECT_END,
    SECURE_CONNECT_START,
    SECURE_CONNECT_END,
    REQUEST_HEADERS_START,
    REQUEST_HEADERS_END,
    REQUEST_BODY_END,
    RESPONSE_HEADERS_START,
    RESPONSE_BODY_START,
    RESPONSE_BODY_END,
}

/**
 * Mutable, per-call phase timestamps recorded by [PhaseTrackingEventListener]. Every timestamp is a
 * [System.nanoTime] reading -- monotonic and immune to wall-clock adjustments -- captured on
 * whichever OkHttp-internal thread raised the event. All reads and writes go through the same
 * monitor so a concurrently-read [snapshot] never observes a half-written phase.
 */
internal class CallPhaseTimestamps(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private val marks = EnumMap<CallPhaseMark, Long>(CallPhaseMark::class.java)

    /** Records [mark] as having happened now. Safe to call from any thread. */
    fun record(mark: CallPhaseMark) {
        val now = nanoTime()
        synchronized(lock) { marks[mark] = now }
    }

    /**
     * Renders the timestamps captured so far as phase durations. A phase whose start or end was
     * never observed -- a pooled connection skips DNS/connect/TLS entirely, a plaintext request has
     * no TLS handshake, a call that fails before headers arrive has no wait/receive phase -- is left
     * `null` rather than coerced to `0`; `0` would render as a real zero-length phase in the UI.
     */
    fun snapshot(): NetworkTimingPhases =
        synchronized(lock) {
            val tlsMs = durationMs(CallPhaseMark.SECURE_CONNECT_START, CallPhaseMark.SECURE_CONNECT_END)
            val rawConnectMs = durationMs(CallPhaseMark.CONNECT_START, CallPhaseMark.CONNECT_END)
            // connectEnd fires only after the TLS handshake finishes on an HTTPS connection, so the
            // raw connectStart..connectEnd span double-counts TLS time. Subtract it out so connectMs
            // is the TCP handshake alone -- its own row in the DNS/TCP/TLS/TTFB/download breakdown.
            val connectMs = rawConnectMs?.let { raw -> (raw - (tlsMs ?: 0L)).coerceAtLeast(0L) }
            val sendEndNanos = marks[CallPhaseMark.REQUEST_BODY_END] ?: marks[CallPhaseMark.REQUEST_HEADERS_END]
            NetworkTimingPhases(
                dnsMs = durationMs(CallPhaseMark.DNS_START, CallPhaseMark.DNS_END),
                connectMs = connectMs,
                tlsMs = tlsMs,
                sendMs = durationMsFrom(marks[CallPhaseMark.REQUEST_HEADERS_START], sendEndNanos),
                waitMs = durationMsFrom(sendEndNanos, marks[CallPhaseMark.RESPONSE_HEADERS_START]),
                receiveMs = durationMs(CallPhaseMark.RESPONSE_BODY_START, CallPhaseMark.RESPONSE_BODY_END),
            )
        }

    private fun durationMs(
        start: CallPhaseMark,
        end: CallPhaseMark,
    ): Long? = durationMsFrom(marks[start], marks[end])

    private fun durationMsFrom(
        startNanos: Long?,
        endNanos: Long?,
    ): Long? {
        if (startNanos == null || endNanos == null) return null
        return (endNanos - startNanos) / NANOS_PER_MILLI
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Records phase boundary timestamps into [timestamps] and forwards every callback to an optional
 * [delegate] so installing DevConsole never displaces a host's own [EventListener]. [onCallFinished]
 * runs on `callEnd`/`callFailed` as the leak-safety net described on [DevConsoleOkHttpEventListenerFactory].
 */
@Suppress("TooManyFunctions") // One override per okhttp3.EventListener callback -- the whole surface it exposes.
internal class PhaseTrackingEventListener(
    private val timestamps: CallPhaseTimestamps,
    private val delegate: EventListener?,
    private val onCallFinished: () -> Unit,
) : EventListener() {
    override fun callStart(call: Call) {
        delegate?.callStart(call)
    }

    override fun dnsStart(
        call: Call,
        domainName: String,
    ) {
        timestamps.record(CallPhaseMark.DNS_START)
        delegate?.dnsStart(call, domainName)
    }

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<InetAddress>,
    ) {
        timestamps.record(CallPhaseMark.DNS_END)
        delegate?.dnsEnd(call, domainName, inetAddressList)
    }

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
    ) {
        timestamps.record(CallPhaseMark.CONNECT_START)
        delegate?.connectStart(call, inetSocketAddress, proxy)
    }

    override fun secureConnectStart(call: Call) {
        timestamps.record(CallPhaseMark.SECURE_CONNECT_START)
        delegate?.secureConnectStart(call)
    }

    override fun secureConnectEnd(
        call: Call,
        handshake: Handshake?,
    ) {
        timestamps.record(CallPhaseMark.SECURE_CONNECT_END)
        delegate?.secureConnectEnd(call, handshake)
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        timestamps.record(CallPhaseMark.CONNECT_END)
        delegate?.connectEnd(call, inetSocketAddress, proxy, protocol)
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        // Deliberately no timestamp write: whatever phases completed before the failure (a DNS
        // lookup that succeeded before the connect attempt failed, say) stay recorded as-is, and
        // callFailed() below decides the call's final outcome.
        delegate?.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
    }

    override fun requestHeadersStart(call: Call) {
        timestamps.record(CallPhaseMark.REQUEST_HEADERS_START)
        delegate?.requestHeadersStart(call)
    }

    override fun requestHeadersEnd(
        call: Call,
        request: Request,
    ) {
        timestamps.record(CallPhaseMark.REQUEST_HEADERS_END)
        delegate?.requestHeadersEnd(call, request)
    }

    override fun requestBodyStart(call: Call) {
        delegate?.requestBodyStart(call)
    }

    override fun requestBodyEnd(
        call: Call,
        byteCount: Long,
    ) {
        timestamps.record(CallPhaseMark.REQUEST_BODY_END)
        delegate?.requestBodyEnd(call, byteCount)
    }

    override fun requestFailed(
        call: Call,
        ioe: IOException,
    ) {
        delegate?.requestFailed(call, ioe)
    }

    override fun responseHeadersStart(call: Call) {
        timestamps.record(CallPhaseMark.RESPONSE_HEADERS_START)
        delegate?.responseHeadersStart(call)
    }

    override fun responseHeadersEnd(
        call: Call,
        response: Response,
    ) {
        delegate?.responseHeadersEnd(call, response)
    }

    override fun responseBodyStart(call: Call) {
        timestamps.record(CallPhaseMark.RESPONSE_BODY_START)
        delegate?.responseBodyStart(call)
    }

    override fun responseBodyEnd(
        call: Call,
        byteCount: Long,
    ) {
        timestamps.record(CallPhaseMark.RESPONSE_BODY_END)
        delegate?.responseBodyEnd(call, byteCount)
    }

    override fun responseFailed(
        call: Call,
        ioe: IOException,
    ) {
        delegate?.responseFailed(call, ioe)
    }

    override fun callEnd(call: Call) {
        delegate?.callEnd(call)
        onCallFinished()
    }

    override fun callFailed(
        call: Call,
        ioe: IOException,
    ) {
        delegate?.callFailed(call, ioe)
        onCallFinished()
    }
}
