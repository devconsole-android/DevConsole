package io.devconsole.state

sealed interface StateValue {
    data object Null : StateValue

    data class BooleanValue(
        val value: Boolean,
    ) : StateValue

    data class NumberValue(
        val value: Number,
    ) : StateValue

    data class StringValue(
        val value: String,
    ) : StateValue

    data class ObjectValue(
        val values: Map<String, StateValue>,
    ) : StateValue

    data class ArrayValue(
        val values: List<StateValue>,
    ) : StateValue

    data object Redacted : StateValue

    data class Unavailable(
        val reason: String? = null,
    ) : StateValue

    data class BinaryMetadata(
        val byteLength: Long,
        val mediaType: String? = null,
    ) : StateValue
}

data class StateSnapshot(
    val values: Map<String, StateValue>,
)

sealed interface StateMutationResult {
    data class Success(
        val snapshot: StateSnapshot,
    ) : StateMutationResult

    data class Rejected(
        val reason: String,
    ) : StateMutationResult
}

/** Explicit host command; the SDK never reflects over arbitrary application objects. */
class StateMutator(
    val id: String,
    val inputSchema: String = "{\"type\":\"object\"}",
    private val mutate: (String) -> StateMutationResult,
) {
    init {
        require(id.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*"))) { "Invalid state mutator id" }
    }

    fun execute(input: String): StateMutationResult = mutate(input)
}

interface StateProvider {
    val id: String

    fun snapshot(): StateSnapshot

    /** Read-only remains the default; a host must opt in each individual mutator. */
    val mutators: List<StateMutator> get() = emptyList()
}

fun stateProvider(
    id: String,
    snapshot: () -> StateSnapshot,
): StateProvider =
    object : StateProvider {
        override val id: String = id

        override fun snapshot(): StateSnapshot = snapshot()
    }

class StateRegistry {
    private val providers = linkedMapOf<String, StateProvider>()

    fun register(provider: StateProvider) {
        require(provider.id.isNotBlank()) { "State provider id must not be blank" }
        require(provider.id !in providers) { "Duplicate state provider: ${provider.id}" }
        providers[provider.id] = provider
    }

    fun snapshot(id: String): StateSnapshot? = providers[id]?.snapshot()

    fun providerIds(): List<String> = providers.keys.toList()

    /**
     * The mutation catalogue for a provider -- empty (not null) for an unknown id or a read-only
     * provider. Public because `sdk:server-ktor` (a separate Gradle module, so `internal` here would
     * not be reachable from it) reads this to serialize the dashboard's provider-detail and
     * mutator-catalogue responses; hosts supply mutators and never read this back themselves.
     * Returns a fresh copy rather than [StateProvider.mutators]'s own list instance: that property's
     * default is `emptyList()`, but nothing stops an implementation from backing it with a live
     * `MutableList`, and hosts can only reach [StateProvider] through this SDK's public `state`
     * artifact -- so a caller who upcasts the result would otherwise get a live handle into the
     * provider's own mutable state instead of a snapshot of its catalogue.
     */
    fun mutators(id: String): List<StateMutator> = providers[id]?.mutators?.toList().orEmpty()

    fun mutate(
        providerId: String,
        mutatorId: String,
        input: String,
    ): StateMutationResult? = providers[providerId]?.mutators?.firstOrNull { it.id == mutatorId }?.execute(input)
}
