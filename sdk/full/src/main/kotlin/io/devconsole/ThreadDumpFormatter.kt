/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

/** A single thread's name and stack frames, already rendered as text (`Thread.toString()` shape). */
internal data class ThreadStackSample(
    val name: String,
    val frames: List<String>,
)

/**
 * Orders a raw [Thread.getAllStackTraces] snapshot the way [AnrWatchdog] needs it: the stalled main
 * thread first -- that is where the block surfaced -- then every other thread sorted by name so the
 * dump is deterministic across runs (tests depend on that ordering being stable).
 */
internal fun Map<Thread, Array<StackTraceElement>>.toOrderedStackSamples(mainThread: Thread): List<ThreadStackSample> {
    val main = entries.firstOrNull { it.key == mainThread }
    val others = entries.filter { it.key != mainThread }.sortedBy { it.key.name }
    return (listOfNotNull(main) + others).map { (thread, frames) ->
        ThreadStackSample(thread.name, frames.map { it.toString() })
    }
}

/**
 * Renders a bounded, deterministically-ordered [ThreadStackSample] list into the text dump embedded
 * in a crash/ANR payload. Every cap -- thread count, frames per thread, and total characters -- marks
 * its truncation explicitly in the output; nothing is dropped silently.
 */
internal object ThreadDumpFormatter {
    fun format(
        samples: List<ThreadStackSample>,
        maxThreads: Int,
        maxFramesPerThread: Int,
        maxChars: Int,
    ): String {
        val threadCap = maxThreads.coerceAtLeast(0)
        val frameCap = maxFramesPerThread.coerceAtLeast(0)
        val includedThreads = samples.take(threadCap)
        val text =
            buildString {
                includedThreads.forEachIndexed { index, sample ->
                    if (index > 0) append('\n')
                    append('"').append(sample.name).append("\"\n")
                    val includedFrames = sample.frames.take(frameCap)
                    includedFrames.forEach { frame -> append("\tat ").append(frame).append('\n') }
                    val omittedFrames = sample.frames.size - includedFrames.size
                    if (omittedFrames > 0) {
                        append("\t... ").append(omittedFrames).append(" more frames (truncated)\n")
                    }
                }
                val omittedThreads = samples.size - includedThreads.size
                if (omittedThreads > 0) {
                    append("... ").append(omittedThreads).append(" more threads (truncated)\n")
                }
            }
        return text.capAt(maxChars.coerceAtLeast(0))
    }

    private fun String.capAt(maxChars: Int): String {
        if (length <= maxChars) return this
        val marker = "\n... (truncated, dump exceeded $maxChars chars)"
        val keep = (maxChars - marker.length).coerceAtLeast(0)
        return take(keep) + marker
    }
}
