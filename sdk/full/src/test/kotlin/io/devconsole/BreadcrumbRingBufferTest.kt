/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BreadcrumbRingBufferTest {
    private fun crumb(index: Int) = Breadcrumb(index.toLong(), "plugin$index", "type$index", index, "summary$index")

    @Test
    fun `an empty buffer has an empty snapshot`() {
        assertEquals(emptyList<Breadcrumb>(), BreadcrumbRingBuffer(5).snapshot())
    }

    @Test
    fun `entries under capacity are all retained in append order`() {
        val buffer = BreadcrumbRingBuffer(5)

        buffer.record(crumb(1))
        buffer.record(crumb(2))
        buffer.record(crumb(3))

        assertEquals(listOf(crumb(1), crumb(2), crumb(3)), buffer.snapshot())
    }

    @Test
    fun `capacity is respected and the oldest entry is evicted first`() {
        val buffer = BreadcrumbRingBuffer(3)

        (1..5).forEach { buffer.record(crumb(it)) }

        assertEquals(listOf(crumb(3), crumb(4), crumb(5)), buffer.snapshot())
    }

    @Test
    fun `ordering stays oldest-to-newest across many wraps`() {
        val buffer = BreadcrumbRingBuffer(4)

        (1..20).forEach { buffer.record(crumb(it)) }

        assertEquals(listOf(crumb(17), crumb(18), crumb(19), crumb(20)), buffer.snapshot())
    }

    @Test
    fun `capacity zero disables the buffer cleanly`() {
        val buffer = BreadcrumbRingBuffer(0)

        buffer.record(crumb(1))
        buffer.record(crumb(2))

        assertEquals(emptyList<Breadcrumb>(), buffer.snapshot())
    }

    @Test
    fun `resize preserves the most recent entries that still fit`() {
        val buffer = BreadcrumbRingBuffer(5)
        (1..5).forEach { buffer.record(crumb(it)) }

        buffer.resize(2)

        assertEquals(listOf(crumb(4), crumb(5)), buffer.snapshot())
    }

    @Test
    fun `resize to a larger capacity keeps every existing entry and accepts new ones`() {
        val buffer = BreadcrumbRingBuffer(2)
        buffer.record(crumb(1))
        buffer.record(crumb(2))

        buffer.resize(4)
        buffer.record(crumb(3))

        assertEquals(listOf(crumb(1), crumb(2), crumb(3)), buffer.snapshot())
    }

    @Test
    fun `resize to zero disables further recording`() {
        val buffer = BreadcrumbRingBuffer(3)
        buffer.record(crumb(1))

        buffer.resize(0)
        buffer.record(crumb(2))

        assertEquals(emptyList<Breadcrumb>(), buffer.snapshot())
    }

    @Test
    fun `concurrent append while reading does not throw`() {
        val buffer = BreadcrumbRingBuffer(64)
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val errors = mutableListOf<Throwable>()

        repeat(4) { worker ->
            executor.execute {
                ready.countDown()
                start.await()
                try {
                    repeat(500) { i -> buffer.record(crumb(worker * 1000 + i)) }
                } catch (t: Throwable) {
                    synchronized(errors) { errors += t }
                } finally {
                    done.countDown()
                }
            }
        }
        repeat(4) {
            executor.execute {
                ready.countDown()
                start.await()
                try {
                    repeat(500) { buffer.snapshot() }
                } catch (t: Throwable) {
                    synchronized(errors) { errors += t }
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertTrue("expected no exceptions, got: $errors", errors.isEmpty())
        assertEquals(64, buffer.snapshot().size)
    }
}
