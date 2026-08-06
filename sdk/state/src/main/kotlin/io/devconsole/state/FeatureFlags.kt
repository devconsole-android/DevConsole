package io.devconsole.state

import java.util.concurrent.ConcurrentHashMap

enum class FeatureFlagType { BOOLEAN, STRING }

/**
 * A host-declared flag. Values are carried as strings so a flag can offer a choice between named
 * options — a backend environment, an account tier — rather than only on or off. Booleans remain
 * first-class through [ofBoolean] and [SessionFeatureFlags.booleanValue].
 */
data class FeatureFlag(
    val key: String,
    val defaultValue: String,
    val allowedValues: Set<String>,
    val type: FeatureFlagType = FeatureFlagType.STRING,
    val description: String = "",
    val mutable: Boolean = true,
    val source: String = "application",
) {
    init {
        require(key.isNotBlank()) { "Feature flag key must not be blank" }
        require(source.isNotBlank()) { "Feature flag source must not be blank" }
        require(allowedValues.isNotEmpty()) { "Feature flag must allow at least one value" }
        require(defaultValue in allowedValues) { "Feature flag default must be in allowedValues" }
    }

    /** Convenience constructor for the common on/off flag. */
    constructor(
        key: String,
        defaultValue: Boolean,
        description: String = "",
        mutable: Boolean = true,
        source: String = "application",
    ) : this(key, defaultValue.toString(), BOOLEAN_VALUES, FeatureFlagType.BOOLEAN, description, mutable, source)

    companion object {
        val BOOLEAN_VALUES: Set<String> = setOf("true", "false")

        @JvmStatic
        @JvmOverloads
        fun ofBoolean(
            key: String,
            defaultValue: Boolean,
            description: String = "",
        ): FeatureFlag = FeatureFlag(key, defaultValue, description)

        /**
         * A flag the dashboard renders as a picker — the shape a staging/production environment
         * switcher needs.
         */
        @JvmStatic
        @JvmOverloads
        fun ofOptions(
            key: String,
            defaultValue: String,
            options: Set<String>,
            description: String = "",
        ): FeatureFlag = FeatureFlag(key, defaultValue, options, FeatureFlagType.STRING, description)
    }
}

/** A host-facing flag contract: session overrides never mutate the provider's default. */
interface FeatureFlagProvider {
    fun flags(): List<FeatureFlag>

    fun value(key: String): String

    /** Sugar for a [FeatureFlagType.BOOLEAN] flag; anything not literally "true" reads as false. */
    fun booleanValue(key: String): Boolean

    /** Flags whose value currently differs from the host's declared default, keyed by flag name. */
    fun overrides(): Map<String, String>

    fun override(
        key: String,
        value: String,
    )

    fun reset()
}

class SessionFeatureFlags(
    private val definitions: List<FeatureFlag>,
) : FeatureFlagProvider {
    // override() is called from the dashboard's Ktor thread while value()/booleanValue()/overrides()
    // are read from arbitrary host threads; a plain mutableMapOf() here would let a concurrent write
    // corrupt an in-progress read/iteration (ConcurrentModificationException at best, silent map
    // corruption at worst). ConcurrentHashMap makes every individual operation thread-safe without
    // requiring callers to hold a lock.
    private val overrides = ConcurrentHashMap<String, String>()

    init {
        require(definitions.map(FeatureFlag::key).distinct().size == definitions.size) { "Duplicate feature flag key" }
    }

    override fun override(
        key: String,
        value: String,
    ) {
        val definition =
            definitions.firstOrNull { it.key == key && it.mutable }
                ?: error("Unknown or immutable feature flag: $key")
        require(value in definition.allowedValues) { "Value is not allowed for feature flag: $key" }
        overrides[key] = value
    }

    /** An unregistered [key] reads as the empty-string/false default rather than throwing. */
    override fun value(key: String): String =
        overrides[key] ?: definitions.firstOrNull { it.key == key }?.defaultValue ?: ""

    override fun booleanValue(key: String): Boolean = value(key).toBoolean()

    override fun flags(): List<FeatureFlag> = definitions

    override fun overrides(): Map<String, String> = overrides.toMap()

    override fun reset() {
        overrides.clear()
    }
}
