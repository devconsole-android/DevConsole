/**
 * @author Shakib
 * @since 02/08/26
 */
package io.devconsole.network

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ExportSelectionTest {
    private fun store(): InMemoryNetworkTransactionStore =
        InMemoryNetworkTransactionStore(NetworkCursorCodec("export-selection-key".encodeToByteArray()))

    private fun transaction(
        id: String,
        startedAt: Long,
        url: String = "https://api.test/$id",
    ) = NetworkTransaction(
        id = id,
        startedAtEpochMs = startedAt,
        completedAtEpochMs = startedAt + 5,
        capture =
            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                NetworkRequestInput("GET", url),
                NetworkResponseInput(200),
            ),
    )

    @Test
    fun `All resolves every transaction the store holds`() {
        val store = store()
        store.record(transaction("a", 100))
        store.record(transaction("b", 200))

        val resolved = store.resolveExportSelection(ExportSelection.All)

        assertEquals(setOf("a", "b"), resolved!!.map { it.id }.toSet())
    }

    @Test
    fun `Ids resolves exactly the named transactions and silently drops unknown ids`() {
        val store = store()
        store.record(transaction("a", 100))
        store.record(transaction("b", 200))
        store.record(transaction("c", 300))

        val resolved = store.resolveExportSelection(ExportSelection.Ids(setOf("a", "c", "missing")))

        assertEquals(setOf("a", "c"), resolved!!.map { it.id }.toSet())
    }

    @Test
    fun `Ids ignores an accompanying base query -- explicit selection is always exact`() {
        val store = store()
        store.record(transaction("a", 100))

        val resolved =
            store.resolveExportSelection(
                ExportSelection.Ids(setOf("a")),
                baseQuery = NetworkTransactionQuery(limit = 10, hosts = setOf("nonexistent.test")),
            )

        assertEquals(listOf("a"), resolved!!.map { it.id })
    }

    @Test
    fun `TimeRange resolves only transactions started within the inclusive window`() {
        val store = store()
        store.record(transaction("early", 50))
        store.record(transaction("inside", 150))
        store.record(transaction("late", 500))

        val resolved = store.resolveExportSelection(ExportSelection.TimeRange(100, 200))

        assertEquals(listOf("inside"), resolved!!.map { it.id })
    }

    @Test
    fun `TimeRange layers on top of an accompanying base query`() {
        val store = store()
        store.record(transaction("matchingHostAndTime", 150, url = "https://api.test/x"))
        store.record(transaction("wrongHost", 150, url = "https://other.test/x"))

        val resolved =
            store.resolveExportSelection(
                ExportSelection.TimeRange(100, 200),
                baseQuery = NetworkTransactionQuery(limit = 10, hosts = setOf("api.test")),
            )

        assertEquals(listOf("matchingHostAndTime"), resolved!!.map { it.id })
    }

    @Test
    fun `All returns null rather than an empty list when the base query cursor is invalid`() {
        val store = store()
        store.record(transaction("a", 100))

        val resolved =
            store.resolveExportSelection(
                ExportSelection.All,
                baseQuery = NetworkTransactionQuery(limit = 10, cursor = "not-a-real-cursor"),
            )

        assertNull(resolved)
    }

    @Test
    fun `TimeRange returns null rather than an empty list when the base query cursor is invalid`() {
        val store = store()
        store.record(transaction("a", 150))

        val resolved =
            store.resolveExportSelection(
                ExportSelection.TimeRange(100, 200),
                baseQuery = NetworkTransactionQuery(limit = 10, cursor = "not-a-real-cursor"),
            )

        assertNull(resolved)
    }

    @Test
    fun `Ids selection rejects an empty set`() {
        assertThrows(IllegalArgumentException::class.java) { ExportSelection.Ids(emptySet()) }
    }

    @Test
    fun `TimeRange selection rejects an inverted window`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExportSelection.TimeRange(fromEpochMs = 200, toEpochMs = 100)
        }
    }

    @Test
    fun `TimeRange selection rejects a negative fromEpochMs`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExportSelection.TimeRange(fromEpochMs = -1, toEpochMs = 100)
        }
    }

    @Test
    fun `Ids selection rejects a blank id`() {
        assertThrows(IllegalArgumentException::class.java) { ExportSelection.Ids(setOf("")) }
    }
}
