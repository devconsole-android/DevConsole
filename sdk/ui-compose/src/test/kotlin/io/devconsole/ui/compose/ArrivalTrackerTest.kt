/**
 * @author Shakib
 * @since 11/08/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalTrackerTest {
    @Test
    fun `the first snapshot never flashes`() {
        val tracker = ArrivalTracker()

        // A screen opening onto a session's worth of retained captures must not wash every row in
        // signal -- only what lands while the operator is watching counts as an arrival.
        assertEquals(emptySet<String>(), tracker.accept(listOf("a", "b", "c")))
    }

    @Test
    fun `reports only the ids added since the previous snapshot`() {
        val tracker = ArrivalTracker()
        tracker.accept(listOf("a", "b"))

        assertEquals(setOf("c"), tracker.accept(listOf("c", "a", "b")))
        assertEquals(setOf("d", "e"), tracker.accept(listOf("e", "d", "c", "a", "b")))
    }

    @Test
    fun `an unchanged snapshot reports nothing`() {
        val tracker = ArrivalTracker()
        tracker.accept(listOf("a", "b"))

        assertEquals(emptySet<String>(), tracker.accept(listOf("a", "b")))
        assertEquals(emptySet<String>(), tracker.accept(listOf("b", "a")))
    }

    @Test
    fun `an id that leaves and returns counts as a new arrival`() {
        val tracker = ArrivalTracker()
        tracker.accept(listOf("a", "b"))

        // "b" drops out -- a search filter narrowing, or the retained buffer evicting it.
        assertEquals(emptySet<String>(), tracker.accept(listOf("a")))
        // Coming back is a genuine arrival on screen, and is flashed as one.
        assertEquals(setOf("b"), tracker.accept(listOf("a", "b")))
    }

    @Test
    fun `forgets ids that leave so the seen set cannot grow without bound`() {
        val tracker = ArrivalTracker()
        tracker.accept(List(1_000) { "id-$it" })

        // The whole first batch has been evicted; a single retained row is not an arrival, and the
        // tracker is not still holding the thousand ids that scrolled out of the buffer.
        assertEquals(emptySet<String>(), tracker.accept(listOf("id-999")))
        assertEquals(setOf("id-0"), tracker.accept(listOf("id-999", "id-0")))
    }
}
