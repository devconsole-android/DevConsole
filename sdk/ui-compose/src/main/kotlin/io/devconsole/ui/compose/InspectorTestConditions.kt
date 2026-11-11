/**
 * @author Shakib
 * @since 09/11/26
 */
@file:Suppress("MatchingDeclarationName")

package io.devconsole.ui.compose

internal enum class InspectorTestCondition(
    val label: String,
    val notice: String,
) {
    SLOW_NETWORK("Slow network", "Slow network is on. Real requests are delayed."),
    NO_CONNECTION("No connection", "No connection is on. Every request fails."),
    TIMEOUTS("Timeouts", "Timeouts are on. Every request times out."),
    SERVER_ERRORS("Server errors", "Server errors are on. Every request returns 500."),
    SIGNED_OUT("Signed out", "Signed out is on. Every request returns 401."),
}

private const val CONDITION_PREFIX = "devconsole-condition-"
private const val CONDITION_PRIORITY = 1_000_000

internal fun String.isTestConditionRuleId(): Boolean = startsWith(CONDITION_PREFIX)

/** The chips already show the change, so a successful condition update needs no toast. */
internal fun InspectorCommandResult.isTestConditionSuccess(): Boolean =
    this is InspectorCommandResult.Success && CONDITION_PREFIX in summary

internal fun mockActionLabel(transaction: InspectorTransactionUi): String =
    when {
        testConditionForRuleId(transaction.mockRuleId) != null ->
            "Turn off ${testConditionForRuleId(transaction.mockRuleId)?.label}"
        transaction.isMocked -> "Unmock this response"
        else -> "Mock this response"
    }

/** Slow network still returns the real response, so it must not read as a fake one. */
internal fun InspectorTransactionUi.mockedLabel(): String? =
    when {
        testConditionForRuleId(mockRuleId) == InspectorTestCondition.SLOW_NETWORK -> "slowed"
        isMocked -> "mocked"
        else -> null
    }

internal fun testConditionForRuleId(id: String?): InspectorTestCondition? =
    InspectorTestCondition.entries.firstOrNull { it.ruleId() == id }

private fun InspectorTestCondition.ruleId(): String = CONDITION_PREFIX + name.lowercase().replace('_', '-')

internal fun InspectorTestCondition.toMockRule(): InspectorMockRuleUi {
    val priority = if (this == InspectorTestCondition.SLOW_NETWORK) -CONDITION_PRIORITY else CONDITION_PRIORITY
    val base = InspectorMockRuleUi(id = ruleId(), priority = priority)
    return when (this) {
        InspectorTestCondition.SLOW_NETWORK ->
            base.copy(
                actionType = InspectorMockActionType.THROTTLE,
                throttleBytesPerSecond = 50_000,
                throttleLatencyMs = 3_000,
            )
        InspectorTestCondition.NO_CONNECTION ->
            base.copy(
                actionType = InspectorMockActionType.CONNECTION_FAILURE,
                failureMessage = "DevConsole test condition: no connection",
            )
        InspectorTestCondition.TIMEOUTS -> base.copy(actionType = InspectorMockActionType.TIMEOUT, timeoutMs = 10_000)
        InspectorTestCondition.SERVER_ERRORS ->
            base.copy(
                statusCode = 500,
                body = "{\"error\":\"DevConsole test condition: server error\"}",
                headers = mapOf("Content-Type" to "application/json"),
            )
        InspectorTestCondition.SIGNED_OUT ->
            base.copy(
                statusCode = 401,
                body = "{\"error\":\"DevConsole test condition: signed out\"}",
                headers = mapOf("Content-Type" to "application/json"),
            )
    }
}

internal fun activeTestCondition(state: InspectorState): InspectorTestCondition? =
    if (state.mocksEnabled) {
        state.mockRules.firstNotNullOfOrNull { rule ->
            if (rule.enabled) testConditionForRuleId(rule.id) else null
        }
    } else {
        null
    }

/** Removes every leftover condition rule, because the web can leave a disabled one behind. */
internal fun testConditionActions(
    current: InspectorTestCondition?,
    next: InspectorTestCondition?,
    mocksEnabled: Boolean,
    existingRuleIds: List<String> = listOfNotNull(current?.toMockRule()?.id),
): List<InspectorAction> =
    buildList {
        val nextId = next?.toMockRule()?.id
        existingRuleIds
            .filter { it.isTestConditionRuleId() && it != nextId }
            .forEach { add(InspectorAction.DeleteMockRule(it)) }
        if (next != null && next != current) {
            if (!mocksEnabled) add(InspectorAction.SetMocksEnabled(true))
            add(InspectorAction.UpsertMockRule(next.toMockRule()))
        }
    }
