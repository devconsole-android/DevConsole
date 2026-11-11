/**
 * @author Shakib
 * @since 09/11/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorTestConditionsTest {
    @Test
    fun `conditions create session rules with the expected actions`() {
        val types =
            listOf(
                InspectorMockActionType.THROTTLE,
                InspectorMockActionType.CONNECTION_FAILURE,
                InspectorMockActionType.TIMEOUT,
                InspectorMockActionType.STATIC,
                InspectorMockActionType.STATIC,
            )
        InspectorTestCondition.entries.forEachIndexed { index, condition ->
            val rule = condition.toMockRule()
            assertEquals("devconsole-condition-" + condition.name.lowercase().replace('_', '-'), rule.id)
            assertEquals(types[index], rule.actionType)
            assertEquals("SESSION", rule.scope)
            assertEquals(".*", rule.pathPattern)
            assertNull(rule.method)
            assertNull(rule.host)
            assertTrue(rule.enabled)
            assertEquals(if (index == 0) -1_000_000 else 1_000_000, rule.priority)
            assertEquals(listOf(200, 200, 200, 500, 401)[index], rule.statusCode)
            assertEquals(condition, testConditionForRuleId(rule.id))
        }
        val slow = InspectorTestCondition.SLOW_NETWORK.toMockRule()
        assertEquals(50_000L, slow.throttleBytesPerSecond)
        assertEquals(3_000L, slow.throttleLatencyMs)
        assertEquals(10_000L, InspectorTestCondition.TIMEOUTS.toMockRule().timeoutMs)
        assertEquals(
            "DevConsole test condition: no connection",
            InspectorTestCondition.NO_CONNECTION.toMockRule().failureMessage,
        )
        listOf(InspectorTestCondition.SERVER_ERRORS, InspectorTestCondition.SIGNED_OUT).forEach {
            assertEquals(mapOf("Content-Type" to "application/json"), it.toMockRule().headers)
        }
        assertEquals(
            "{\"error\":\"DevConsole test condition: server error\"}",
            InspectorTestCondition.SERVER_ERRORS.toMockRule().body,
        )
        assertEquals(
            "{\"error\":\"DevConsole test condition: signed out\"}",
            InspectorTestCondition.SIGNED_OUT.toMockRule().body,
        )
    }

    @Test
    fun `condition transitions dispatch actions in order`() {
        val first = InspectorTestCondition.SLOW_NETWORK
        val second = InspectorTestCondition.TIMEOUTS
        val delete = InspectorAction.DeleteMockRule(first.toMockRule().id)
        val upsert = InspectorAction.UpsertMockRule(second.toMockRule())
        assertEquals(listOf(upsert), testConditionActions(null, second, true))
        assertEquals(listOf(delete, upsert), testConditionActions(first, second, true))
        assertEquals(listOf(delete), testConditionActions(first, null, true))
        assertEquals(emptyList<InspectorAction>(), testConditionActions(first, first, true))
        assertEquals(listOf(InspectorAction.SetMocksEnabled(true), upsert), testConditionActions(null, second, false))
        assertEquals(
            listOf(delete, upsert),
            testConditionActions(null, second, true, existingRuleIds = listOf(first.toMockRule().id, "user")),
        )
        assertEquals(
            listOf(delete),
            testConditionActions(null, null, true, existingRuleIds = listOf(first.toMockRule().id)),
        )
    }

    @Test
    fun `only a successful condition update is silent`() {
        val id = InspectorTestCondition.TIMEOUTS.toMockRule().id
        assertTrue(InspectorCommandResult.Success("Mock rule $id saved").isTestConditionSuccess())
        assertEquals(false, InspectorCommandResult.Success("Mock rule mine saved").isTestConditionSuccess())
        assertEquals(false, InspectorCommandResult.Failed("Mock rule $id failed").isTestConditionSuccess())
    }

    @Test
    fun `mock action names the condition it would turn off`() {
        val condition = InspectorTestCondition.SIGNED_OUT
        val mocked =
            InspectorTransactionUi(id = "1", method = "GET", host = "h", path = "/", statusCode = 200, isMocked = true)
        assertEquals("Turn off Signed out", mockActionLabel(mocked.copy(mockRuleId = condition.toMockRule().id)))
        assertEquals("Unmock this response", mockActionLabel(mocked.copy(mockRuleId = "mine")))
        assertEquals("Mock this response", mockActionLabel(mocked.copy(isMocked = false)))
    }

    @Test
    fun `slow network reads as slowed because the response is real`() {
        val transaction =
            InspectorTransactionUi(id = "1", method = "GET", host = "h", path = "/", statusCode = 200, isMocked = true)
        val slow = InspectorTestCondition.SLOW_NETWORK.toMockRule().id
        val signedOut = InspectorTestCondition.SIGNED_OUT.toMockRule().id
        assertEquals("slowed", transaction.copy(mockRuleId = slow).mockedLabel())
        assertEquals("mocked", transaction.copy(mockRuleId = signedOut).mockedLabel())
        assertNull(transaction.copy(isMocked = false).mockedLabel())
    }

    @Test
    fun `active condition and user rules respect enabled state`() {
        val condition = InspectorTestCondition.TIMEOUTS
        val rule = condition.toMockRule()
        val user = InspectorMockRuleUi(id = "user")
        val state = InspectorState(mocksEnabled = true, mockRules = listOf(rule, user))
        assertEquals(condition, activeTestCondition(state))
        assertNull(activeTestCondition(state.copy(mocksEnabled = false)))
        assertNull(activeTestCondition(state.copy(mockRules = listOf(rule.copy(enabled = false)))))
        assertEquals(listOf("user"), activeMockRuleIds(state))
    }

    @Test
    fun `traffic notice describes conditions and user rules`() {
        val condition = InspectorTestCondition.SIGNED_OUT
        val rule = condition.toMockRule()
        val first = InspectorMockRuleUi(id = "first")
        val second = InspectorMockRuleUi(id = "second")
        val state = InspectorState(mocksEnabled = true)
        assertNull(trafficFakeResponsesNotice(state))
        assertNull(trafficFakeResponsesNotice(state.copy(mockRules = listOf(rule), selectedSessionId = "past")))
        assertNull(trafficFakeResponsesNotice(state.copy(mockRules = listOf(rule), mocksEnabled = false)))
        assertEquals(
            TrafficFakeResponsesNotice(condition.notice, listOf(rule.id)),
            trafficFakeResponsesNotice(state.copy(mockRules = listOf(rule))),
        )
        assertEquals(
            "1 mock is on. Some responses aren't real.",
            trafficFakeResponsesNotice(state.copy(mockRules = listOf(first)))?.text,
        )
        assertEquals(
            "2 mocks are on. Some responses aren't real.",
            trafficFakeResponsesNotice(state.copy(mockRules = listOf(first, second)))?.text,
        )
        assertEquals(
            TrafficFakeResponsesNotice(
                "Signed out and 1 mock are on. Some responses aren't real.",
                listOf(rule.id, first.id),
            ),
            trafficFakeResponsesNotice(state.copy(mockRules = listOf(rule, first))),
        )
        assertEquals(
            "Signed out and 2 mocks are on. Some responses aren't real.",
            trafficFakeResponsesNotice(state.copy(mockRules = listOf(rule, first, second)))?.text,
        )
    }
}
