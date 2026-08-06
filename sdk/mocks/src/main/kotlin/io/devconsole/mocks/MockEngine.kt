package io.devconsole.mocks

import com.google.re2j.Pattern
import java.util.concurrent.atomic.AtomicBoolean

data class MockRequest(
    val method: String,
    val scheme: String,
    val host: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    /** Present only when the host has explicitly supplied a safe, repeatable preview. */
    val body: String? = null,
)

enum class MockScope { SESSION, PERSISTENT_INTERNAL, TEST_FIXTURE }

sealed interface MockAction {
    data class StaticResponse(
        val statusCode: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    ) : MockAction

    data class TemplateResponse(
        val statusCode: Int,
        val template: String,
        val headers: Map<String, String> = emptyMap(),
    ) : MockAction

    /** Delays before applying [next], making latency simulations explicit and bounded. */
    data class Delay(
        val durationMs: Long,
        val next: MockAction = Passthrough,
    ) : MockAction {
        init {
            require(durationMs in 0..MAX_DELAY_MS) { "Delay must be between 0 and $MAX_DELAY_MS ms" }
        }
    }

    data class ConnectionFailure(
        val message: String = "DevConsole simulated connection failure",
    ) : MockAction

    data class Timeout(
        val durationMs: Long = DEFAULT_TIMEOUT_MS,
    ) : MockAction {
        init {
            require(durationMs in 0..MAX_DELAY_MS) { "Timeout must be between 0 and $MAX_DELAY_MS ms" }
        }
    }

    data class StatusOverride(
        val statusCode: Int,
    ) : MockAction

    data class BodyReplacement(
        val body: String,
    ) : MockAction

    data object Passthrough : MockAction

    private companion object {
        const val MAX_DELAY_MS = 30_000L
        const val DEFAULT_TIMEOUT_MS = 1_000L
    }
}

data class MockRule(
    val id: String,
    val priority: Int,
    val method: String? = null,
    val scheme: String? = null,
    val host: String? = null,
    val path: String = ".*",
    val queryPredicates: Map<String, String> = emptyMap(),
    val headerPredicates: Map<String, String> = emptyMap(),
    val bodyPredicate: String? = null,
    val scope: MockScope = MockScope.SESSION,
    val action: MockAction = MockAction.Passthrough,
    /**
     * The original transaction's response body, captured only by the "mock this response" flows
     * (Compose and the web dashboard) so the served mock can later be diffed against it. Session-only:
     * it is never written to any disk persistence of rules -- [toDurableRules] strips it before a rule
     * reaches a [MockRuleStore], and hand-written rules never set it in the first place.
     */
    val sourceBodySnapshot: String? = null,
) {
    var persistence: MockRulePersistence = MockRulePersistence()
        private set

    fun withPersistence(persistence: MockRulePersistence): MockRule = copy().also { it.persistence = persistence }
}

data class MockRulePersistence(
    val enabled: Boolean = true,
    val createdAppVersion: String? = null,
    val installationId: String? = null,
    val compatibleAppVersions: Set<String> = emptySet(),
    val compatibleAcrossReinstall: Boolean = false,
)

interface MockRuleStore {
    fun load(): List<MockRule>

    fun save(rules: List<MockRule>)
}

class InMemoryMockRuleStore(
    initialRules: List<MockRule> = emptyList(),
    private val maxRules: Int = 1_000,
) : MockRuleStore {
    private var rules = initialRules.take(maxRules)

    init {
        require(maxRules > 0) { "maxRules must be positive" }
    }

    @Synchronized
    override fun load(): List<MockRule> = rules.toList()

    @Synchronized
    override fun save(rules: List<MockRule>) {
        this.rules = rules.take(maxRules)
    }
}

enum class MockPassthroughReason { DISABLED, INPUT_LIMIT, NO_MATCH, EVALUATION_ERROR }

sealed interface MockOutcome {
    data class Matched(
        val ruleId: String,
        val actionType: String,
    ) : MockOutcome

    data class Passthrough(
        val reason: MockPassthroughReason,
    ) : MockOutcome

    data class EvaluationError(
        val ruleId: String?,
        val message: String,
    ) : MockOutcome
}

fun interface MockOutcomeSink {
    fun record(outcome: MockOutcome)
}

sealed interface MockDecision {
    data class Matched(
        val rule: MockRule,
        val action: MockAction,
    ) : MockDecision

    data object Passthrough : MockDecision
}

/** How often a rule has fired, and when it last did -- surfaced next to the rule in every UI. */
data class MockRuleStats(
    val hitCount: Long = 0,
    val lastHitEpochMs: Long? = null,
)

