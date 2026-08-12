/**
 * @author Shakib
 * @since 20/07/26
 */
@file:Suppress("FunctionNaming")

package io.devconsole.sample.compose

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.devconsole.DevConsole
import io.devconsole.api.BindingMode
import io.devconsole.api.BrowserBinding
import io.devconsole.api.BrowserConfig
import io.devconsole.api.BrowserEndpoint
import io.devconsole.api.CrashPolicy
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.DevConsoleState
import io.devconsole.api.EditingCapabilities
import io.devconsole.api.InspectorOpenResult
import io.devconsole.api.OpenTriggers
import io.devconsole.api.ScreenshotPolicy
import io.devconsole.api.ScreenshotResult
import io.devconsole.api.ShakeIntensity
import io.devconsole.api.StartRequest
import io.devconsole.api.StartResult
import io.devconsole.api.StopReason
import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockRule
import io.devconsole.mocks.okhttp.DevConsoleMockInterceptor
import io.devconsole.network.okhttp.installDevConsole
import io.devconsole.push.PushInput
import io.devconsole.socket.okhttp.DevConsoleOkHttpWebSocketListener
import io.devconsole.socket.okhttp.DevConsoleRecordingWebSocket
import io.devconsole.socket.paho.DevConsolePahoMqtt
import io.devconsole.state.FeatureFlag
import io.devconsole.state.StateSnapshot
import io.devconsole.state.StateValue
import io.devconsole.state.stateProvider
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val SHOW_ORDER_HISTORY_FLAG = "compose_sample.show_order_history"
private const val MOCK_RULE_ID = "compose-sample-orders"
private val requestCount = AtomicInteger(0)

/**
 * Shortened from [CrashPolicy]'s 5s default so the ANR hazard button below trips the watchdog in a
 * demo-friendly amount of time. Only one sample needs to show this override -- foundation-app and
 * views-java-app demonstrate the default threshold instead.
 */
private const val DEMO_ANR_THRESHOLD_MS = 2_000L

/** Comfortably past [DEMO_ANR_THRESHOLD_MS] so the block reliably trips the watchdog. */
private const val ANR_BLOCK_MS = 3_500L

/** Public test broker for the sample MQTT connection -- see docs/MQTT_CAPTURE.md. */
private const val MQTT_BROKER_URI = "tcp://broker.hivemq.com:1883"
private const val MQTT_DEMO_TOPIC_FILTER = "devconsole/demo/#"
private const val MQTT_DEMO_TOPIC = "devconsole/demo/hello"
private const val MQTT_DEMO_QOS = 1
private const val MQTT_CONNECT_TIMEOUT_SECONDS = 10

/** DevConsole's terminal-green identity carried into the sample so the two read as one product. */
private val DevConsoleColors =
    darkColorScheme(
        primary = Color(0xFFB7ED65),
        onPrimary = Color(0xFF0B0E0D),
        primaryContainer = Color(0xFF29371C),
        onPrimaryContainer = Color(0xFFD3F0A8),
        secondary = Color(0xFF9DB08F),
        onSecondary = Color(0xFF0B0E0D),
        background = Color(0xFF0B0E0D),
        onBackground = Color(0xFFE7E5D8),
        surface = Color(0xFF151A18),
        onSurface = Color(0xFFE7E5D8),
        surfaceVariant = Color(0xFF1B231E),
        onSurfaceVariant = Color(0xFF92968E),
        error = Color(0xFFFF715E),
        outline = Color(0xFF344039),
    )

private val Amber = Color(0xFFF0B45C)
private val Signal = Color(0xFFB7ED65)
private val Error = Color(0xFFFF715E)
private val Idle = Color(0xFF6B726A)

/**
 * Local reimplementation of `io.devconsole.ui.compose.DevConsoleComposeStatus.forState` -- kept
 * inline here (rather than depending on `sdk:ui-compose`) so this file has no `ui-compose` import
 * and stays release-compile-safe now that `ui-compose` is `debugImplementation`-only (it has no
 * release no-op counterpart; see build.gradle.kts).
 */
