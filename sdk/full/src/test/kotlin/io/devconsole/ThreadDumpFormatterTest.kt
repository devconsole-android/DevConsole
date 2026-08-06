/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadDumpFormatterTest {
    private fun sample(
        name: String,
        frameCount: Int,
    ) = ThreadStackSample(name, List(frameCount) { index -> "com.example.Class$index.method(Class$index.kt:$index)" })

    @Test
    fun `every thread and frame is included when under every cap`() {
        val samples = listOf(sample("main", 2), sample("worker", 3))

        val dump = ThreadDumpFormatter.format(samples, maxThreads = 10, maxFramesPerThread = 10, maxChars = 10_000)

        assertTrue("\"main\"" in dump)
        assertTrue("\"worker\"" in dump)
        assertTrue("truncated" !in dump)
        assertEquals(2 + 3, dump.lines().count { it.trimStart().startsWith("at ") })
    }

    @Test
    fun `thread count beyond the cap is dropped and explicitly marked`() {
        val samples = listOf(sample("main", 1), sample("alpha", 1), sample("beta", 1), sample("gamma", 1))

        val dump = ThreadDumpFormatter.format(samples, maxThreads = 2, maxFramesPerThread = 10, maxChars = 10_000)

        assertTrue("\"main\"" in dump)
        assertTrue("\"alpha\"" in dump)
        assertTrue("\"beta\"" !in dump)
        assertTrue("\"gamma\"" !in dump)
        assertTrue("2 more threads (truncated)" in dump)
    }

    @Test
    fun `frames beyond the per-thread cap are dropped and explicitly marked`() {
        val samples = listOf(sample("main", 5))

        val dump = ThreadDumpFormatter.format(samples, maxThreads = 10, maxFramesPerThread = 2, maxChars = 10_000)

        assertEquals(2, dump.lines().count { it.trimStart().startsWith("at ") })
        assertTrue("3 more frames (truncated)" in dump)
    }

    @Test
    fun `total output beyond maxChars is capped and explicitly marked`() {
        val samples = listOf(sample("main", 200))

        val dump = ThreadDumpFormatter.format(samples, maxThreads = 10, maxFramesPerThread = 200, maxChars = 500)

        assertTrue(dump.length <= 500)
        assertTrue("(truncated, dump exceeded 500 chars)" in dump)
    }

    @Test
    fun `main thread is ordered first regardless of input order`() {
        val threadA = Thread(null, {}, "zeta")
        val threadB = Thread(null, {}, "alpha")
        val mainThread = Thread(null, {}, "main")
        val allTraces =
            linkedMapOf(
                threadA to arrayOf(frame("A")),
                threadB to arrayOf(frame("B")),
                mainThread to arrayOf(frame("Main")),
            )

        val ordered = allTraces.toOrderedStackSamples(mainThread)

        assertEquals(listOf("main", "alpha", "zeta"), ordered.map { it.name })
    }

    @Test
    fun `non-main threads are sorted by name deterministically`() {
        val threadZ = Thread(null, {}, "zzz-worker")
        val threadA = Thread(null, {}, "aaa-worker")
        val threadM = Thread(null, {}, "mmm-worker")
        val mainThread = Thread(null, {}, "main")
        val allTraces =
            mapOf(
                threadZ to arrayOf(frame("Z")),
                threadA to arrayOf(frame("A")),
                threadM to arrayOf(frame("M")),
                mainThread to arrayOf(frame("Main")),
            )

        val orderedNames = allTraces.toOrderedStackSamples(mainThread).map { it.name }

        assertEquals(listOf("main", "aaa-worker", "mmm-worker", "zzz-worker"), orderedNames)
    }

    private fun frame(tag: String) = StackTraceElement("com.example.$tag", "run", "$tag.kt", 1)
}