class MockEngine(
    initialRules: List<MockRule>,
    enabled: Boolean = true,
    private val bodyMatchingEnabled: Boolean = false,
    private val maxBodyPredicateBytes: Int = DEFAULT_MAX_BODY_PREDICATE_BYTES,
) {
    private val rules = initialRules.toMutableList()
    private val compiledRules = initialRules.associate { it.id to it.compile() }.toMutableMap()
    private val stats = mutableMapOf<String, MockRuleStats>()
    private val enabled = AtomicBoolean(enabled)

    @Volatile
    private var outcomeSink: MockOutcomeSink = MockOutcomeSink {}
    private var persistenceBinding: PersistenceBinding? = null

    init {
        require(maxBodyPredicateBytes >= 0) { "maxBodyPredicateBytes must not be negative" }
    }

    fun isEnabled(): Boolean = enabled.get()

    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    fun withOutcomeSink(sink: MockOutcomeSink): MockEngine =
        apply {
            outcomeSink = sink
        }

    /**
     * Restores durable rules and makes subsequent CRUD synchronous with [store]. A failed save is
     * reported to the caller before the active in-memory rule set changes.
     */
    @Synchronized
    fun bindPersistence(
        store: MockRuleStore,
        currentAppVersion: String,
        installationId: String,
    ) {
        require(currentAppVersion.isNotBlank()) { "currentAppVersion must not be blank" }
        require(installationId.isNotBlank()) { "installationId must not be blank" }
        persistenceBinding = null
        restore(store, currentAppVersion, installationId)
        persistenceBinding = PersistenceBinding(store, currentAppVersion, installationId)
    }

    @Synchronized
    fun restore(
        store: MockRuleStore,
        currentAppVersion: String,
        installationId: String,
    ) {
        store.load().forEach { stored ->
            if (stored.scope == MockScope.SESSION) return@forEach
            val metadata = stored.persistence
            val sameVersion = metadata.createdAppVersion == currentAppVersion
            val versionCompatible = currentAppVersion in metadata.compatibleAppVersions
            val sameInstall = metadata.installationId == installationId
            val installCompatible = sameInstall || metadata.compatibleAcrossReinstall
            val restored =
                stored.withPersistence(
                    metadata.copy(
                        enabled = metadata.enabled && installCompatible && (sameVersion || versionCompatible),
                    ),
                )
            runCatching { upsert(restored) }
                .onFailure { emit(MockOutcome.EvaluationError(stored.id, it.message ?: it.javaClass.simpleName)) }
        }
    }

    @Synchronized
    fun persist(
        store: MockRuleStore,
        currentAppVersion: String,
        installationId: String,
    ) {
        store.save(rules.toDurableRules(currentAppVersion, installationId))
    }

    @Synchronized fun clearSessionRules() {
        val removedIds = rules.filter { it.scope == MockScope.SESSION }.map(MockRule::id).toSet()
        rules.removeAll { it.id in removedIds }
        compiledRules.keys.removeAll(removedIds)
        // Same contract as remove(): a rule recreated under a cleared id must start at 0 hits —
        // an inherited count from the previous session would be a fabricated number.
        stats.keys.removeAll(removedIds)
    }

    @Synchronized fun rules(): List<MockRule> = rules.toList()

    /** Hit stats for one rule; [MockRuleStats.hitCount] is 0 (never fabricated) if it has never matched. */
    @Synchronized fun stats(id: String): MockRuleStats = stats[id] ?: MockRuleStats()

    /** Every rule's hit stats, keyed by rule id -- unmatched rules are simply absent, not zero-filled. */
    @Synchronized fun statsSnapshot(): Map<String, MockRuleStats> = stats.toMap()

    @Synchronized fun upsert(rule: MockRule) {
        val compiled = rule.compile()
        val persistedRule = persistenceBinding?.prepare(rule) ?: rule
        val index = rules.indexOfFirst { it.id == persistedRule.id }
        val updated = rules.toMutableList()
        if (index >= 0) updated[index] = persistedRule else updated += persistedRule
        persistenceBinding?.save(updated)
        if (index >= 0) rules[index] = persistedRule else rules += persistedRule
        compiledRules[persistedRule.id] = compiled
    }

    /**
     * Enables/disables one rule without losing it, writing through [persistenceBinding] exactly
     * like [upsert]. Matching already honors [MockRule.persistence]'s `enabled` flag in [decide],
     * so this is the only mutation a rule-level toggle needs. Returns false when [id] is unknown.
     */
    @Synchronized
    fun setEnabled(
        id: String,
        enabled: Boolean,
    ): Boolean {
        val rule = rules.firstOrNull { it.id == id } ?: return false
        upsert(rule.withPersistence(rule.persistence.copy(enabled = enabled)))
        return true
    }

    @Synchronized
    fun remove(id: String): Boolean {
        if (rules.none { it.id == id }) return false
        val updated = rules.filterNot { it.id == id }
        persistenceBinding?.save(updated)
        compiledRules.remove(id)
        stats.remove(id)
        return rules.removeAll { it.id == id }
    }

    @Synchronized
    fun conflicts(): List<Pair<MockRule, MockRule>> {
        if (!enabled.get()) return emptyList()
        val active = rules.filter { it.persistence.enabled }
        val result = mutableListOf<Pair<MockRule, MockRule>>()
        for (i in active.indices) {
            for (j in i + 1 until active.size) {
                if (active[i].mayOverlapWith(active[j])) result += active[i] to active[j]
            }
        }
        return result
    }

    private fun MockRule.mayOverlapWith(other: MockRule): Boolean =
        (method == null || other.method == null || method.equals(other.method, true)) &&
            (scheme == null || other.scheme == null || scheme.equals(other.scheme, true)) &&
            (host == null || other.host == null || host.equals(other.host, true)) &&
            (path == other.path || path == DEFAULT_PATH || other.path == DEFAULT_PATH)

    fun decide(request: MockRequest): MockDecision {
        if (!enabled.get()) return passthrough(MockPassthroughReason.DISABLED)
        if (request.path.length > MAX_PATH_INPUT_CHARS) return passthrough(MockPassthroughReason.INPUT_LIMIT)
        val active =
            synchronized(this) {
                rules
                    .filter { it.persistence.enabled }
                    .mapNotNull { rule -> compiledRules[rule.id]?.let { rule to it } }
            }
        return try {
            val rule =
                active
                    .withIndex()
                    .filter { (_, item) -> item.first.matches(item.second, request) }
                    .sortedWith(
                        compareByDescending<IndexedValue<Pair<MockRule, CompiledRule>>> {
                            it.value.first.priority
                        }.thenByDescending { it.value.first.specificity() }.thenBy { it.index },
                    ).firstOrNull()
                    ?.value
                    ?.first ?: return passthrough(MockPassthroughReason.NO_MATCH)
            when (val resolution = rule.action.resolve(request)) {
                is ActionResolution.Resolved -> {
                    emit(MockOutcome.Matched(rule.id, resolution.action.javaClass.simpleName))
                    recordHit(rule.id)
                    MockDecision.Matched(rule, resolution.action)
                }
                is ActionResolution.Invalid -> {
                    emit(MockOutcome.EvaluationError(rule.id, resolution.message))
                    passthrough(MockPassthroughReason.EVALUATION_ERROR, emitOutcome = false)
                }
            }
        } catch (error: Throwable) {
            emit(MockOutcome.EvaluationError(null, error.message ?: error.javaClass.simpleName))
            passthrough(MockPassthroughReason.EVALUATION_ERROR, emitOutcome = false)
        }
    }

    private fun MockRule.matches(
        compiled: CompiledRule,
        request: MockRequest,
    ): Boolean =
        (method == null || method.equals(request.method, true)) &&
            (scheme == null || scheme.equals(request.scheme, true)) &&
            (host == null || host.equals(request.host, true)) &&
            compiled.path.matcher(request.path).matches() &&
            queryPredicates.all { (key, value) -> request.query[key] == value } &&
            headerPredicates.all { (key, value) ->
                request.headers.entries
                    .firstOrNull { it.key.equals(key, true) }
                    ?.value == value
            } &&
            bodyMatches(compiled.body, request.body)

    private fun bodyMatches(
        predicate: Pattern?,
        body: String?,
    ): Boolean {
        if (predicate == null) return true
        if (!bodyMatchingEnabled || body == null || body.toByteArray().size > maxBodyPredicateBytes) return false
        return predicate.matcher(body).matches()
    }

    private fun MockRule.specificity(): Int =
        listOfNotNull(method, scheme, host, bodyPredicate).size * 100 + path.count { it.isLetterOrDigit() } +
            queryPredicates.size * 10 +
            headerPredicates.size * 10

    private fun MockAction.resolve(request: MockRequest): ActionResolution =
        when (this) {
            is MockAction.TemplateResponse ->
                render(template, request)
                    ?.let { ActionResolution.Resolved(MockAction.StaticResponse(statusCode, it, headers)) }
                    ?: ActionResolution.Invalid("Template contains an unknown or unresolved placeholder")
            is MockAction.Delay ->
                when (val nested = next.resolve(request)) {
                    is ActionResolution.Resolved -> ActionResolution.Resolved(copy(next = nested.action))
                    is ActionResolution.Invalid -> nested
                }
            else -> ActionResolution.Resolved(this)
        }

    private fun passthrough(
        reason: MockPassthroughReason,
        emitOutcome: Boolean = true,
    ): MockDecision.Passthrough {
        if (emitOutcome) emit(MockOutcome.Passthrough(reason))
        return MockDecision.Passthrough
    }

    private fun emit(outcome: MockOutcome) {
        runCatching { outcomeSink.record(outcome) }
    }

    @Synchronized
    private fun recordHit(ruleId: String) {
        val previous = stats[ruleId] ?: MockRuleStats()
        stats[ruleId] = previous.copy(hitCount = previous.hitCount + 1, lastHitEpochMs = System.currentTimeMillis())
    }

    private fun render(
        template: String,
        request: MockRequest,
    ): String? {
        var invalid = false
        val rendered =
            PLACEHOLDER.replace(template) { match ->
                val namespace = match.groupValues[1]
                val key = match.groupValues[2].takeIf(String::isNotEmpty)
                val value =
                    when (namespace) {
                        "method" -> request.method.takeIf { key == null }
                        "scheme" -> request.scheme.takeIf { key == null }
                        "host" -> request.host.takeIf { key == null }
                        "path" -> request.path.takeIf { key == null }
                        "body" -> request.body.takeIf { key == null }
                        "query" -> key?.let(request.query::get)
                        "header" ->
                            key?.let { name ->
                                request.headers.entries
                                    .firstOrNull { it.key.equals(name, true) }
                                    ?.value
                            }
                        else -> null
                    }
                if (value == null) {
                    invalid = true
                    match.value
                } else {
                    value
                }
            }
        return rendered.takeUnless { invalid || PLACEHOLDER.containsMatchIn(it) }
    }

    private companion object {
        const val DEFAULT_MAX_BODY_PREDICATE_BYTES = 8 * 1024
        const val DEFAULT_PATH = ".*"
        private const val MAX_PATH_INPUT_CHARS = 8 * 1024
        private const val MAX_PATTERN_CHARS = 1_024

        // Every literal brace must be escaped, including the closing `}}`: unlike desktop
        // java.util.regex (lenient about a bare `}`), Android's ICU-backed regex engine throws
        // PatternSyntaxException at class-init time for an unescaped literal `}`, crashing the
        // app on first touch of MockEngine. Unit tests run on the host JVM and won't catch this.
        val PLACEHOLDER = Regex("\\{\\{([a-z]+)(?:\\.([A-Za-z0-9_-]+))?\\}\\}")
    }

    private fun MockRule.compile(): CompiledRule {
        require(path.length <= MAX_PATTERN_CHARS) { "Mock path regex is too large" }
        require(bodyPredicate == null || bodyPredicate.length <= MAX_PATTERN_CHARS) { "Mock body regex is too large" }
        return CompiledRule(
            path = Pattern.compile(path),
            body = bodyPredicate?.let(Pattern::compile),
        )
    }

    private data class CompiledRule(
        val path: Pattern,
        val body: Pattern?,
    )

    private data class PersistenceBinding(
        val store: MockRuleStore,
        val currentAppVersion: String,
        val installationId: String,
    ) {
        fun prepare(rule: MockRule): MockRule =
            if (rule.scope == MockScope.SESSION) {
                rule
            } else {
                rule.withPersistence(
                    rule.persistence.copy(
                        createdAppVersion = rule.persistence.createdAppVersion ?: currentAppVersion,
                        installationId = rule.persistence.installationId ?: installationId,
                    ),
                )
            }

        fun save(rules: List<MockRule>) {
            store.save(rules.toDurableRules(currentAppVersion, installationId))
        }
    }

    private sealed interface ActionResolution {
        data class Resolved(
            val action: MockAction,
        ) : ActionResolution

        data class Invalid(
            val message: String,
        ) : ActionResolution
    }
}

private fun List<MockRule>.toDurableRules(
    currentAppVersion: String,
    installationId: String,
): List<MockRule> =
    filter { it.scope != MockScope.SESSION }
        .map { rule ->
            // sourceBodySnapshot is session-only and must never reach a MockRuleStore's disk copy.
            rule
                .copy(sourceBodySnapshot = null)
                .withPersistence(
                    rule.persistence.copy(
                        createdAppVersion = rule.persistence.createdAppVersion ?: currentAppVersion,
                        installationId = rule.persistence.installationId ?: installationId,
                    ),
                )
        }
