package io.devconsole.api

import android.app.Application
import android.content.Context
import android.content.Intent
import io.devconsole.logs.LogRecorder
import io.devconsole.mocks.MockEngine
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.push.PushEvent
import io.devconsole.push.PushInput
import io.devconsole.socket.SocketRecorder
import io.devconsole.state.StateProvider
import kotlinx.coroutines.flow.StateFlow

interface DevConsoleFacadeProvider {
    fun initialize(
        application: Application,
        config: DevConsoleConfig,
    ): InitResult

    fun state(): StateFlow<DevConsoleState>

    fun createInspectorIntent(context: Context): Intent?

    fun openInspector(context: Context): InspectorOpenResult

    suspend fun startBrowser(request: StartRequest): StartResult

    suspend fun stop(reason: StopReason)

    /**
     * Captures the foreground `Activity`'s window and stores it as an attachment, then mirrors it
     * onto the timeline. See [ScreenshotResult] for every way this can conclude, including
     * [ScreenshotResult.Disabled] when [DevConsoleConfig.screenshotPolicy] is off -- which is the
     * default, since a screenshot cannot be redacted.
     */
    suspend fun captureScreenshot(): ScreenshotResult

    /**
     * Registers a state provider after [initialize]. Modular apps discover features lazily through
     * DI, so requiring every state provider at construction time was not workable. Returns false if
     * the runtime is disabled.
     */
    fun registerStateProvider(provider: StateProvider): Boolean

    /**
     * The endpoint of the running server, or null when it is not running. [DevConsoleState.Running]
     * carries no payload, so without this a host that loses its [StartResult.Started] -- to an
     * activity recreation, say -- had no way to recover the address.
     */
    fun endpoint(): BrowserEndpoint?

    /** Session-code credentials for the running server, or null when it is not running. */
    fun accessInfo(): AccessInfo?

    /** Wrap in a `DevConsoleOkHttpInterceptor` and add it to the host's own `OkHttpClient`. */
    fun networkRecorder(): NetworkTransactionRecorder

    /** Wrap in a `DevConsoleOkHttpWebSocketListener` when opening the host's own WebSocket. */
    fun socketRecorder(): SocketRecorder

    /** Feed host log lines in so they join the timeline alongside network calls and crashes. */
    fun logRecorder(): LogRecorder

    /** Wrap in a `DevConsoleMockInterceptor` and add it to the host's own `OkHttpClient`. */
    fun mockEngine(): MockEngine

    /** Called directly from the host's own push receiver. Never attempts provider delivery itself. */
    fun recordPush(input: PushInput): PushEvent

    /**
     * The current effective value for a flag declared in [DevConsoleConfig.featureFlags],
     * reflecting any dashboard override. Falls back to `false` for an unknown key.
     */
    fun featureFlagValue(key: String): Boolean

    /**
     * The current effective string value for a flag, reflecting any dashboard override. This is the
     * accessor for multi-valued flags declared with [io.devconsole.state.FeatureFlag.ofOptions]
     * (a boolean flag returns `"true"`/`"false"`). Falls back to `""` for an unknown key.
     */
    fun featureFlagStringValue(key: String): String
}