private fun devConsoleStatusText(
    state: DevConsoleState,
    endpoint: BrowserEndpoint?,
): String =
    when (state) {
        DevConsoleState.Uninitialized -> "DevConsole is not initialized"
        DevConsoleState.DisabledForBuild -> "DevConsole is disabled for this build"
        DevConsoleState.Initialized, DevConsoleState.Stopped -> "DevConsole is ready"
        DevConsoleState.PermissionRequired -> "Local network permission is required"
        DevConsoleState.Starting -> "Starting DevConsole"
        DevConsoleState.Running ->
            if (endpoint != null) {
                "DevConsole server is running at ${endpoint.host}:${endpoint.port}"
            } else {
                "DevConsole server is running"
            }
        DevConsoleState.Stopping -> "Stopping DevConsole"
        is DevConsoleState.Failed -> "DevConsole failed: ${state.message}"
    }

/**
 * Compose launcher sample exercising every SDK capability: network + socket capture, mocks (with
 * enable/disable), push simulation, a feature flag, a state provider, and every
 * [io.devconsole.api.EditingCapabilities] flag unlocked so the Data rail (preferences/database/
 * files) is fully editable. Seeds its own SharedPreferences, a SQLite table, and a couple of files
 * on launch so those inspectors always have real content. The application, rather than the SDK,
 * owns the server lifecycle and builds its own launch surface on top of [DevConsole.state], but can
 * also drop into the SDK's own in-app inspector (More screen QR, Data rail, exports) via
 * [DevConsole.open].
 */
class MainActivity : ComponentActivity() {
    private val socketClient = OkHttpClient()

    /** Owns the sample MQTT connection's lifecycle so [onDestroy] can always disconnect it, even mid-demo. */
    private var mqttClient: MqttAsyncClient? = null
    private val instrumentedClient by lazy {
        OkHttpClient
            .Builder()
            // Wires the event listener and interceptor together so the Network inspector's timing
            // bar (DNS/connect/TLS/send/wait/receive) is actually populated -- see the kdoc on
            // installDevConsole for why the equivalent three-step manual form is easy to get wrong.
            .installDevConsole(DevConsole.networkRecorder())
            .addInterceptor(DevConsoleMockInterceptor(DevConsole.mockEngine()))
            .build()
    }

    /** Built from the variant source sets: instrumented in debug, a plain client in release. */
    private val ktorClient by lazy { buildSampleKtorClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DevConsole.initialize(application, buildConfig())
        // Mock rules are SESSION-scoped and dropped whenever the embedded server restarts (fresh
        // session), so the rule is (re)installed from the Running-state observer below rather than
        // once here -- a one-shot install here would silently stop mocking after any restart.
        seedSampleData(applicationContext)

        setContent {
            MaterialTheme(colorScheme = DevConsoleColors) {
                val scope = rememberCoroutineScope()
                SampleScreen(scope)
            }
        }
    }

    /** Disconnects the sample MQTT client, if one was opened, so it never outlives this Activity. */
    override fun onDestroy() {
        mqttClient?.let { client ->
            runCatching { client.disconnect().waitForCompletion() }
            runCatching { client.close() }
        }
        mqttClient = null
        super.onDestroy()
    }

