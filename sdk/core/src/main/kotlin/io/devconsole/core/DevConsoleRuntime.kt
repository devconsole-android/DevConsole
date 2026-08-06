package io.devconsole.core

import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.DevConsoleState
import io.devconsole.api.InitResult
import io.devconsole.api.StartResult
import io.devconsole.api.StopReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DevConsoleRuntime(
    private val runtimeGate: RuntimeGate,
) {
    private val sessionManager = SessionManager()
    private val mutableState = MutableStateFlow<DevConsoleState>(DevConsoleState.Uninitialized)
    private val mutableHealth = MutableStateFlow(SdkHealth())
    private var config: DevConsoleConfig? = null
    private var session: SessionSnapshot? = null

    /** True when [config] came from auto-initialization and an explicit host config may replace it. */
    private var configIsProvisional = false

    val state: StateFlow<DevConsoleState> = mutableState.asStateFlow()
    val health: StateFlow<SdkHealth> = mutableHealth.asStateFlow()

    /** The one app-run identifier shared by platform capture and durable storage. */
    @Synchronized
    fun currentSessionId(): String? = session?.id?.toString()

    /**
     * [provisional] marks a config installed by auto-initialization rather than by the host. Such a
     * config is replaced by the first explicit [initialize] instead of conflicting with it.
     */
    @JvmOverloads
    @Synchronized
    fun initialize(
        requestedConfig: DevConsoleConfig,
        provisional: Boolean = false,
    ): InitResult =
        when (runtimeGate.evaluate()) {
            is RuntimeGate.Decision.Disabled -> {
                transitionTo(DevConsoleState.DisabledForBuild)
                InitResult.Disabled
            }

            RuntimeGate.Decision.Enabled -> initializeEnabled(requestedConfig, provisional)
        }

    suspend fun start(): StartResult =
        when (state.value) {
            DevConsoleState.DisabledForBuild -> StartResult.DisabledForBuild
            DevConsoleState.Uninitialized, DevConsoleState.Stopped -> StartResult.NotInitialized
            is DevConsoleState.Failed -> StartResult.Failed("Runtime is failed")
            else -> StartResult.ServerUnavailable
        }

    /**
     * Reserves the runtime lifecycle for a platform server start.  The core module deliberately
     * does not know about a concrete server implementation; platform facades complete this
     * transition after their loopback engine has started.
     */
    @Synchronized
    fun beginServerStart(): StartResult? =
        when (state.value) {
            DevConsoleState.Initialized -> {
                transitionTo(DevConsoleState.Starting)
                null
            }

            // A previous start that ended in PermissionRequired or Failed must be retryable: the
            // host may have since granted the local-network permission, and auto-start falls back
            // from LAN to loopback by simply starting again. Blocking a retry here stranded the
            // runtime with no way forward short of re-initialize().
            DevConsoleState.PermissionRequired,
            is DevConsoleState.Failed,
            -> {
                transitionTo(DevConsoleState.Starting)
                null
            }

            // A stopped runtime that was configured can start again: stop() is a lifecycle
            // operation, not a teardown, and requiring re-initialize() to restart was a trap.
            DevConsoleState.Stopped ->
                if (config != null) {
                    // A stopped runtime is a completed app run. Captures after a later start must
                    // never be attributed to that finished run.
                    session = sessionManager.create()
                    transitionTo(DevConsoleState.Starting)
                    null
                } else {
                    StartResult.NotInitialized
                }

            DevConsoleState.DisabledForBuild -> StartResult.DisabledForBuild
            DevConsoleState.Uninitialized -> StartResult.NotInitialized
            else -> StartResult.ServerUnavailable
        }

    @Synchronized
    fun serverStarted() {
        if (state.value == DevConsoleState.Starting) transitionTo(DevConsoleState.Running)
    }

    @Synchronized
    fun serverRequiresPermission() {
        if (state.value == DevConsoleState.Starting) transitionTo(DevConsoleState.PermissionRequired)
    }

    @Synchronized
    fun serverFailed(message: String) {
        if (state.value == DevConsoleState.Starting) transitionTo(DevConsoleState.Failed(message))
    }

    @Synchronized
    fun stop(reason: StopReason) {
        if (state.value == DevConsoleState.Stopped || state.value == DevConsoleState.Uninitialized) return

        transitionTo(DevConsoleState.Stopping)
        session = session?.let { sessionManager.stop(it, reason) }
        transitionTo(DevConsoleState.Stopped)
    }

    private fun initializeEnabled(
        requestedConfig: DevConsoleConfig,
        provisional: Boolean,
    ): InitResult {
        val existingConfig = config
        return when {
            existingConfig == null || state.value == DevConsoleState.Stopped -> adopt(requestedConfig, provisional)
            existingConfig.runtimeEquivalentTo(requestedConfig) -> InitResult.ExistingRuntime
            // A provisional config was installed by auto-initialization from a ContentProvider,
            // before the host's Application.onCreate ran. An explicit host config must replace it,
            // or the host's state providers and flags are silently dropped. Two explicit
            // configs still conflict (FR-CORE-001).
            configIsProvisional && !provisional -> adopt(requestedConfig, provisional)
            else -> InitResult.Conflict("DevConsole is already initialized with a different configuration")
        }
    }

    private fun adopt(
        requestedConfig: DevConsoleConfig,
        provisional: Boolean,
    ): InitResult {
        val activeState = mutableState.value.takeIf { it == DevConsoleState.Starting || it == DevConsoleState.Running }
        config = requestedConfig
        configIsProvisional = provisional
        if (session == null || mutableState.value == DevConsoleState.Stopped) session = sessionManager.create()
        transitionTo(activeState ?: DevConsoleState.Initialized, initializationIncrement = true)
        return InitResult.Initialized
    }

    private fun transitionTo(
        nextState: DevConsoleState,
        initializationIncrement: Boolean = false,
    ) {
        mutableState.value = nextState
        mutableHealth.value =
            mutableHealth.value.copy(
                initializationCount = mutableHealth.value.initializationCount + if (initializationIncrement) 1 else 0,
                state = nextState,
            )
    }
}
