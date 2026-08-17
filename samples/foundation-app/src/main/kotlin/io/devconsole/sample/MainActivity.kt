/**
 * @author Shakib
 * @since 20/07/26
 */
package io.devconsole.sample

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.devconsole.DevConsole
import io.devconsole.api.BindingMode
import io.devconsole.api.BrowserBinding
import io.devconsole.api.BrowserConfig
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.DevConsoleState
import io.devconsole.api.EditingCapabilities
import io.devconsole.api.ScreenshotResult
import io.devconsole.api.StartRequest
import io.devconsole.api.StartResult
import io.devconsole.api.StopReason
import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockRule
import io.devconsole.network.okhttp.installDevConsole
import io.devconsole.push.PushInput
import io.devconsole.socket.okhttp.DevConsoleOkHttpWebSocketListener
import io.devconsole.socket.okhttp.DevConsoleRecordingWebSocket
import io.devconsole.state.FeatureFlag
import io.devconsole.state.StateSnapshot
import io.devconsole.state.StateValue
import io.devconsole.state.stateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val SHOW_DIAGNOSTICS_FLAG = "foundation_sample.show_diagnostics"
private const val LAYOUT_PADDING_DP = 16
private const val MIN_TOUCH_TARGET_DP = 48
private const val REQUEST_LAN_PERMISSION = 1001
private val requestCount = AtomicInteger(0)

// DevConsole's terminal-green identity, applied with plain framework drawables so this sample keeps
// its "zero UI framework dependency" character while still looking intentional.
private val GROUND = 0xFF0B0E0D.toInt()
private val SURFACE = 0xFF151A18.toInt()
private val LINE = 0xFF344039.toInt()
private val INK = 0xFFE7E5D8.toInt()
private val MUTED = 0xFF92968E.toInt()
private val SIGNAL = 0xFFB7ED65.toInt()

// Same error-red used by compose-app's MaterialTheme.colorScheme.error, applied to the hazard-zone
// buttons below so the destructive area reads consistently across every sample.
private val HAZARD = 0xFFFF715E.toInt()
private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/** CrashPolicy is left at its 5s default here -- compose-app is the sample that demonstrates a
 * shortened anrThresholdMs, so this block just needs to comfortably clear the default. */
private const val ANR_BLOCK_MS = 6_000L