    /**
     * Capability-flag matrix across the three samples (see each MainActivity's own comment for
     * the rest of the row): compose-app is the full showcase, so every [EditingCapabilities] flag
     * is unlocked -- the Data rail (preferences/database/files), mocks, capture rules, and the
     * request composer are all live and editable from the browser and the in-app inspector -- and
     * the two server-side gates that sit outside that enum, `composerEnabled` (with a host
     * allowlist) and `stateMutationsEnabled`, are opted into as well.
     * foundation-app leaves every flag at its `false` default to demonstrate the locked-down,
     * read-only posture a production-adjacent debug build might ship with. views-java-app sits in
     * between: mocks and capture rules are editable, but preferences/database/files stay read-only.
     * [OpenTriggers] follows the same gradient: this sample opts into both shake-to-open and the
     * floating button, views-java-app enables shake only, and foundation-app leaves both off.
     *
     * Screenshot capture is off by default across the whole SDK -- [ScreenshotPolicy.enabled]
     * defaults to `false` because a screenshot can't be redacted. This sample is the one that opts
     * in, via [ScreenshotPolicy], so its "Capture screenshot" button renders a real
     * [ScreenshotResult.Captured]; foundation-app leaves the default alone and shows the refusal
     * verbatim instead -- seeing both side by side is the fastest way to understand the gate.
     * [CrashPolicy.anrThresholdMs] is also shortened here (see [DEMO_ANR_THRESHOLD_MS]) purely so
     * the hazard-zone ANR button below trips the watchdog quickly during a demo.
     */
    private fun buildConfig() =
        DevConsoleConfig(
            stateProviders =
                listOf(
                    stateProvider("compose-sample") {
                        StateSnapshot(mapOf("requestsSent" to StateValue.NumberValue(requestCount.get())))
                    },
                ),
            featureFlags =
                listOf(
                    FeatureFlag(
                        key = SHOW_ORDER_HISTORY_FLAG,
                        defaultValue = false,
                        description = "Shows the order-history placeholder in the sample UI",
                    ),
                ),
            // The Composer makes the device issue outbound requests, so it stays off unless the
            // host opts in. This showcase opts in, but confines it to the hosts the sample itself
            // talks to -- an empty allowlist denies every destination, so naming them is required.
            composerEnabled = true,
            composerAllowedHosts = setOf("jsonplaceholder.typicode.com", "example.test", "postman-echo.com"),
            // Lets the dashboard drive registered state mutations; state is read-only otherwise.
            stateMutationsEnabled = true,
        ).withBrowserConfig(
            // Governs the in-app inspector's own More-screen Start button, which issues no
            // StartRequest of its own -- without this it would bind loopback while the sample's
            // own Start button binds LAN, so the same app would hand out two different connect
            // URLs depending on where you pressed start. LAN carries the exposure spelled out at
            // that call site and in docs/THREAT_MODEL.md.
            BrowserConfig(binding = BrowserBinding.LAN),
        ).withEditingCapabilities(
            EditingCapabilities
                .builder()
                .preferences(true)
                .database(true)
                .files(true)
                .mocks(true)
                .captureRules(true)
                .featureFlags(true)
                .requestExecution(true)
                .build(),
        ).withScreenshotPolicy(ScreenshotPolicy(enabled = true))
            .withCrashPolicy(CrashPolicy(anrThresholdMs = DEMO_ANR_THRESHOLD_MS))
            .withOpenTriggers(
                OpenTriggers(shakeToOpen = true, shakeIntensity = ShakeIntensity.MEDIUM, floatingButton = true),
            )
    // Feature 2 demo (capture-category selection at init): uncommenting the line below scopes
    // capture down to only SOCKET + MQTT. Every other category -- NETWORK, PUSH, LOGS, CRASHES,
    // STATE, INSPECTION, MOCKS -- is then hidden (not merely emptied out) and disabled in both
    // this in-app panel and the browser dashboard. Left commented out so this sample keeps its
    // default, unmodified behaviour: CaptureCategory.all(), every category enabled.
    // .withCaptureCategories(CaptureCategory.SOCKET, CaptureCategory.MQTT)

