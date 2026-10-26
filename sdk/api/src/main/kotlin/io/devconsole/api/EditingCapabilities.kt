package io.devconsole.api

/**
 * Per-surface write permissions for the dashboard and the in-app inspector.
 *
 * Every capability is enabled by default for this debug-only tool. Use [readOnly] to opt in
 * to a read-only inspector, or disable individual capabilities explicitly.
 *
 * Editing mock rules still requires [CaptureCategory.MOCKS] to be enabled; the two gates are
 * separate and both must hold.
 */
data class EditingCapabilities(
    val preferences: Boolean = true,
    val featureFlags: Boolean = true,
    val database: Boolean = true,
    val files: Boolean = true,
    val mocks: Boolean = true,
    val requestExecution: Boolean = true,
    val captureRules: Boolean = true,
) {
    class Builder {
        private var preferences = true
        private var featureFlags = true
        private var database = true
        private var files = true
        private var mocks = true
        private var requestExecution = true
        private var captureRules = true

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
        /**
         * Opt-in read-only policy: grants no editing capability, including mocks.
         */
        @JvmStatic
        fun readOnly(): EditingCapabilities =
            EditingCapabilities(
                preferences = false,
                featureFlags = false,
                database = false,
                files = false,
                mocks = false,
                requestExecution = false,
                captureRules = false,
            )

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}
