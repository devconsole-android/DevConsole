/**
 * @author Shakib
 * @since 02/08/26
 */
package io.devconsole

import io.devconsole.api.CaptureRule
import io.devconsole.api.CaptureRuleEngine
import io.devconsole.api.CaptureRuleStore
import io.devconsole.storage.room.CaptureRuleDao
import io.devconsole.storage.room.CaptureRuleEntity

/**
 * Durable capture-exclusion storage on the shared DevConsole Room database.
 *
 * The DAO is resolved through a provider rather than captured once so a database replaced by
 * corruption recovery is picked up on the next call. Reads never throw: a rule row that no longer
 * validates (older schema, hand-edited database) is skipped rather than making every rule
 * unavailable. Writes deliberately propagate so the caller learns a rule was not persisted.
 */
internal class RoomCaptureRuleStore(
    private val dao: () -> CaptureRuleDao,
) : CaptureRuleStore {
    override fun load(): List<CaptureRule> =
        runCatching { dao().rules() }
            .getOrDefault(emptyList())
            .mapNotNull { entity -> runCatching { entity.toCaptureRule() }.getOrNull() }
            .take(CaptureRuleEngine.MAX_RULES)

    override fun save(rules: List<CaptureRule>) {
        require(rules.size <= CaptureRuleEngine.MAX_RULES) {
            "At most ${CaptureRuleEngine.MAX_RULES} capture rules may be persisted"
        }
        require(rules.map(CaptureRule::id).distinct().size == rules.size) { "Capture rule ids must be unique" }
        dao().replaceAll(rules.mapIndexed { index, rule -> rule.toEntity(index) })
    }
}

private fun CaptureRuleEntity.toCaptureRule(): CaptureRule =
    CaptureRule(
        id = id,
        host = host,
        method = method,
        pathPrefix = pathPrefix,
        enabled = enabled,
    )

private fun CaptureRule.toEntity(position: Int): CaptureRuleEntity =
    CaptureRuleEntity(
        id,
        host,
        method,
        pathPrefix,
        enabled,
        position,
    )
