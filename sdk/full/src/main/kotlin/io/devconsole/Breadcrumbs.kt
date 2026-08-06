/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

/**
 * An already-redacted summary of a single captured event, kept only long enough to give a crash or
 * ANR payload lead-up context. Carries no payload bodies -- this must never become a second
 * redaction surface (see [CrashCapture]'s KDoc for the synchronous crash-path budget this protects).
 */
internal data class Breadcrumb(
    val wallTimeMs: Long,
    val pluginId: String,
    val type: String,
    val severity: Int,
    val summary: String,
)

/**
 * Bounded ring buffer of the most recent [Breadcrumb]s, fed from [CaptureTimelineBridge] as events
 * are appended and read synchronously by [CrashCapture] on the uncaught-exception thread.
 *
 * A plain synchronized circular array, not a lock-free structure: appends happen at ordinary capture
 * rates (not a hot loop), so a monitor is cheap enough here, and simplicity matters more than
 * avoiding a lock that is never held across I/O or held long enough to contend with the crash-path
 * read.
 *
 * `capacity <= 0` disables the buffer entirely: [record] no-ops and [snapshot] always returns an
 * empty list, which is how `breadcrumbDepth = 0` turns breadcrumbs off cleanly.
 */
internal class BreadcrumbRingBuffer(
    @Volatile private var capacity: Int,
) {
    private val lock = Any()
    private var buffer: Array<Breadcrumb?> = newBuffer(capacity)
    private var writeIndex = 0
    private var size = 0

    fun record(breadcrumb: Breadcrumb) {
        if (capacity <= 0) return
        synchronized(lock) {
            if (capacity <= 0) return
            buffer[writeIndex] = breadcrumb
            writeIndex = (writeIndex + 1) % capacity
            if (size < capacity) size++
        }
    }

    /** Oldest first, newest last -- the order a reader wants for "what led up to this." */
    fun snapshot(): List<Breadcrumb> =
        synchronized(lock) {
            if (capacity <= 0 || size == 0) return emptyList()
            val start = if (size < capacity) 0 else writeIndex
            List(size) { offset -> buffer[(start + offset) % capacity]!! }
        }

    /**
     * Reallocates to [newCapacity], preserving as many of the most recent entries as still fit. Used
     * when a host re-initializes with a different [io.devconsole.api.CrashPolicy.breadcrumbDepth].
     */
    fun resize(newCapacity: Int) {
        synchronized(lock) {
            if (newCapacity == capacity) return
            val kept = if (newCapacity <= 0) emptyList() else snapshotLocked().takeLast(newCapacity)
            capacity = newCapacity
            buffer = newBuffer(newCapacity)
            writeIndex = 0
            size = 0
            kept.forEach { crumb ->
                buffer[writeIndex] = crumb
                writeIndex = (writeIndex + 1) % newCapacity
                size++
            }
        }
    }

    private fun snapshotLocked(): List<Breadcrumb> {
        if (size == 0) return emptyList()
        val start = if (size < capacity) 0 else writeIndex
        return List(size) { offset -> buffer[(start + offset) % capacity]!! }
    }

    private fun newBuffer(forCapacity: Int): Array<Breadcrumb?> = arrayOfNulls(forCapacity.coerceAtLeast(0))
}
