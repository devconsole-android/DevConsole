package io.devconsole

import android.app.Application
import android.content.Context
import android.content.Intent
import io.devconsole.api.AccessInfo
import io.devconsole.api.BrowserEndpoint
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.DevConsoleState
import io.devconsole.api.InitResult
import io.devconsole.api.InspectorOpenResult
import io.devconsole.api.ScreenshotResult
import io.devconsole.api.StartRequest
import io.devconsole.api.StartResult
import io.devconsole.api.StopReason
import io.devconsole.logs.LogRecorder
import io.devconsole.mocks.MockEngine
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.push.PushEvent
import io.devconsole.push.PushInput
import io.devconsole.remoteconfig.RemoteConfigProvider
import io.devconsole.socket.SocketRecorder
import io.devconsole.state.StateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object DevConsole {
    private val provider = PlatformFacadeProvider()
    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Prepares the SDK's capture engine, storage, and state/flag registries so network, WebSocket,
     * push, and timeline recording are ready to receive events. This does **not** open a network
     * port or start the browser dashboard -- that only happens on an explicit [startBrowser] /
     * [startBrowserAsync] call.
     *
     * Calling this more than once is only sometimes a no-op, not unconditionally safe -- the exact
     * outcome is one of [InitResult]'s variants, and callers that ignore the return value can miss
     * a real conflict:
     * - An equivalent [config] to the one already active returns [InitResult.ExistingRuntime]
     *   without changing anything.
     * - A debuggable build has already auto-initialized with a **provisional**
     *   [DevConsoleConfig.default] before your own code runs (via its own `ContentProvider`); your
     *   first explicit call with a real [config] *replaces* that provisional one and returns
     *   [InitResult.Initialized]. This is the one case a second call is expected to change
     *   anything.
     * - Two genuinely different **explicit** configs -- your own call after another explicit call
     *   already installed a different [config] -- do **not** merge or replace each other; this
     *   returns [InitResult.Conflict] and the previously active config keeps running.
     *
     * Synchronous and expected to be called from the main thread (typically `Application.onCreate`
     * or an activity's `onCreate`); it does not perform blocking I/O itself. On a **protected**
     * build variant (release, wired to `devconsole-noop`) this is a no-op that always returns
     * [InitResult.Disabled] -- every other [DevConsole] call becomes a no-op too, safely, rather
     * than throwing.
     *
     * @param application required for storage paths, manifest metadata, and lifecycle observation;
     *   the SDK never holds an `Activity` reference.
     * @param config state providers, feature flags, redaction policy, editing capabilities, and
     *   the browser/composer/storage settings for this run. Defaults to [DevConsoleConfig.default].
     * @return an [InitResult] describing what happened -- see its KDoc for what each variant means
     *   and whether it's safe to proceed.
     */
    @JvmStatic
    fun initialize(
        application: Application,
        config: DevConsoleConfig = DevConsoleConfig.default(),
    ): InitResult = provider.initialize(application, config)

    /**
     * Auto-initialization entry point for [DevConsoleInitializer]. The config installed here is
     * provisional: a host that later calls [initialize] with its own state providers or flags
     * replaces it rather than colliding with it. Not part of the public API. The server itself is
     * never auto-started -- hosts call [startBrowser]/[startBrowserAsync] explicitly.
     */
    internal fun initializeProvisionally(application: Application): InitResult =
        provider.initialize(application, DevConsoleConfig.default(), provisional = true)

    @JvmStatic
    fun state(): StateFlow<DevConsoleState> = provider.state()

    /** Creates the SDK-owned inspector intent after initialization; protected builds return null. */
    @JvmStatic
    fun createIntent(context: Context): Intent? = provider.createInspectorIntent(context)

    /**
     * Opens the inspector from the host app's chosen trigger. With
     * [io.devconsole.api.OpenTriggers] the SDK can also open it on a device shake or from a
     * floating button -- both opt-in and off by default. The server is never started by any of
     * these.
     */
    @JvmStatic
    fun open(context: Context): InspectorOpenResult = provider.openInspector(context)

    /**
     * Binds the embedded server and opens the browser dashboard. The SDK never does this on its
     * own -- [initialize] only prepares capture/storage, so nothing is reachable over the network
     * until a host calls this (or [startBrowserAsync]) explicitly, typically from a debug menu
     * action, or unconditionally from `onCreate` if that suits the host.
     *
     * A `suspend` function, safe to call from any dispatcher including `Main` -- the underlying
     * bind loop does blocking socket probes across [StartRequest.portRange] internally, and that
     * work is already moved off the caller's dispatcher, so calling this from
     * `lifecycleScope.launch { }` on the main thread will not ANR.
     *
     * [StartRequest.bindingMode] decides loopback-vs-LAN for *this call*, and no [DevConsoleConfig]
     * value provides a default for it: `DevConsoleConfig.browserConfig.binding` governs only the
     * starts this method is not making -- the in-app inspector's More-screen Start button -- so a
     * host that wants LAN from both surfaces sets both. See the KDoc on
     * [io.devconsole.api.BindingMode] and [io.devconsole.api.BrowserBinding]. The default
     * `StartRequest()` binds loopback, which is the safe choice -- see `docs/THREAT_MODEL.md` at
     * the repository root before ever passing `bindingMode = BindingMode.LAN`.
     *
     * @param request binding mode and port range for this start attempt. Defaults to loopback on
     *   `8080..8099`.
     * @return a [StartResult] -- [StartResult.Started] carries the bound [io.devconsole.api.BrowserEndpoint]
     *   and the session [io.devconsole.api.AccessInfo] (the connect URL/code); every other variant is a
     *   reason it didn't start (see [StartResult]'s KDoc for what each one means and whether it's
     *   retryable).
     */
    @JvmStatic
    suspend fun startBrowser(request: StartRequest = StartRequest()): StartResult = provider.startBrowser(request)

    /** Java-friendly counterpart to [startBrowser]; the [callback] is invoked on a background thread with a sealed result. */
    @JvmStatic
    @JvmOverloads
    fun startBrowserAsync(
        request: StartRequest = StartRequest(),
        callback: DevConsoleCallback<StartResult>,
    ) {
        asyncScope.launch {
            runCatching { provider.startBrowser(request) }
                .onSuccess { callback.onResult(it) }
                .onFailure { callback.onResult(StartResult.Failed(it.message ?: "Unknown error")) }
        }
    }

    @JvmStatic
    suspend fun stop(reason: StopReason = StopReason.UserRequested) {
        provider.stop(reason)
    }

    /**
     * Java-friendly counterpart to [stop]; the [callback] is invoked on a background thread when
     * the stop attempt completes, whether or not it succeeded. Callers that need teardown to run
     * unconditionally (e.g. releasing a foreground service pin) can rely on the callback firing
     * even if [provider]'s stop throws.
     */
    @JvmStatic
    @JvmOverloads
    fun stopAsync(
        reason: StopReason = StopReason.UserRequested,
        callback: DevConsoleCallback<Unit> = DevConsoleCallback {},
    ) {
        asyncScope.launch {
            runCatching { provider.stop(reason) }
            callback.onResult(Unit)
        }
    }

    /**
     * Captures the foreground `Activity`'s window and stores it as an attachment, then mirrors it
     * onto the timeline. Safe to call from any dispatcher -- the capture path hops to the main
     * thread only where the underlying platform APIs require it.
     *
     * Returns [ScreenshotResult.Disabled] without capturing anything when
     * [io.devconsole.api.DevConsoleConfig.screenshotPolicy] is off, which is the default -- a
     * screenshot cannot be redacted, so a host must opt in explicitly. On a **protected** build
     * variant this is a no-op that always returns [ScreenshotResult.DisabledForBuild].
     */
    @JvmStatic
    suspend fun captureScreenshot(): ScreenshotResult = provider.captureScreenshot()

    /** Java-friendly counterpart to [captureScreenshot]; the [callback] is invoked on a background thread with a sealed result. */
    @JvmStatic
    fun captureScreenshotAsync(callback: DevConsoleCallback<ScreenshotResult>) {
        asyncScope.launch {
            runCatching { provider.captureScreenshot() }
                .onSuccess { callback.onResult(it) }
                .onFailure { callback.onResult(ScreenshotResult.Failed(it.message ?: "Unknown error")) }
        }
    }

    /** Registers a state provider after [initialize], for features that are constructed lazily. */
    @JvmStatic
    fun registerStateProvider(provider: StateProvider): Boolean = this.provider.registerStateProvider(provider)

    /**
     * Registers a Remote Config source after [initialize], for a client built lazily through DI.
     * Returns false when the runtime is disabled or the STATE capture category is off.
     */
    @JvmStatic
    fun registerRemoteConfigProvider(provider: RemoteConfigProvider): Boolean =
        this.provider.registerRemoteConfigProvider(provider)

    /** Address of the running server, or null when it is not running. Survives activity recreation. */
    @JvmStatic
    fun endpoint(): BrowserEndpoint? = provider.endpoint()

    /** Session-code credentials for the running server, or null when it is not running. */
    @JvmStatic
    fun accessInfo(): AccessInfo? = provider.accessInfo()

    /** Wrap in a `DevConsoleOkHttpInterceptor` and add it to the host's own `OkHttpClient`. */
    @JvmStatic
    fun networkRecorder(): NetworkTransactionRecorder = provider.networkRecorder()

    /** Wrap in a `DevConsoleOkHttpWebSocketListener` when opening the host's own WebSocket. */
    @JvmStatic
    fun socketRecorder(): SocketRecorder = provider.socketRecorder()

    /** Feed host log lines in so they join the timeline alongside network calls and crashes. */
    @JvmStatic
    fun logRecorder(): LogRecorder = provider.logRecorder()

    /** Wrap in a `DevConsoleMockInterceptor` and add it to the host's own `OkHttpClient`. */
    @JvmStatic
    fun mockEngine(): MockEngine = provider.mockEngine()

    /** Called directly from the host's own push receiver. Never attempts provider delivery itself. */
    @JvmStatic
    fun recordPush(input: PushInput): PushEvent = provider.recordPush(input)

    /** The current effective value for a flag declared in [DevConsoleConfig.featureFlags]. */
    @JvmStatic
    fun featureFlagValue(key: String): Boolean = provider.featureFlagValue(key)

    /**
     * The current effective string value for a flag, for multi-valued flags declared with
     * [io.devconsole.state.FeatureFlag.ofOptions]. Returns `""` for an unknown key.
     */
    @JvmStatic
    fun featureFlagStringValue(key: String): String = provider.featureFlagStringValue(key)
}
