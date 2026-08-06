package io.devconsole.api

data class EditingCapabilities(
    val preferences: Boolean = false,
    val featureFlags: Boolean = false,
    val database: Boolean = false,
    val files: Boolean = false,
    val mocks: Boolean = false,
    val requestExecution: Boolean = false,
    val captureRules: Boolean = false,
) {
    class Builder {
        private var preferences = false
        private var featureFlags = false
        private var database = false
        private var files = false
        private var mocks = false
        private var requestExecution = false
        private var captureRules = false

        fun preferences(value: Boolean) = apply { preferences = value }

        fun featureFlags(value: Boolean) = apply { featureFlags = value }

        fun database(value: Boolean) = apply { database = value }

        fun files(value: Boolean) = apply { files = value }

        fun mocks(value: Boolean) = apply { mocks = value }

        fun requestExecution(value: Boolean) = apply { requestExecution = value }

        fun captureRules(value: Boolean) = apply { captureRules = value }

        fun build(): EditingCapabilities =
            EditingCapabilities(
                preferences = preferences,
                featureFlags = featureFlags,
                database = database,
                files = files,
                mocks = mocks,
                requestExecution = requestExecution,
                captureRules = captureRules,
            )
    }

    companion object {
        @JvmStatic
        fun readOnly(): EditingCapabilities = EditingCapabilities()

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}
