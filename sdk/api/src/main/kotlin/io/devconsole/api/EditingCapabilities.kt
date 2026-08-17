package io.devconsole.api

/**
 * Per-surface write permissions for the dashboard and the in-app inspector.
 *
 * Everything defaults to off except [mocks]. A mock rule writes nothing that belongs to the host --
 * it only short-circuits DevConsole's own OkHttp interceptor -- so the read-only posture that
 * protects preferences, databases, and files has nothing to protect here, while a Mocks screen that
 * cannot add a mock is the most common "is this thing broken?" first run. Pass `mocks = false`, or
 * use [readOnly], to take it back.
 *
 * Editing mock rules still requires [CaptureCategory.MOCKS] to be enabled; the two gates are
 * separate and both must hold.
 */
data class EditingCapabilities(
    val preferences: Boolean = false,
    val featureFlags: Boolean = false,
    val database: Boolean = false,
    val files: Boolean = false,
    val mocks: Boolean = true,
    val requestExecution: Boolean = false,
    val captureRules: Boolean = false,
) {
    class Builder {
        private var preferences = false
        private var featureFlags = false
        private var database = false
        private var files = false
        private var mocks = true
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
        /**
         * Grants nothing at all, [mocks] included. Spelled out rather than delegating to the no-arg
         * constructor, which now enables mocks -- a host that asks for read-only must get exactly
         * that, whatever the defaults drift to later.
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
