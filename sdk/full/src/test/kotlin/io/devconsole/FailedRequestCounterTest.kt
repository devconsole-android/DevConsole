/**
 * @author Shakib
 * @since 09/11/26
 */
package io.devconsole

import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkRequestMetadata
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkTransaction
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailedRequestCounterTest {
    private val factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.recommended()))
    private val counter = FailedRequestCounter()

    private fun transaction(
        id: String,
        response: NetworkResponseInput?,
        tags: Map<String, String> = emptyMap(),
    ): NetworkTransaction =
        NetworkTransaction(
            id,
            startedAtEpochMs = 0,
            completedAtEpochMs = 1,
            capture =
                factory.capture(
                    NetworkRequestInput("GET", "https://api.test/orders")
                        .withMetadata(NetworkRequestMetadata(tags = tags)),
                    response,
                ),
        )

    @Test
    fun `a 500 response is counted`() {
        assertTrue(counter.record(transaction("tx-500", NetworkResponseInput(500))))
        assertEquals(1, counter.count)
    }

    @Test
    fun `a 404 response is counted`() {
        assertTrue(counter.record(transaction("tx-404", NetworkResponseInput(404))))
        assertEquals(1, counter.count)
    }

    @Test
    fun `a 200 response is not counted`() {
        assertFalse(counter.record(transaction("tx-200", NetworkResponseInput(200))))
        assertEquals(0, counter.count)
    }

    @Test
    fun `a transaction without a response is not counted`() {
        assertFalse(counter.record(transaction("tx-null", null)))
        assertEquals(0, counter.count)
    }

    @Test
    fun `a zero status with an error is counted`() {
        assertTrue(counter.record(transaction("tx-error", NetworkResponseInput(0, error = "timeout"))))
        assertEquals(1, counter.count)
    }

    @Test
    fun `a mocked transaction is not counted`() {
        assertFalse(
            counter.record(
                transaction("tx-mocked", NetworkResponseInput(500), tags = mapOf("mocked" to "true")),
            ),
        )
        assertEquals(0, counter.count)
    }

    @Test
    fun `the same id recorded twice counts once`() {
        assertTrue(counter.record(transaction("tx-dup", NetworkResponseInput(500))))
        assertFalse(counter.record(transaction("tx-dup", NetworkResponseInput(500))))
        assertEquals(1, counter.count)
    }

    @Test
    fun `reset returns true only when it cleared something`() {
        counter.record(transaction("tx-1", NetworkResponseInput(500)))

        assertTrue(counter.reset())
        assertEquals(0, counter.count)
        assertFalse(counter.reset())
    }

    @Test
    fun `capacity stops growth`() {
        val bounded = FailedRequestCounter(capacity = 2)

        assertTrue(bounded.record(transaction("tx-1", NetworkResponseInput(500))))
        assertTrue(bounded.record(transaction("tx-2", NetworkResponseInput(500))))
        assertFalse(bounded.record(transaction("tx-3", NetworkResponseInput(500))))
        assertEquals(2, bounded.count)
    }
}