    private fun installMockRule() {
        DevConsole.mockEngine().upsert(
            MockRule(
                id = MOCK_RULE_ID,
                priority = 0,
                path = "/orders",
                action = MockAction.StaticResponse(statusCode = 200, body = "{\"mocked\":true,\"orders\":[]}"),
            ),
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SampleScreen(scope: CoroutineScope) {
        val context = LocalContext.current
        val state by DevConsole.state().collectAsStateWithLifecycle()
        var showOrderHistory by remember { mutableStateOf(DevConsole.featureFlagValue(SHOW_ORDER_HISTORY_FLAG)) }
        var lastResponse by remember { mutableStateOf<String?>(null) }
        var endpoint by remember { mutableStateOf(DevConsole.endpoint()) }
        var connectUrl by remember { mutableStateOf(DevConsole.accessInfo()?.connectUrl) }
        var mockRuleEnabled by remember { mutableStateOf(true) }

        LaunchedEffect(state) {
            if (state is DevConsoleState.Running) {
                endpoint = DevConsole.endpoint()
                // Re-seed on every Running transition (first start AND every restart) -- see the
                // comment in onCreate for why a one-shot install at init isn't enough.
                installMockRule()
                // Poll while running: a browser exchange consumes the displayed code and the SDK
                // immediately mints a fresh one -- a one-shot read here would keep advertising the
                // dead URL.
                while (true) {
                    connectUrl = DevConsole.accessInfo()?.connectUrl
                    kotlinx.coroutines.delay(CONNECT_URL_POLL_MS)
                }
            } else if (state is DevConsoleState.Stopped || state is DevConsoleState.Failed) {
                endpoint = null
                connectUrl = null
            }
        }

        fun applyStarted(result: StartResult) {
            val started = result as? StartResult.Started ?: return
            endpoint = started.endpoint
            connectUrl = started.access.connectUrl
        }

        val permissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    // This branch only runs after a LAN start requested ACCESS_LOCAL_NETWORK below; loopback
                    // never reaches it. Kept on LAN to retry the mode the host actually asked for.
                    scope.launch { applyStarted(DevConsole.startBrowser(StartRequest(bindingMode = BindingMode.LAN))) }
                } else {
                    lastResponse = "Local network permission was denied"
                }
            }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { BrandTitle() },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                )
            },
        ) { innerPadding ->
            Column(
                modifier =
                    Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ServerStatusCard(
                    status =
                        ServerStatus(
                            state = state,
                            endpoint = endpoint,
                            connectUrl = connectUrl,
                        ),
                    onStart = {
                        scope.launch {
                            // LAN so the connect URL carries the device's own address and a browser on
                            // the same network can reach it without `adb forward`. That exposure is real:
                            // the dashboard speaks plaintext HTTP, so anyone who can see these packets
                            // reads every captured header, token and body. Swap to LOOPBACK below on any
                            // network you do not control. See docs/THREAT_MODEL.md.
                            when (
                                val result =
                                    DevConsole.startBrowser(StartRequest(bindingMode = BindingMode.LAN))
                                // val result =
                                //     DevConsole.startBrowser(StartRequest(bindingMode = BindingMode.LOOPBACK))
                            ) {
                                is StartResult.Started -> applyStarted(result)
                                is StartResult.PermissionRequired -> permissionLauncher.launch(result.permission)
                                else -> lastResponse = "Could not start: ${result::class.simpleName}"
                            }
                        }
                    },
                    onStop = {
                        scope.launch {
                            DevConsole.stop(StopReason.UserRequested)
                            endpoint = null
                            connectUrl = null
                        }
                    },
                )

                // exportSessionZip() has no direct DevConsole facade method -- it only exists
                // inside the SDK's own InspectorViewModel, which the browser routes and this
                // in-app inspector both share. Opening the real inspector is how this sample
                // exercises that flow end to end: its More tab's Export section is backed by
                // the same AndroidInspectorExporter the browser's /api/v1/exports uses.
                FullInspectorCard(
                    onOpen = {
                        lastResponse =
                            if (DevConsole.open(context) is InspectorOpenResult.Opened) {
                                "Opened the in-app inspector"
                            } else {
                                "Could not open the in-app inspector"
                            }
                    },
                )

                SectionLabel("Exercise the SDK")
                CapabilityCard(
                    title = "Send network request",
                    subtitle = "OkHttp interceptor -- chunked response, body captured via the tee",
                    onClick = {
                        scope.launch {
                            lastResponse =
                                sendRequest("https://jsonplaceholder.typicode.com/todos/1", "Network response")
                            showOrderHistory = DevConsole.featureFlagValue(SHOW_ORDER_HISTORY_FLAG)
                        }
                    },
                )
                CapabilityCard(
                    title = "Send Ktor request",
                    subtitle = "Ktor plugin on the CIO engine -- request and response bodies, no OkHttp involved",
                    onClick = {
                        scope.launch {
                            lastResponse = sendKtorRequest("https://jsonplaceholder.typicode.com/posts/1")
                        }
                    },
                )
                CapabilityCard(
                    title = "Send mocked request",
                    subtitle = "Served by a session mock rule, not the network",
                    onClick = {
                        scope.launch { lastResponse = sendRequest("https://example.test/orders", "Mocked response") }
                    },
                )
                CapabilityCard(
                    title = "Open sample WebSocket",
                    subtitle = "Frames recorded on the WebSocket inspector",
                    onClick = {
                        val socket =
                            socketClient.newWebSocket(
                                Request.Builder().url("wss://ws.postman-echo.com/raw").build(),
                                DevConsoleOkHttpWebSocketListener(DevConsole.socketRecorder()),
                            )
                        // Wrap the returned socket so an outbound frame is captured too -- the raw
                        // socket from newWebSocket() only records inbound events via the listener.
                        DevConsoleRecordingWebSocket
                            .wrap(socket, DevConsole.socketRecorder())
                            .send("Hello from the compose sample")
                        lastResponse = "WebSocket connecting…"
                    },
                )
                CapabilityCard(
                    title = "Open sample MQTT connection",
                    subtitle = "A separate MQTT capture category -- frames recorded on the same Sockets inspector",
                    onClick = {
                        scope.launch {
                            lastResponse = "Connecting to the MQTT broker…"
                            lastResponse = openSampleMqttConnection()
                        }
                    },
                )
                CapabilityCard(
                    title = "Simulate push",
                    subtitle = "Recorded on the push timeline",
                    onClick = { lastResponse = simulatePush() },
                )
                CapabilityCard(
                    title = "Capture screenshot",
                    subtitle = "ScreenshotPolicy.enabled = true here -- foundation-app shows the default-off refusal",
                    onClick = { scope.launch { lastResponse = captureScreenshot() } },
                )
                CapabilityCard(
                    title = "Toggle /orders mock rule",
                    subtitle = "Enables/disables it without deleting it -- same call the dashboard's switch makes",
                    onClick = {
                        mockRuleEnabled = !mockRuleEnabled
                        DevConsole.mockEngine().setEnabled(MOCK_RULE_ID, mockRuleEnabled)
                        lastResponse = "Mock rule toggled"
                    },
                )
                ResultCard(lastResponse)
                if (showOrderHistory) FeatureFlagCard()

                SectionLabel("Hazard zone — destructive")
                HazardNotice()
                HazardCard(
                    title = "Trigger crash",
                    subtitle = "Throws an uncaught exception -- kills the process immediately",
                    onClick = { error("compose-app sample: deliberate uncaught crash") },
                )
                HazardCard(
                    title = "Trigger ANR",
                    subtitle =
                        "Blocks the main thread for ${ANR_BLOCK_MS}ms -- anrThresholdMs is shortened to " +
                            "${DEMO_ANR_THRESHOLD_MS}ms in this sample's CrashPolicy so the watchdog trips quickly",
                    onClick = { Thread.sleep(ANR_BLOCK_MS) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    /**
     * Issues one request through the sample's instrumented OkHttp client.
     *
     * The response body is read here rather than discarded, for the same reason the Ktor call below
     * reads its own: reading it is what the card demonstrates. These endpoints answer without a
     * `Content-Length` (gzipped, chunked), so the interceptor captures the body through its tee as
     * this call consumes it -- the same bytes, counted once. Abandoning the body would still be
     * captured (the tee drains what is left on close), but a sample that shows the body it received
     * is the honest demonstration.
     */
    private suspend fun sendRequest(
        url: String,
        successLabel: String,
    ): String =
        withContext(Dispatchers.IO) {
            requestCount.incrementAndGet()
            try {
                val request = Request.Builder().url(url).build()
                instrumentedClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    "$successLabel: ${response.code} (${body.length} chars)"
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // error.message can be null (e.g. some IOExceptions), which would otherwise leave
                // LAST RESULT rendering nothing under its label -- always fall back to a class name.
                "Request failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }

    /**
     * Issues one request through the sample's Ktor client, whose debug build installs
     * `DevConsoleKtorClientPlugin` (see `SampleKtorClient` in `src/debug`).
     *
     * The response body is read here rather than discarded, because reading it is the point: the
     * plugin splits the response channel, so the bytes this call receives and the bytes the Network
     * inspector shows come from the same stream, and neither read costs the other anything. On the
     * release variant the identical call runs against a plain client and records nothing.
     */
    private suspend fun sendKtorRequest(url: String): String =
        withContext(Dispatchers.IO) {
            requestCount.incrementAndGet()
            try {
                val body = ktorClient.get(url).bodyAsText()
                "Ktor response: ${body.length} chars"
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                "Ktor request failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }

    /**
     * Opens (or reopens) the sample MQTT connection: installs the Paho adapter -- real capture in
     * debug, a no-op in release, same call either way -- connects, subscribes to
     * [MQTT_DEMO_TOPIC_FILTER], and publishes one message to [MQTT_DEMO_TOPIC]. MQTT is a separate
     * [io.devconsole.api.CaptureCategory] from the WebSocket capability above, but both land on the
     * same Sockets inspector, tagged by protocol. All of this runs off the main thread; a dead or
     * unreachable public broker must never crash this sample, so every step is wrapped in
     * [runCatching] and surfaced through the same LAST RESULT card every other action uses.
     */
    private suspend fun openSampleMqttConnection(): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val client =
                    MqttAsyncClient(
                        MQTT_BROKER_URI,
                        MqttAsyncClient.generateClientId(),
                        MemoryPersistence(),
                    )
                mqttClient = client
                val publisher = DevConsolePahoMqtt.install(client, DevConsole.socketRecorder())
                client
                    .connect(
                        MqttConnectOptions().apply {
                            isCleanSession = true
                            connectionTimeout = MQTT_CONNECT_TIMEOUT_SECONDS
                        },
                    ).waitForCompletion()
                client.subscribe(MQTT_DEMO_TOPIC_FILTER, MQTT_DEMO_QOS).waitForCompletion()
                publisher.publish(MQTT_DEMO_TOPIC, "hi from DevConsole".toByteArray(), MQTT_DEMO_QOS, false)
                "MQTT connected -- subscribed to $MQTT_DEMO_TOPIC_FILTER and published to $MQTT_DEMO_TOPIC"
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                // error.message can be null, and a public broker being unreachable is an expected,
                // demo-safe outcome -- never let it crash the sample, just report it.
                "MQTT connection failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }

    /**
     * Exercises every [ScreenshotResult] variant Kotlin's exhaustive `when` demands. Because this
     * sample's [buildConfig] opts into [ScreenshotPolicy], the ordinary path here is
     * [ScreenshotResult.Captured] -- the other branches exist for completeness and are the ones
     * foundation-app actually reaches from its default-off policy.
     */
    private suspend fun captureScreenshot(): String =
        when (val result = DevConsole.captureScreenshot()) {
            is ScreenshotResult.Captured ->
                "Screenshot captured — ${result.widthPx}×${result.heightPx}, ${result.byteCount} bytes " +
                    "(attachment ${result.attachmentId})"
            ScreenshotResult.Disabled ->
                "Screenshot capture is off — set screenshotPolicy.enabled = true on DevConsoleConfig"
            ScreenshotResult.DisabledForBuild -> "Screenshot capture isn't available in this release build"
            ScreenshotResult.NoForegroundActivity ->
                "No foreground activity to capture — bring the app to the front and try again"
            ScreenshotResult.SecureWindow -> "This screen is FLAG_SECURE and cannot be captured"
            is ScreenshotResult.Failed -> "Screenshot failed — ${result.reason}"
        }

    private fun simulatePush(): String {
        val event =
            DevConsole.recordPush(
                PushInput(
                    provider = "local",
                    data = mapOf("campaign" to "compose-sample"),
                    messageId = "compose-sample-message-1",
                    source = "local-simulation",
                ),
            )
        return "Push recorded: ${event.messageId}"
    }
}

/**
 * Seeds the Data inspectors (preferences/database/files -- all live-editable here, per
 * [MainActivity.buildConfig]) so the browser Data rail and the in-app inspector have real content
 * to show the moment the app launches, instead of three empty panes.
 *
 * The seed values below are flavor text and prices, not configuration, so they are suppressed
 * rather than each given a named constant.
 */
@Suppress("MagicNumber")
private fun seedSampleData(context: Context) {
    context
        .getSharedPreferences("compose_sample_prefs", Context.MODE_PRIVATE)
        .edit()
        .putString("displayName", "Ada Lovelace")
        .putInt("cartItemCount", 3)
        .putBoolean("darkModeEnabled", true)
        .putFloat("checkoutProgress", 0.65f)
        // Two non-secret values that together show how key-based redaction actually behaves.
        // "access_token" is on RedactionPolicy.default()'s name list, so the inspector masks it.
        .putString("access_token", "sample-not-a-real-token-4f9c2b")
        // "authToken" is NOT on that list. Redaction matches field names exactly, so this one is
        // shown verbatim -- the allowlist blind spot docs/THREAT_MODEL.md warns about, made
        // visible side by side with the masked value above.
        .putString("authToken", "sample-not-a-real-token-9d1e7a")
        .apply()

    SampleOrdersDatabase(context).writableDatabase.use { db ->
        if (DatabaseUtils.queryNumEntries(db, "orders") == 0L) {
            listOf(
                "Terminal-green hoodie" to 42.5,
                "DevConsole sticker pack" to 6.0,
            ).forEach { (item, price) ->
                db.insert(
                    "orders",
                    null,
                    ContentValues().apply {
                        put("item", item)
                        put("price", price)
                    },
                )
            }
        }
    }

    File(context.filesDir, "sample-notes.txt").writeText(
        "DevConsole compose sample\nSeeded so the Files inspector has something to preview and download.\n",
    )
    File(context.filesDir, "sample-binary.dat").writeBytes(ByteArray(64) { it.toByte() })
}

/** Minimal SQLite schema so the Database inspector has a real table + rows to browse and query. */
private class SampleOrdersDatabase(
    context: Context,
) : SQLiteOpenHelper(context, "compose_sample.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE orders (id INTEGER PRIMARY KEY AUTOINCREMENT, item TEXT NOT NULL, price REAL NOT NULL)",
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) = Unit
}

@Composable
private fun BrandTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "DEV",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "CONSOLE",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun ServerStatusCard(
    status: ServerStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val state = status.state
    val running = state is DevConsoleState.Running
    val busy = state is DevConsoleState.Starting || state is DevConsoleState.Stopping
    val largeText = LocalConfiguration.current.fontScale >= LARGE_TEXT_STACK_THRESHOLD
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = statusColor(state))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = devConsoleStatusText(state, status.endpoint),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            @Composable
            fun StartAction(modifier: Modifier) {
                FilledTonalButton(
                    onClick = onStart,
                    enabled = !running && !busy,
                    modifier = modifier,
                ) { Text("Start server") }
            }

            @Composable
            fun StopAction(modifier: Modifier) {
                OutlinedButton(
                    onClick = onStop,
                    enabled = running || busy,
                    modifier = modifier,
                ) { Text("Stop") }
            }
            if (largeText) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StartAction(Modifier.fillMaxWidth())
                    StopAction(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StartAction(Modifier.weight(1f))
                    StopAction(Modifier.weight(1f))
                }
            }
            if (status.connectUrl != null) {
                ConnectUrlPanel(
                    connectUrl = status.connectUrl,
                    showLanWarning = status.endpoint?.bindingMode == BindingMode.LAN,
                )
            }
        }
    }
}

private data class ServerStatus(
    val state: DevConsoleState,
    val endpoint: BrowserEndpoint?,
    val connectUrl: String?,
)

/**
 * Standalone launcher for the SDK's own in-app inspector, styled to sit alongside
 * [ServerStatusCard] rather than in the capability list below -- the inspector is a primary
 * surface of the SDK, not just another capability to exercise.
 */
@Composable
private fun FullInspectorCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Full inspector",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "The SDK's own in-app UI -- More screen QR, Data rail, HAR/Postman/session-ZIP exports.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open full inspector") }
        }
    }
}

