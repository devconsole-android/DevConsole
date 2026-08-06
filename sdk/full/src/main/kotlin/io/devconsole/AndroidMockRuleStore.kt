package io.devconsole

import android.content.Context
import android.content.SharedPreferences
import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockRule
import io.devconsole.mocks.MockRulePersistence
import io.devconsole.mocks.MockRuleStore
import io.devconsole.mocks.MockScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * App-private, synchronous storage for persistent mock rules. SharedPreferences gives each update
 * an atomic file replacement; [MockEngine] writes before changing active state.
 */
internal class AndroidMockRuleStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) : MockRuleStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun load(): List<MockRule> {
        val encoded = preferences.getString(RULES_KEY, null) ?: return emptyList()
        if (encoded.encodeToByteArray().size > MAX_ENCODED_BYTES) return quarantineCorruptRules()
        return runCatching {
            val array = JSONArray(encoded)
            require(array.length() <= MAX_RULES) { "Too many persisted mock rules" }
            List(array.length()) { index -> array.getJSONObject(index).toMockRule() }
                .also { rules ->
                    require(rules.map(MockRule::id).distinct().size == rules.size) {
                        "Persisted mock rule ids must be unique"
                    }
                }
        }.getOrElse { quarantineCorruptRules() }
    }

    override fun save(rules: List<MockRule>) {
        require(rules.size <= MAX_RULES) { "At most $MAX_RULES mock rules may be persisted" }
        require(rules.map(MockRule::id).distinct().size == rules.size) { "Mock rule ids must be unique" }
        val encoded =
            JSONArray()
                .apply { rules.forEach { put(it.toJson()) } }
                .toString()
        require(encoded.encodeToByteArray().size <= MAX_ENCODED_BYTES) {
            "Persisted mock rules exceed $MAX_ENCODED_BYTES bytes"
        }
        check(
            preferences
                .edit()
                .putString(RULES_KEY, encoded)
                .remove(CORRUPT_KEY)
                .commit(),
        ) {
            "Unable to persist mock rules"
        }
    }

    fun installationId(): String =
        synchronized(preferences) {
            preferences.getString(INSTALLATION_ID_KEY, null)
                ?: UUID
                    .randomUUID()
                    .toString()
                    .also { generated ->
                        check(preferences.edit().putString(INSTALLATION_ID_KEY, generated).commit()) {
                            "Unable to persist DevConsole installation id"
                        }
                    }
        }

    private fun quarantineCorruptRules(): List<MockRule> {
        preferences
            .edit()
            .remove(RULES_KEY)
            .putBoolean(CORRUPT_KEY, true)
            .commit()
        return emptyList()
    }

    private companion object {
        const val PREFERENCES_NAME = "io.devconsole.mock_rules"
        const val RULES_KEY = "rules"
        const val CORRUPT_KEY = "rules_corrupt"
        const val INSTALLATION_ID_KEY = "installation_id"
        const val MAX_RULES = 1_000
        const val MAX_ENCODED_BYTES = 1024 * 1024
        const val MAX_ACTION_DEPTH = 8
    }

    // Deliberately omits MockRule.sourceBodySnapshot: it is session-only (MockEngine already strips
    // it before a rule reaches MockRuleStore.save), and even if a caller bypassed that and handed
    // this store a rule that still carried one, it must not gain a second, independent disk copy here.
    private fun MockRule.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("priority", priority)
            .putNullable("method", method)
            .putNullable("scheme", scheme)
            .putNullable("host", host)
            .put("path", path)
            .put("queryPredicates", queryPredicates.toJson())
            .put("headerPredicates", headerPredicates.toJson())
            .putNullable("bodyPredicate", bodyPredicate)
            .put("scope", scope.name)
            .put("action", action.toJson())
            .put(
                "persistence",
                JSONObject()
                    .put("enabled", persistence.enabled)
                    .putNullable("createdAppVersion", persistence.createdAppVersion)
                    .putNullable("installationId", persistence.installationId)
                    .put("compatibleAppVersions", JSONArray(persistence.compatibleAppVersions.sorted()))
                    .put("compatibleAcrossReinstall", persistence.compatibleAcrossReinstall),
            )

    private fun JSONObject.toMockRule(): MockRule {
        val persistenceJson = getJSONObject("persistence")
        val persistence =
            MockRulePersistence(
                enabled = persistenceJson.getBoolean("enabled"),
                createdAppVersion = persistenceJson.nullableString("createdAppVersion"),
                installationId = persistenceJson.nullableString("installationId"),
                compatibleAppVersions =
                    persistenceJson
                        .getJSONArray("compatibleAppVersions")
                        .toStringSet(),
                compatibleAcrossReinstall = persistenceJson.getBoolean("compatibleAcrossReinstall"),
            )
        return MockRule(
            id = getString("id"),
            priority = getInt("priority"),
            method = nullableString("method"),
            scheme = nullableString("scheme"),
            host = nullableString("host"),
            path = getString("path"),
            queryPredicates = getJSONObject("queryPredicates").toStringMap(),
            headerPredicates = getJSONObject("headerPredicates").toStringMap(),
            bodyPredicate = nullableString("bodyPredicate"),
            scope = MockScope.valueOf(getString("scope")),
            action = getJSONObject("action").toMockAction(),
        ).withPersistence(persistence)
    }

    private fun MockAction.toJson(depth: Int = 0): JSONObject {
        require(depth <= MAX_ACTION_DEPTH) { "Mock action nesting is too deep" }
        return when (this) {
            is MockAction.StaticResponse ->
                JSONObject()
                    .put("type", "static")
                    .put("statusCode", statusCode)
                    .put("body", body)
                    .put("headers", headers.toJson())
            is MockAction.TemplateResponse ->
                JSONObject()
                    .put("type", "template")
                    .put("statusCode", statusCode)
                    .put("template", template)
                    .put("headers", headers.toJson())
            is MockAction.Delay ->
                JSONObject()
                    .put("type", "delay")
                    .put("durationMs", durationMs)
                    .put("next", next.toJson(depth + 1))
            is MockAction.ConnectionFailure ->
                JSONObject()
                    .put("type", "connection_failure")
                    .put("message", message)
            is MockAction.Timeout ->
                JSONObject()
                    .put("type", "timeout")
                    .put("durationMs", durationMs)
            is MockAction.StatusOverride ->
                JSONObject()
                    .put("type", "status_override")
                    .put("statusCode", statusCode)
            is MockAction.BodyReplacement ->
                JSONObject()
                    .put("type", "body_replacement")
                    .put("body", body)
            MockAction.Passthrough -> JSONObject().put("type", "passthrough")
        }
    }

    private fun JSONObject.toMockAction(depth: Int = 0): MockAction {
        require(depth <= MAX_ACTION_DEPTH) { "Persisted mock action nesting is too deep" }
        return when (getString("type")) {
            "static" ->
                MockAction.StaticResponse(
                    getInt("statusCode"),
                    getString("body"),
                    getJSONObject("headers").toStringMap(),
                )
            "template" ->
                MockAction.TemplateResponse(
                    getInt("statusCode"),
                    getString("template"),
                    getJSONObject("headers").toStringMap(),
                )
            "delay" -> MockAction.Delay(getLong("durationMs"), getJSONObject("next").toMockAction(depth + 1))
            "connection_failure" -> MockAction.ConnectionFailure(getString("message"))
            "timeout" -> MockAction.Timeout(getLong("durationMs"))
            "status_override" -> MockAction.StatusOverride(getInt("statusCode"))
            "body_replacement" -> MockAction.BodyReplacement(getString("body"))
            "passthrough" -> MockAction.Passthrough
            else -> error("Unknown persisted mock action")
        }
    }
}

private fun Map<String, String>.toJson(): JSONObject =
    JSONObject().apply {
        forEach { (name, value) -> put(name, value) }
    }

private fun JSONObject.toStringMap(): Map<String, String> =
    buildMap {
        keys().forEach { name -> put(name, getString(name)) }
    }

private fun JSONArray.toStringSet(): Set<String> =
    buildSet {
        repeat(length()) { index -> add(getString(index)) }
    }

private fun JSONObject.putNullable(
    name: String,
    value: String?,
): JSONObject = put(name, value ?: JSONObject.NULL)

private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)
