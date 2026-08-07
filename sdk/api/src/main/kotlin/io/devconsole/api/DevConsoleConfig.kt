package io.devconsole.api

import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.state.FeatureFlag
import io.devconsole.state.StateProvider

/**
 * Host configuration supplied to [io.devconsole.DevConsole.initialize].
 *
 * Kotlin callers use named arguments; Java callers use [DevConsoleConfig.builder], because Kotlin
 * default arguments do not cross the language boundary and positional construction would break at
 * every call site the moment a field is added.
 *
 * State providers and flags supplied here are registered at initialize. Features that are
 * constructed later can register themselves through
 * [io.devconsole.DevConsole.registerStateProvider].
 */
@Suppress("TooManyFunctions") // Additive policy copy methods preserve the existing constructor ABI.
data class DevConsoleConfig(
    val eventBufferCapacity: Int = DEFAULT_EVENT_BUFFER_CAPACITY,
    val stateProviders: List<StateProvider> = emptyList(),
    val featureFlags: List<FeatureFlag> = emptyList(),
    /**
     * Enables the Composer, which lets an authenticated session make the device issue arbitrary
     * outbound HTTP requests. Off by default because that turns the device into a proxy onto its
     * network; pair with [composerAllowedHosts] to confine it to known backends.
     */
    val composerEnabled: Boolean = false,
    /** Exact hosts the Composer may reach when [composerEnabled]. Empty denies every destination. */
    val composerAllowedHosts: Set<String> = emptySet(),
    /** Enables dashboard-driven state mutations. Off by default; state stays read-only otherwise. */
    val stateMutationsEnabled: Boolean = false,
    /** Redaction applied at every capture boundary and reported to the dashboard. */
    val redactionPolicy: RedactionPolicy = RedactionPolicy.default(),
) {
    /** Additive policy fields live outside the primary constructor to preserve the 1.x JVM ABI. */
    var storagePolicy: StoragePolicy = StoragePolicy()
        private set

    var sessionPolicy: SessionPolicy = SessionPolicy()
        private set

    var editingCapabilities: EditingCapabilities = EditingCapabilities.readOnly()
        private set

    var retentionPolicy: RetentionPolicy = RetentionPolicy()
        private set

    var browserConfig: BrowserConfig = BrowserConfig()
        private set

    /** Uncaught-exception / ANR capture depth. See [CrashPolicy] for the enable gates and caps. */
    var crashPolicy: CrashPolicy = CrashPolicy()
        private set

    /** Foreground-window screenshot capture. See [ScreenshotPolicy] for why it is off by default. */
    var screenshotPolicy: ScreenshotPolicy = ScreenshotPolicy()
        private set

    /**
     * Which capture surfaces are enabled at all. Defaults to every category so existing hosts see no
     * behaviour change. An empty set is legal -- it means "capture nothing" -- and is reported (never
     * thrown) as [ConfigValidationCode.NO_CAPTURE_CATEGORIES_ENABLED] by [validationErrors].
     */
    var captureCategories: Set<CaptureCategory> = CaptureCategory.all()
        private set

    /** Opt-in in-app ways to open the inspector UI. See [OpenTriggers]; everything defaults to off. */
    var openTriggers: OpenTriggers = OpenTriggers()
        private set

    fun withStoragePolicy(value: StoragePolicy): DevConsoleConfig =
        duplicate().also {
            it.storagePolicy = value
            it.retentionPolicy =
                it.retentionPolicy.copy(
                    maxBytes = value.maxBytes,
                    maxAgeMs = value.maxAgeMs,
                )
        }

    fun withSessionPolicy(value: SessionPolicy): DevConsoleConfig = duplicate().also { it.sessionPolicy = value }

    fun withEditingCapabilities(value: EditingCapabilities): DevConsoleConfig =
        duplicate().also {
            it.editingCapabilities =
                value
        }

    fun withRetentionPolicy(value: RetentionPolicy): DevConsoleConfig =
        duplicate().also {
            it.retentionPolicy = value
            it.storagePolicy =
                it.storagePolicy.copy(
                    maxBytes = value.maxBytes,
                    maxAgeMs = value.maxAgeMs,
                )
        }

    fun withBrowserConfig(value: BrowserConfig): DevConsoleConfig = duplicate().also { it.browserConfig = value }

    fun withCrashPolicy(value: CrashPolicy): DevConsoleConfig = duplicate().also { it.crashPolicy = value }

    fun withScreenshotPolicy(value: ScreenshotPolicy): DevConsoleConfig =
        duplicate().also { it.screenshotPolicy = value }

    fun withCaptureCategories(value: Set<CaptureCategory>): DevConsoleConfig =
        duplicate().also { it.captureCategories = value }

    fun withCaptureCategories(vararg values: CaptureCategory): DevConsoleConfig = withCaptureCategories(values.toSet())

    fun withOpenTriggers(value: OpenTriggers): DevConsoleConfig = duplicate().also { it.openTriggers = value }

    fun capturesCategory(category: CaptureCategory): Boolean = category in captureCategories

    /**
     * Aggregates host mistakes without throwing into `Application.onCreate`. The enabled Android
     * facade passes the real debuggable flag; standalone callers default to a debug-safe check.
     */
    @JvmOverloads
    fun validationErrors(isDebuggableRuntime: Boolean = true): List<ConfigValidationError> =
        buildList {
            if (eventBufferCapacity <= 0) {
                add(invalidEventBufferError())
            }
            redactionValidationError()?.let(::add)
            if (!storagePolicy.isValid()) add(invalidStoragePolicyError())
            if (!sessionPolicy.isValid()) add(invalidSessionPolicyError())
            addAll(retentionPolicy.validationErrors())
            addAll(browserConfig.validationErrors())
            addAll(crashPolicy.validationErrors())
            addAll(screenshotPolicy.validationErrors())
            if (captureCategories.isEmpty()) {
                add(noCaptureCategoriesEnabledError())
            }
            if (!isDebuggableRuntime) {
                add(unsafeProductionRuntimeError())
            }
        }

    private fun redactionValidationError(): ConfigValidationError? =
        runCatching { RedactionEngine(redactionPolicy) }
            .exceptionOrNull()
            ?.let { failure ->
                ConfigValidationError(
                    ConfigValidationCode.INVALID_REDACTION_EXPRESSION,
                    "redactionPolicy.textPatterns",
                    failure.message ?: "Invalid redaction expression",
                )
            }

    fun runtimeEquivalentTo(other: DevConsoleConfig): Boolean =
        this == other &&
            storagePolicy == other.storagePolicy &&
            sessionPolicy == other.sessionPolicy &&
            editingCapabilities == other.editingCapabilities &&
            retentionPolicy == other.retentionPolicy &&
            browserConfig == other.browserConfig &&
            crashPolicy == other.crashPolicy &&
            screenshotPolicy == other.screenshotPolicy &&
            captureCategories == other.captureCategories &&
            openTriggers == other.openTriggers

    private fun duplicate(): DevConsoleConfig =
        copy().also {
            it.storagePolicy = storagePolicy
            it.sessionPolicy = sessionPolicy
            it.editingCapabilities = editingCapabilities
            it.retentionPolicy = retentionPolicy
            it.browserConfig = browserConfig
            it.crashPolicy = crashPolicy
            it.screenshotPolicy = screenshotPolicy
            it.captureCategories = captureCategories
            it.openTriggers = openTriggers
        }

    /** Mutable builder for Java callers and for incremental construction. */
    @Suppress("TooManyFunctions") // One setter per config field is the builder's whole job.
    class Builder {
        private var eventBufferCapacity: Int = DEFAULT_EVENT_BUFFER_CAPACITY
        private val stateProviders = mutableListOf<StateProvider>()
        private val featureFlags = mutableListOf<FeatureFlag>()
        private var composerEnabled: Boolean = false
        private var composerAllowedHosts: Set<String> = emptySet()
        private var stateMutationsEnabled: Boolean = false
        private var redactionPolicy: RedactionPolicy = RedactionPolicy.default()
        private var storagePolicy: StoragePolicy = StoragePolicy()
        private var sessionPolicy: SessionPolicy = SessionPolicy()
        private var editingCapabilities = EditingCapabilities.readOnly()
        private var retentionPolicy: RetentionPolicy? = null
        private var browserConfig = BrowserConfig()
        private var crashPolicy = CrashPolicy()
        private var screenshotPolicy = ScreenshotPolicy()
        private var captureCategories: Set<CaptureCategory> = CaptureCategory.all()
        private var openTriggers = OpenTriggers()

        fun eventBufferCapacity(value: Int) = apply { eventBufferCapacity = value }

        fun addStateProvider(value: StateProvider) = apply { stateProviders += value }

        fun addFeatureFlag(value: FeatureFlag) = apply { featureFlags += value }

        fun composerEnabled(value: Boolean) = apply { composerEnabled = value }

        fun composerAllowedHosts(value: Set<String>) = apply { composerAllowedHosts = value }

        fun stateMutationsEnabled(value: Boolean) = apply { stateMutationsEnabled = value }

        fun redactionPolicy(value: RedactionPolicy) = apply { redactionPolicy = value }

        fun storagePolicy(value: StoragePolicy) = apply { storagePolicy = value }

        fun sessionPolicy(value: SessionPolicy) = apply { sessionPolicy = value }

        fun editingCapabilities(value: EditingCapabilities) = apply { editingCapabilities = value }

        fun retentionPolicy(value: RetentionPolicy) = apply { retentionPolicy = value }

        fun browserConfig(value: BrowserConfig) = apply { browserConfig = value }

        fun crashPolicy(value: CrashPolicy) = apply { crashPolicy = value }

        fun screenshotPolicy(value: ScreenshotPolicy) = apply { screenshotPolicy = value }

        fun captureCategories(value: Set<CaptureCategory>) = apply { captureCategories = value }

        fun addCaptureCategory(value: CaptureCategory) = apply { captureCategories = captureCategories + value }

        fun openTriggers(value: OpenTriggers) = apply { openTriggers = value }

        fun build(): DevConsoleConfig {
            val configured =
                DevConsoleConfig(
                    eventBufferCapacity = eventBufferCapacity,
                    stateProviders = stateProviders.toList(),
                    featureFlags = featureFlags.toList(),
                    composerEnabled = composerEnabled,
                    composerAllowedHosts = composerAllowedHosts,
                    stateMutationsEnabled = stateMutationsEnabled,
                    redactionPolicy = redactionPolicy,
                ).withStoragePolicy(storagePolicy)
                    .withSessionPolicy(sessionPolicy)
                    .withEditingCapabilities(editingCapabilities)
                    .withBrowserConfig(browserConfig)
                    .withCrashPolicy(crashPolicy)
                    .withScreenshotPolicy(screenshotPolicy)
                    .withCaptureCategories(captureCategories)
                    .withOpenTriggers(openTriggers)
            return retentionPolicy?.let(configured::withRetentionPolicy) ?: configured
        }
    }

    companion object {
        const val DEFAULT_EVENT_BUFFER_CAPACITY: Int = 256

        @JvmStatic
        fun default(): DevConsoleConfig = DevConsoleConfig()

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

private fun invalidEventBufferError() =
    ConfigValidationError(
        ConfigValidationCode.INVALID_EVENT_BUFFER_CAPACITY,
        "eventBufferCapacity",
        "eventBufferCapacity must be positive",
    )

private fun StoragePolicy.isValid(): Boolean =
    listOf(
        maxBytes,
        maxAgeMs,
        maxTimelineEvents.toLong(),
        maxNetworkTransactions.toLong(),
    ).all { it > 0 }

private fun invalidStoragePolicyError() =
    ConfigValidationError(
        ConfigValidationCode.INVALID_STORAGE_POLICY,
        "storagePolicy",
        "Storage byte, age, and count limits must all be positive",
    )

private fun SessionPolicy.isValid(): Boolean = maxAuthenticatedSessions > 0

private fun invalidSessionPolicyError() =
    ConfigValidationError(
        ConfigValidationCode.INVALID_SESSION_POLICY,
        "sessionPolicy",
        "maxAuthenticatedSessions must be positive",
    )

private fun noCaptureCategoriesEnabledError() =
    ConfigValidationError(
        ConfigValidationCode.NO_CAPTURE_CATEGORIES_ENABLED,
        "captureCategories",
        "No capture categories are enabled; DevConsole will record nothing",
    )

private fun unsafeProductionRuntimeError() =
    ConfigValidationError(
        ConfigValidationCode.UNSAFE_PRODUCTION_RUNTIME,
        "runtime",
        "The full DevConsole runtime cannot initialize in a non-debuggable application",
    )
