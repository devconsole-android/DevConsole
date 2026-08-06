/**
 * @author Shakib
 * @since 19/07/26
 */
package io.devconsole.mocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockEngineConflictTest {
    @Test
    fun `flags two rules with identical predicates as conflicting`() {
        val ruleA = MockRule(id = "a", priority = 0, method = "GET", host = "api.example.test", path = "/orders")
        val ruleB = MockRule(id = "b", priority = 1, method = "GET", host = "api.example.test", path = "/orders")
        val engine = MockEngine(listOf(ruleA, ruleB))

        val conflicts = engine.conflicts()

        assertEquals(1, conflicts.size)
        assertTrue(conflicts.single().let { (first, second) -> setOf(first.id, second.id) == setOf("a", "b") })
    }

    @Test
    fun `does not flag rules with different hosts`() {
        val ruleA = MockRule(id = "a", priority = 0, method = "GET", host = "api.example.test", path = "/orders")
        val ruleB = MockRule(id = "b", priority = 1, method = "GET", host = "other.example.test", path = "/orders")
        val engine = MockEngine(listOf(ruleA, ruleB))

        assertEquals(emptyList<Pair<MockRule, MockRule>>(), engine.conflicts())
    }

    @Test
    fun `flags an unconstrained wildcard path rule against a specific one on the same host and method`() {
        val wildcard = MockRule(id = "wildcard", priority = 0, method = "GET", host = "api.example.test")
        val specific =
            MockRule(id = "specific", priority = 1, method = "GET", host = "api.example.test", path = "/orders")
        val engine = MockEngine(listOf(wildcard, specific))

        val conflicts = engine.conflicts()

        assertEquals(1, conflicts.size)
    }

    @Test
    fun `does not flag a disabled rule`() {
        val ruleA = MockRule(id = "a", priority = 0, method = "GET", host = "api.example.test", path = "/orders")
        val ruleB = MockRule(id = "b", priority = 1, method = "GET", host = "api.example.test", path = "/orders")
        val engine = MockEngine(listOf(ruleA, ruleB), enabled = false)

        assertEquals(emptyList<Pair<MockRule, MockRule>>(), engine.conflicts())
    }
}
