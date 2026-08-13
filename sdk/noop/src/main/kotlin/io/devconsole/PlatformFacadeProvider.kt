package io.devconsole

import android.app.Application
import android.content.Context
import android.content.Intent
import io.devconsole.api.AccessInfo
import io.devconsole.api.BrowserEndpoint
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.DevConsoleFacadeProvider
import io.devconsole.api.DevConsoleState
import io.devconsole.api.InitResult
import io.devconsole.api.InspectorOpenResult
import io.devconsole.api.ScreenshotResult
import io.devconsole.api.StartRequest
import io.devconsole.api.StartResult
import io.devconsole.api.StopReason
import io.devconsole.logs.LogRecorder
import io.devconsole.logs.LogSink
import io.devconsole.mocks.MockEngine
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.push.InMemoryPushStore
import io.devconsole.push.PushEvent
import io.devconsole.push.PushInput
import io.devconsole.push.PushRecorder
import io.devconsole.remoteconfig.RemoteConfigProvider
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.SocketRecorder
import io.devconsole.state.FeatureFlag
import io.devconsole.state.StateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

/**
 * Every recorder here is constructed with `enabled = false`, so no method ever redacts,
 * serializes, or stores a payload — satisfying FR-BUILD-004 for a disabled build while still
 * matching [DevConsoleFacadeProvider]'s method signatures exactly.
 */
internal class PlatformFacadeProvider : DevConsoleFacadeProvider {
    private val runtimeState = MutableStateFlow<DevConsoleState>(DevConsoleState.DisabledForBuild)
    private val redaction by lazy { RedactionEngine(RedactionPolicy.default()) }

    private val networkRecorderInstance by lazy {
        NetworkTransactionRecorder(
            NetworkCaptureFactory(redaction),
            InMemoryNetworkTransactionStore(NetworkCursorCodec(randomCursorSecret())),
            enabled = false,
        )
    }
    private val socketRecorderInstance by lazy { SocketRecorder(redaction, InMemorySocketStore(), enabled = false) }
    private val pushRecorderInstance by lazy { PushRecorder(redaction, InMemoryPushStore(), enabled = false) }
    private val mockEngineInstance by lazy { MockEngine(emptyList(), enabled = false) }
    private val logRecorderInstance by lazy { LogRecorder(redaction, LogSink { }, enabled = false) }

    /** Retained only so [featureFlagValue] can answer with the host's declared default. */
    @Volatile private var flags: List<FeatureFlag> = emptyList()

    override fun initialize(
        application: Application,
        config: DevConsoleConfig,
    ): InitResult {
        flags = config.featureFlags
        return InitResult.Disabled
    }

    /** Signature parity with the enabled build's auto-initialization path; nothing here to supersede. */
    @Suppress("UNUSED_PARAMETER")
    internal fun initialize(
        application: Application,
        config: DevConsoleConfig,
        provisional: Boolean,
    ): InitResult = initialize(application, config)

    override fun state(): StateFlow<DevConsoleState> = runtimeState

    override fun createInspectorIntent(context: Context): Intent? = null

    override fun openInspector(context: Context): InspectorOpenResult = InspectorOpenResult.DisabledForBuild

    override suspend fun startBrowser(request: StartRequest): StartResult = StartResult.DisabledForBuild

    override suspend fun stop(reason: StopReason) = Unit

    override suspend fun captureScreenshot(): ScreenshotResult = ScreenshotResult.DisabledForBuild

    override fun registerStateProvider(provider: StateProvider): Boolean = false

    override fun registerRemoteConfigProvider(provider: RemoteConfigProvider): Boolean = false

    override fun endpoint(): BrowserEndpoint? = null

    override fun accessInfo(): AccessInfo? = null

    override fun networkRecorder(): NetworkTransactionRecorder = networkRecorderInstance

    override fun socketRecorder(): SocketRecorder = socketRecorderInstance

    override fun logRecorder(): LogRecorder = logRecorderInstance

    override fun mockEngine(): MockEngine = mockEngineInstance

    override fun recordPush(input: PushInput): PushEvent = pushRecorderInstance.record(input)

    override fun featureFlagValue(key: String): Boolean = declaredDefault(key).toBoolean()

    override fun featureFlagStringValue(key: String): String = declaredDefault(key)

    private fun declaredDefault(key: String): String = flags.firstOrNull { it.key == key }?.defaultValue.orEmpty()
}

private const val CURSOR_SECRET_BYTES = 16

private fun randomCursorSecret(): ByteArray = ByteArray(CURSOR_SECRET_BYTES).also(SecureRandom()::nextBytes)
