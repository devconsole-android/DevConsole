package io.devconsole.core

sealed interface RuntimeGate {
    fun evaluate(): Decision

    data object Enabled : RuntimeGate {
        override fun evaluate(): Decision = Decision.Enabled
    }

    data class Disabled(
        private val reason: String,
    ) : RuntimeGate {
        override fun evaluate(): Decision = Decision.Disabled(reason)
    }

    sealed interface Decision {
        data object Enabled : Decision

        data class Disabled(
            val reason: String,
        ) : Decision
    }
}