@Composable
private fun ConnectUrlPanel(
    connectUrl: String,
    showLanWarning: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "CONNECT URL -- FULL SESSION CREDENTIAL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = connectUrl,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text =
                    "The #code= fragment is a live, single-use session code; the same code renders as a " +
                        "QR on the More screen inside the full inspector below. Treat both like a password.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(connectUrl))
                    val message = "Session credential copied -- keep it private"
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.align(Alignment.End),
            ) { Text("Copy session credential") }
            if (showLanWarning) {
                Text(
                    text = LOCAL_NETWORK_HTTP_WARNING,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CapabilityCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val largeText = LocalConfiguration.current.fontScale >= LARGE_TEXT_STACK_THRESHOLD
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { role = Role.Button }
                .clickable(onClickLabel = title, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        if (largeText) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = MaterialTheme.colorScheme.primary, size = 8)
                    Spacer(Modifier.width(14.dp))
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    subtitle,
                    modifier = Modifier.padding(start = 22.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Run ›",
                    modifier = Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(color = MaterialTheme.colorScheme.primary, size = 8)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Run ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Warning banner sitting above the hazard-zone buttons so the destructive area reads as a hazard
 * zone rather than blending into the ordinary demo buttons above it.
 */
@Composable
private fun HazardNotice() {
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = HAZARD_SURFACE_ALPHA),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "These buttons are destructive. Crash kills the process; ANR freezes the UI for a few seconds.",
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** [CapabilityCard]'s destructive counterpart -- an error-colored border and text keep it visually distinct. */
@Composable
private fun HazardCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { role = Role.Button }
                .clickable(onClickLabel = title, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = MaterialTheme.colorScheme.error, size = 8)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Run ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ResultCard(lastResponse: String?) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "LAST RESULT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = lastResponse ?: "No action run yet — start the server, then tap a capability above.",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun FeatureFlagCard() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Order history — revealed by a feature-flag override from the dashboard",
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun StatusDot(
    color: Color,
    size: Int = 12,
) {
    Box(
        modifier =
            Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(color),
    )
}

private fun statusColor(state: DevConsoleState): Color =
    when (state) {
        DevConsoleState.Running -> Signal
        DevConsoleState.Starting, DevConsoleState.Stopping, DevConsoleState.PermissionRequired -> Amber
        is DevConsoleState.Failed -> Error
        else -> Idle
    }

private const val LARGE_TEXT_STACK_THRESHOLD = 1.3f
private const val HAZARD_SURFACE_ALPHA = 0.12f

private const val LOCAL_NETWORK_HTTP_WARNING =
    "This debugging session uses local-network HTTP. Other participants on an untrusted network " +
        "may observe or modify traffic. Use ADB localhost mode or a trusted isolated network for sensitive testing."

private const val CONNECT_URL_POLL_MS = 3_000L