/**
 * Headless launcher sample built entirely from stock Android widgets -- no Compose, no ui-views
 * panel -- proving every SDK capability is reachable with zero UI framework dependency. Each
 * button below exercises exactly one capability so it can be tested in isolation.
 *
 * Capability-flag posture (see compose-app's `MainActivity` for the full three-sample matrix):
 * [EditingCapabilities.readOnly] refuses every write here, on purpose -- this sample is the
 * locked-down contrast to compose-app's fully-unlocked one. Note that this now takes an explicit
 * call: the SDK's own default leaves `mocks` editable, and `readOnly()` is what opts back out. The
 * Data rail (preferences/database/files, seeded below) is still visible and browsable in both the
 * browser dashboard and the SDK's in-app inspector; only the write/edit actions are refused.
 *
 * Screenshot capture follows the same posture: `ScreenshotPolicy.enabled` is left at its SDK-wide
 * `false` default (no `withScreenshotPolicy` override), so the "Capture screenshot" button here
 * always renders [ScreenshotResult.Disabled] verbatim, naming the property a host would set to turn
 * it on. compose-app is the sample that opts in -- seeing the refusal here next to compose-app's
 * real capture is the fastest way to understand the gate.
 *
 * The HAZARD ZONE below is deliberately set apart from the ordinary demo buttons: one button throws
 * an uncaught exception (kills the process), the other blocks the main thread past the default
 * [io.devconsole.api.CrashPolicy.anrThresholdMs] to trip the ANR watchdog.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val plainClient = OkHttpClient()
    private val instrumentedClient by lazy {
        OkHttpClient
            .Builder()
            // One-call installer: wires the event listener factory and the interceptor together so
            // the Network inspector's timing bar (DNS/connect/TLS/send/wait/receive) is populated,
            // and wires mock rules from the engine DevConsole published at initialize -- no separate
            // DevConsoleMockInterceptor line needed.
            .installDevConsole(DevConsole.networkRecorder())
            .build()
    }

    private lateinit var statusView: TextView
    private var connectUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DevConsole.initialize(
            application,
            DevConsoleConfig(
                stateProviders =
                    listOf(
                        stateProvider("foundation-sample") {
                            StateSnapshot(mapOf("requestsSent" to StateValue.NumberValue(requestCount.get())))
                        },
                    ),
                featureFlags =
                    listOf(
                        FeatureFlag(
                            key = SHOW_DIAGNOSTICS_FLAG,
                            defaultValue = false,
                            description = "Shows extra diagnostics in the sample UI",
                        ),
                    ),
                // Explicit, not just the default: this sample's whole point is the locked-down
                // contrast to compose-app's fully-unlocked EditingCapabilities.
                // OpenTriggers likewise stays at its all-off default -- no shake-to-open, no floating button.
            ).withEditingCapabilities(EditingCapabilities.readOnly())
                // Matches the BindingMode.LAN this sample's own Start button passes: the in-app
                // inspector's More screen issues no StartRequest, so without this it would bind
                // loopback and the two start paths would hand out different connect URLs. This is
                // the one place LAN is deliberate in an otherwise locked-down sample -- see the
                // start call site and docs/THREAT_MODEL.md.
                .withBrowserConfig(BrowserConfig(binding = BrowserBinding.LAN)),
        )
        // Mock rules are SESSION-scoped and dropped on every server restart, so the rule is
        // (re)installed from the Running-state observer below, not once here -- a one-shot install
        // would silently stop mocking after a restart.
        seedSampleData(applicationContext)

        setContentView(buildContentView())
        DevConsole.accessInfo()?.connectUrl?.let(::showConnectUrl)
        scope.launch {
            DevConsole.state().collect { state ->
                if (state is DevConsoleState.Running) {
                    installMockRule()
                    // Poll while running: an exchange consumes the shown code and the SDK
                    // mints a fresh one; a one-shot read would keep advertising the dead URL.
                    while (DevConsole.state().value is DevConsoleState.Running) {
                        DevConsole.accessInfo()?.connectUrl?.let { url -> runOnUiThread { showConnectUrl(url) } }
                        kotlinx.coroutines.delay(CONNECT_URL_POLL_MS)
                    }
                }
            }
        }
    }

    private fun installMockRule() {
        DevConsole.mockEngine().upsert(
            MockRule(
                id = "foundation-sample-orders",
                priority = 0,
                path = "/orders",
                action = MockAction.StaticResponse(statusCode = 200, body = "{\"mocked\":true,\"orders\":[]}"),
            ),
        )
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            DevConsole.stopAsync(StopReason.ApplicationTerminated)
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildContentView() =
        ScrollView(this).apply {
            setBackgroundColor(GROUND)
            applySystemBarInsets(this)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val padding = dp(LAYOUT_PADDING_DP)
                    setPadding(padding, padding, padding, padding)

                    addView(brandHeader())

                    addView(sectionLabel("SERVER"))
                    addView(button("Start server") { startServer() })
                    addView(
                        button("Stop server") {
                            runSuspending {
                                DevConsole.stop(StopReason.UserRequested)
                                "Server stopped"
                            }
                        },
                    )

                    addView(sectionLabel("EXERCISE THE SDK"))
                    addView(
                        button("Send network request") {
                            sendRequest("https://jsonplaceholder.typicode.com/todos/1", "Network response")
                        },
                    )
                    addView(
                        button("Send mocked request") { sendRequest("https://example.test/orders", "Mocked response") },
                    )
                    addView(button("Open sample WebSocket") { openSampleWebSocket() })
                    addView(button("Simulate push") { simulatePush() })
                    addView(
                        button("Read feature flag") {
                            showResult("Diagnostics flag: ${DevConsole.featureFlagValue(SHOW_DIAGNOSTICS_FLAG)}")
                        },
                    )
                    addView(button("Capture screenshot") { captureScreenshot() })

                    addView(sectionLabel("HAZARD ZONE — DESTRUCTIVE", HAZARD))
                    addView(hazardNotice())
                    addView(
                        button("Trigger crash (kills the process)", HAZARD) { triggerCrash() },
                    )
                    addView(
                        button("Trigger ANR (freezes ~${ANR_BLOCK_MS / MS_PER_SECOND}s)", HAZARD) { triggerAnr() },
                    )

                    addView(sectionLabel("LAST RESULT"))
                    statusView = resultCard()
                    addView(statusView)
                },
            )
        }

    private fun applySystemBarInsets(view: View) {
        view.setOnApplyWindowInsetsListener { target, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                target.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        view.requestApplyInsets()
    }

    private fun brandHeader() =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(brandWord("DEV", INK))
            addView(brandWord("CONSOLE", SIGNAL))
        }

    private fun brandWord(
        word: String,
        color: Int,
    ) = TextView(this).apply {
        text = word
        setTextColor(color)
        textSize = 20f
        letterSpacing = 0.12f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionLabel(
        label: String,
        color: Int = MUTED,
    ) = TextView(this).apply {
        text = label
        setTextColor(color)
        textSize = 12f
        letterSpacing = 0.08f
        typeface = Typeface.DEFAULT_BOLD
        layoutParams =
            LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = dp(20)
                bottomMargin = dp(6)
            }
    }

    /** Sits above the hazard-zone buttons so the destructive area reads as a hazard rather than
     * blending into the ordinary demo buttons above it. */
    private fun hazardNotice() =
        TextView(this).apply {
            text = "These buttons are destructive. Crash kills the process; ANR freezes the UI for a few seconds."
            setTextColor(HAZARD)
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        }

    private fun resultCard() =
        TextView(this).apply {
            text = "No action run yet — start the server, then tap a capability above."
            setTextColor(INK)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            val p = dp(14)
            setPadding(p, p, p, p)
            minimumHeight = dp(MIN_TOUCH_TARGET_DP)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isClickable = false
            background =
                GradientDrawable().apply {
                    setColor(SURFACE)
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), LINE)
                }
            setOnClickListener {
                val url = connectUrl ?: return@setOnClickListener
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("DevConsole session credential", url))
                val message = "Session credential copied -- keep it private"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

    private fun button(
        label: String,
        backgroundColor: Int = SIGNAL,
        onClick: () -> Unit,
    ) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(GROUND)
        typeface = Typeface.DEFAULT_BOLD
        background =
            GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = dp(12).toFloat()
            }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minimumHeight = dp(MIN_TOUCH_TARGET_DP)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun runSuspending(block: suspend () -> Any) {
        scope.launch {
            val result = block()
            runOnUiThread {
                showResult(result.toString())
            }
        }
    }

    private fun sendRequest(
        url: String,
        successLabel: String,
    ) {
        runSuspending {
            requestCount.incrementAndGet()
            runCatching {
                val request = Request.Builder().url(url).build()
                instrumentedClient.newCall(request).execute().use { "$successLabel: ${it.code}" }
            }.getOrElse { error -> "Request failed: ${error.message}" }
        }
    }

    private fun openSampleWebSocket() {
        val request = Request.Builder().url("wss://ws.postman-echo.com/raw").build()
        val socket = plainClient.newWebSocket(request, DevConsoleOkHttpWebSocketListener(DevConsole.socketRecorder()))
        // Wrap the returned socket so an outbound frame is captured too -- the raw socket from
        // newWebSocket() only records inbound events via the listener.
        DevConsoleRecordingWebSocket.wrap(socket, DevConsole.socketRecorder()).send("Hello from the foundation sample")
        showResult("WebSocket connecting...")
    }

    private fun simulatePush() {
        runSuspending {
            val event =
                DevConsole.recordPush(
                    PushInput(
                        provider = "local",
                        data = mapOf("campaign" to "foundation-sample"),
                        messageId = "foundation-sample-message-1",
                        source = "local-simulation",
                    ),
                )
            "Push recorded: ${event.messageId}"
        }
    }

    /**
     * Exercises every [ScreenshotResult] variant Kotlin's exhaustive `when` demands. Since this
     * sample's config never calls `withScreenshotPolicy`, [ScreenshotResult.Disabled] is the branch
     * actually reached here -- the other variants are what compose-app's opted-in capture reaches
     * instead.
     */
    private fun captureScreenshot() {
        runSuspending {
            when (val result = DevConsole.captureScreenshot()) {
                is ScreenshotResult.Captured ->
                    "Screenshot captured: ${result.widthPx}x${result.heightPx}, ${result.byteCount} bytes"
                ScreenshotResult.Disabled ->
                    "Screenshot capture is off -- set screenshotPolicy.enabled = true on DevConsoleConfig " +
                        "(left at the default here to show the locked-down refusal; compose-app opts in)"
                ScreenshotResult.DisabledForBuild -> "Screenshot capture isn't available in this release build"
                ScreenshotResult.NoForegroundActivity ->
                    "No foreground activity to capture -- bring the app to the front and try again"
                ScreenshotResult.SecureWindow -> "This screen is FLAG_SECURE and cannot be captured"
                is ScreenshotResult.Failed -> "Screenshot failed: ${result.reason}"
            }
        }
    }

    /** HAZARD: throws directly on the UI thread (button clicks already run there), producing a real
     * uncaught crash that kills the process. */
    private fun triggerCrash() {
        error("foundation-app sample: deliberate uncaught crash")
    }

    /** HAZARD: blocks the UI thread past the default CrashPolicy.anrThresholdMs (5s) so the ANR
     * watchdog's all-thread dump fires. The process survives, unlike [triggerCrash]. */
    private fun triggerAnr() {
        Thread.sleep(ANR_BLOCK_MS)
    }

    private fun startServer() {
        scope.launch {
            // LAN so the connect URL carries the device's own address and a browser on the same
            // network can reach it without `adb forward`. That exposure is real: the dashboard speaks
            // plaintext HTTP, so anyone who can see these packets reads every captured header, token
            // and body. Swap to LOOPBACK below on any network you do not control. See
            // docs/THREAT_MODEL.md.
            val result = DevConsole.startBrowser(StartRequest(bindingMode = BindingMode.LAN))
            // val result = DevConsole.startBrowser(StartRequest(bindingMode = BindingMode.LOOPBACK))
            runOnUiThread {
                when (result) {
                    is StartResult.Started -> {
                        showConnectUrl(result.access.connectUrl)
                    }
                    is StartResult.PermissionRequired -> {
                        showResult("Requesting local network permission...")
                        requestPermissions(arrayOf(result.permission), REQUEST_LAN_PERMISSION)
                    }
                    else -> {
                        showResult(result.toString())
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LAN_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startServer()
            } else {
                showResult("Local network permission was denied")
            }
        }
    }

    private fun showResult(message: String) {
        connectUrl = null
        statusView.text = message
        statusView.isClickable = false
        statusView.contentDescription = message
    }

    private fun showConnectUrl(url: String) {
        connectUrl = url
        val message =
            "Tap to copy the session credential: $url\n\n" +
                "This is a full, single-use session credential -- the same code also renders as a QR on " +
                "the dashboard's More screen once you're connected. Treat it like a password.\n\n" +
                "This debugging session uses local-network HTTP. Other participants on an untrusted network " +
                "may observe or modify traffic. Use ADB localhost mode or a trusted isolated network " +
                "for sensitive testing."
        statusView.text = message
        statusView.isClickable = true
        statusView.contentDescription = "$message Double tap to copy the session credential."
    }
}

/**
 * Seeds the Data inspectors (preferences/database/files) so the browser dashboard and the SDK's
 * in-app inspector have real, browsable content the moment this sample launches -- read-only here,
 * per this file's locked-down [io.devconsole.api.EditingCapabilities] posture.
 */
private fun seedSampleData(context: Context) {
    seedPreferences(context)
    seedDatabase(context)
    seedFiles(context)
}

// Sample seed values below are flavor text and prices, not configuration -- @Suppress rather than
// naming a constant for each one.
@Suppress("MagicNumber")
private fun seedPreferences(context: Context) {
    context
        .getSharedPreferences("foundation_sample_prefs", Context.MODE_PRIVATE)
        .edit()
        .putString("displayName", "Grace Hopper")
        .putInt("cartItemCount", 1)
        .putBoolean("darkModeEnabled", false)
        .putFloat("checkoutProgress", 0.2f)
        // Sensitive-named key (not a real secret): demonstrates the Preferences inspector's
        // key-based redaction without ever writing a live credential to disk.
        .putString("apiSecret", "sample-not-a-real-secret-91ab")
        .apply()
}

@Suppress("MagicNumber")
private fun seedDatabase(context: Context) {
    SampleOrdersDatabase(context).writableDatabase.use { db ->
        if (DatabaseUtils.queryNumEntries(db, "orders") > 0) return@use
        listOf(
            "Foundation sample t-shirt" to 18.0,
            "Debug build lanyard" to 4.5,
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

@Suppress("MagicNumber")
private fun seedFiles(context: Context) {
    File(context.filesDir, "sample-notes.txt").writeText(
        "DevConsole foundation sample\nSeeded so the Files inspector has something to preview and download.\n",
    )
    File(context.filesDir, "sample-binary.dat").writeBytes(ByteArray(64) { it.toByte() })
}

/** Minimal SQLite schema so the Database inspector has a real table + rows to browse and query. */
private class SampleOrdersDatabase(
    context: Context,
) : SQLiteOpenHelper(context, "foundation_sample.db", null, 1) {
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

private const val CONNECT_URL_POLL_MS = 3_000L
private const val MS_PER_SECOND = 1_000L
