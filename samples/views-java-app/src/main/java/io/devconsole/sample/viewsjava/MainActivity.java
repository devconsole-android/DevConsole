/**
 * @author Shakib
 * @since 20/07/26
 */
package io.devconsole.sample.viewsjava;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.devconsole.DevConsole;
import io.devconsole.api.BindingMode;
import io.devconsole.api.DevConsoleConfig;
import io.devconsole.api.DevConsoleState;
import io.devconsole.api.EditingCapabilities;
import io.devconsole.api.OpenTriggers;
import io.devconsole.api.ScreenshotResult;
import io.devconsole.api.ShakeIntensity;
import io.devconsole.api.StartRequest;
import io.devconsole.api.StartResult;
import io.devconsole.api.StopReason;
import io.devconsole.mocks.MockAction;
import io.devconsole.mocks.MockRule;
import io.devconsole.mocks.MockScope;
import io.devconsole.mocks.okhttp.DevConsoleMockInterceptor;
import io.devconsole.network.okhttp.DevConsoleOkHttp;
import io.devconsole.push.PushEvent;
import io.devconsole.push.PushInput;
import io.devconsole.push.PushLifecycle;
import io.devconsole.push.firebase.FirebaseRemoteMessageAdapter;
import io.devconsole.socket.okhttp.DevConsoleOkHttpWebSocketListener;
import io.devconsole.socket.okhttp.DevConsoleRecordingWebSocket;
import io.devconsole.state.FeatureFlag;
import io.devconsole.state.StateProvider;
import io.devconsole.state.StateSnapshot;
import io.devconsole.state.StateValue;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.Job;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Java + XML launcher sample exercising every SDK capability: network + socket capture, mocks
 * (with enable/disable), push recording (manual and Firebase-shaped), a feature flag, and a state
 * provider. The SDK itself never auto-registers this activity.
 *
 * <p>Capability-flag posture (see compose-app's {@code MainActivity} for the full three-sample
 * matrix): this sample sits between compose-app's fully-unlocked and foundation-app's fully-locked
 * {@link EditingCapabilities} -- {@code mocks} and {@code captureRules} are editable, but
 * preferences/database/files stay read-only, matching a host that trusts in-session traffic
 * shaping but not remote edits to on-device storage.
 *
 * <p>Screenshot capture stays at its SDK-wide default here too -- no {@code withScreenshotPolicy}
 * override, so {@code btnCaptureScreenshot} always resolves {@link ScreenshotResult.Disabled}. A
 * screenshot is a different, orthogonal axis from the mocks/captureRules edits this sample already
 * unlocks: trusting the dashboard to reshape in-session traffic is not the same trust decision as
 * handing it the most sensitive, unredactable artifact the SDK can emit. compose-app is the one
 * sample that opts in; this one demonstrates the Java-friendly
 * {@link io.devconsole.DevConsole#captureScreenshotAsync} callback API reaching the same refusal
 * foundation-app reaches through the Kotlin suspend function.
 *
 * <p>{@code btnTriggerCrash} and {@code btnTriggerAnr} sit in a HAZARD ZONE card set apart from the
 * ordinary demo buttons, since a tap kills the process (crash) or freezes the UI for several
 * seconds (ANR). {@link io.devconsole.api.CrashPolicy} is left at its default 5s
 * {@code anrThresholdMs} here -- compose-app is the sample that demonstrates shortening it for a
 * faster demo -- so the ANR button just needs to block comfortably past 5s.
 */
public final class MainActivity extends Activity {
    private static final String SHOW_DIAGNOSTICS_FLAG = "views_java_sample.show_diagnostics";
    private static final String MOCK_RULE_ID = "java-sample-orders";
    private static final int REQUEST_LAN_PERMISSION = 1001;
    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    // Default CrashPolicy.anrThresholdMs is 5s; comfortably clear it rather than shortening the
    // policy here -- compose-app is the sample that demonstrates a shortened threshold.
    private static final long ANR_BLOCK_MS = 6_000L;
    private boolean mockRuleEnabled = true;

    private CoroutineScope panelScope;
    private OkHttpClient client;
    private OkHttpClient socketClient;
    private TextView lastResponse;
    private DevConsolePanelController panel;
    private Job panelJob;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable restoreRunningSession = new Runnable() {
        @Override public void run() {
            DevConsoleState state = DevConsole.state().getValue();
            if (state == DevConsoleState.Running.INSTANCE) {
                // Mock rules are SESSION-scoped and dropped on every server restart, so the rule is
                // (re)installed here -- the Running-state observer -- rather than once in onCreate,
                // which would silently stop mocking after a restart.
                installMockRule();
                panel.setEndpoint(DevConsole.endpoint());
                if (DevConsole.accessInfo() != null) {
                    showConnectUrl(DevConsole.accessInfo().getConnectUrl());
                }
                // Poll while running: a browser exchange consumes the displayed code and
                // the SDK mints a fresh one; a one-shot read would keep advertising the dead URL.
                mainHandler.postDelayed(this, CONNECT_URL_POLL_MS);
            } else if (state == DevConsoleState.Starting.INSTANCE) {
                mainHandler.postDelayed(this, 50L);
            }
        }
    };
    private static final long CONNECT_URL_POLL_MS = 3_000L;
    private String connectUrl;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DevConsole.initialize(getApplication(), buildConfig());
        setContentView(R.layout.activity_main);
        applySystemBarInsets(findViewById(R.id.root));

        // DevConsoleOkHttp.install() is the Java-friendly one-call installer: it wires the event
        // listener factory and interceptor together so the Network inspector's timing bar
        // (DNS/connect/TLS/send/wait/receive) is populated -- see its kdoc for why the equivalent
        // manual three-step form is easy to get wrong from Java.
        client = DevConsoleOkHttp.install(new OkHttpClient.Builder(), DevConsole.networkRecorder())
                .addInterceptor(new DevConsoleMockInterceptor(DevConsole.mockEngine()))
                .build();
        socketClient = new OkHttpClient();
        // Mock rules are SESSION-scoped and dropped on every server restart -- restoreRunningSession
        // (re)installs it on each Running transition instead, so a one-shot call here isn't needed.

        seedSampleData(getApplicationContext());

        lastResponse = findViewById(R.id.tvLastResponse);
        lastResponse.setOnClickListener(view -> copyConnectUrlIfAvailable());
        panelScope = CoroutineScopeKt.CoroutineScope(Dispatchers.getMain());
        FrameLayout panelContainer = findViewById(R.id.dev_console_panel_container);
        panel = DevConsolePanelControllerFactory.create(panelContainer);
        panelJob = panel.bind(
            DevConsole.state(),
            panelScope,
            () -> { startServer(); return Unit.INSTANCE; },
            () -> { stopServer(); return Unit.INSTANCE; }
        );
        mainHandler.post(restoreRunningSession);

        findViewById(R.id.btnSendNetworkRequest).setOnClickListener(view ->
                sendRequest("https://jsonplaceholder.typicode.com/todos/1", "Network response"));
        findViewById(R.id.btnSendMockedRequest).setOnClickListener(view ->
                sendRequest("https://example.test/orders", "Mocked response"));
        findViewById(R.id.btnOpenSocket).setOnClickListener(view -> openSampleWebSocket());
        findViewById(R.id.btnSimulatePush).setOnClickListener(view -> simulatePush());
        findViewById(R.id.btnSimulateFirebasePush).setOnClickListener(view -> simulateFirebasePush());
        findViewById(R.id.btnReadFeatureFlag).setOnClickListener(view -> readFeatureFlag());
        findViewById(R.id.btnToggleMockRule).setOnClickListener(view -> toggleMockRule());
        findViewById(R.id.btnCaptureScreenshot).setOnClickListener(view -> captureScreenshot());
        findViewById(R.id.btnTriggerCrash).setOnClickListener(view -> triggerCrash());
        findViewById(R.id.btnTriggerAnr).setOnClickListener(view -> triggerAnr());
    }

    private static void applySystemBarInsets(View view) {
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                target.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                target.setPadding(
                        insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom()
                );
            }
            return insets;
        });
        view.requestApplyInsets();
    }

    private DevConsoleConfig buildConfig() {
        StateProvider stateProvider = new StateProvider() {
            @Override public String getId() {
                return "views-java-sample";
            }

            @Override public StateSnapshot snapshot() {
                Map<String, StateValue> values = new HashMap<>();
                values.put("requestsSent", new StateValue.NumberValue(REQUEST_COUNT.get()));
                return new StateSnapshot(values);
            }
        };
        FeatureFlag featureFlag = FeatureFlag.ofBoolean(
                SHOW_DIAGNOSTICS_FLAG,
                false,
                "Shows extra diagnostics in the sample UI"
        );
        // mocks + captureRules editable, preferences/database/files read-only -- see the class-level
        // comment for how this sits between compose-app's and foundation-app's postures.
        // Open triggers take the same middle ground below: shake-to-open only (LIGHT), no floating button.
        EditingCapabilities editingCapabilities = EditingCapabilities.builder()
                .mocks(true)
                .captureRules(true)
                .build();
        // Builder rather than positional construction: Kotlin default arguments do not reach Java,
        // so every added field would otherwise break this call site.
        return DevConsoleConfig.builder()
                .addStateProvider(stateProvider)
                .addFeatureFlag(featureFlag)
                .editingCapabilities(editingCapabilities)
                .openTriggers(OpenTriggers.builder()
                        .shakeToOpen(true)
                        .shakeIntensity(ShakeIntensity.LIGHT)
                        .build())
                .build();
    }

    @Override public void onDestroy() {
        mainHandler.removeCallbacks(restoreRunningSession);
        if (panelJob != null) panelJob.cancel(null);
        ioExecutor.shutdownNow();
        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
        if (socketClient != null) {
            socketClient.dispatcher().executorService().shutdown();
            socketClient.connectionPool().evictAll();
        }
        if (isFinishing() && !isChangingConfigurations()) {
            DevConsole.stopAsync(StopReason.ApplicationTerminated.INSTANCE);
        }
        super.onDestroy();
    }

    private void stopServer() {
        DevConsole.stopAsync(StopReason.UserRequested.INSTANCE, ignored ->
                runOnUiThread(() -> {
                    panel.setEndpoint(null);
                    showResult("Server stopped");
                })
        );
    }

    private void startServer() {
        // LAN so the connect URL carries the device's own address and a browser on the same network
        // can reach it without `adb reverse`. That exposure is real: the dashboard speaks plaintext
        // HTTP, so anyone who can see these packets reads every captured header, token and body.
        // Swap to LOOPBACK below on any network you do not control. See docs/THREAT_MODEL.md.
        StartRequest startRequest = new StartRequest(BindingMode.LAN, new kotlin.ranges.IntRange(8080, 8099));
        // StartRequest startRequest = new StartRequest(BindingMode.LOOPBACK, new kotlin.ranges.IntRange(8080, 8099));
        DevConsole.startBrowserAsync(startRequest, result -> runOnUiThread(() -> {
            if (result instanceof StartResult.Started) {
                StartResult.Started started = (StartResult.Started) result;
                // Covers the interactive Stop -> Start flow; restoreRunningSession covers the
                // activity-recreated-while-already-running case at onCreate.
                installMockRule();
                panel.setEndpoint(started.getEndpoint());
                showConnectUrl(started.getAccess().getConnectUrl());
            } else if (result instanceof StartResult.PermissionRequired) {
                String permission = ((StartResult.PermissionRequired) result).getPermission();
                showResult("Requesting local network permission...");
                requestPermissions(new String[] {permission}, REQUEST_LAN_PERMISSION);
            } else {
                showResult("Could not start DevConsole: " + result.getClass().getSimpleName());
            }
        }));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LAN_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startServer();
            } else {
                showResult("Local network permission was denied");
            }
        }
    }

    private void copyConnectUrlIfAvailable() {
        if (connectUrl == null) return;
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("DevConsole session credential", connectUrl));
        Toast.makeText(this, "Session credential copied -- keep it private", Toast.LENGTH_SHORT).show();
    }

    private void sendRequest(String url, String successLabel) {
        REQUEST_COUNT.incrementAndGet();
        ioExecutor.execute(() -> {
            String result;
            try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
                result = successLabel + ": " + response.code();
            } catch (Exception error) {
                result = "Request failed: " + error.getMessage();
            }
            postResult(result);
        });
    }

    private void openSampleWebSocket() {
        Request request = new Request.Builder().url("wss://ws.postman-echo.com/raw").build();
        Function1<WebSocket, String> connectionIdProvider =
                socket -> socket.request().url() + "-" + System.identityHashCode(socket);
        WebSocketListener listener = new DevConsoleOkHttpWebSocketListener(
                DevConsole.socketRecorder(), connectionIdProvider);
        WebSocket socket = socketClient.newWebSocket(request, listener);
        // Wrap the returned socket so an outbound frame is captured too -- the raw socket from
        // newWebSocket() only records inbound events via the listener. Reuses the same
        // connectionIdProvider so the "created" (wrap) and "open" (listener) events line up.
        DevConsoleRecordingWebSocket.Companion.wrap(socket, DevConsole.socketRecorder(), connectionIdProvider)
                .send("Hello from the views-java sample");
        showResult("WebSocket connecting...");
    }

    private void simulatePush() {
        Map<String, String> data = new HashMap<>();
        data.put("campaign", "java-sample");
        PushEvent event = DevConsole.recordPush(new PushInput(
                "local",
                data,
                "java-sample-message-1",
                "local-simulation",
                null,
                System.currentTimeMillis(),
                null,
                Collections.emptyMap(),
                PushLifecycle.RECEIVED,
                true
        ));
        showResult("Push recorded: " + event.getMessageId());
    }

    private void simulateFirebasePush() {
        FakeRemoteMessage remoteMessage = new FakeRemoteMessage();
        PushInput input = new FirebaseRemoteMessageAdapter().toPushInput(remoteMessage);
        PushEvent event = DevConsole.recordPush(input);
        showResult("Firebase push recorded: " + event.getMessageId());
    }

    private void readFeatureFlag() {
        showResult("Diagnostics flag: " + DevConsole.featureFlagValue(SHOW_DIAGNOSTICS_FLAG));
    }

    private void installMockRule() {
        DevConsole.mockEngine().upsert(new MockRule(
                MOCK_RULE_ID,
                0,
                null,
                null,
                null,
                "/orders",
                Collections.emptyMap(),
                Collections.emptyMap(),
                null,
                MockScope.SESSION,
                new MockAction.StaticResponse(200, "{\"mocked\":true,\"orders\":[]}", Collections.emptyMap()),
                null
        ));
    }

    private void toggleMockRule() {
        mockRuleEnabled = !mockRuleEnabled;
        DevConsole.mockEngine().setEnabled(MOCK_RULE_ID, mockRuleEnabled);
        showResult("Mock rule " + (mockRuleEnabled ? "enabled" : "disabled"));
    }

    /**
     * Exercises the Java-friendly {@link DevConsole#captureScreenshotAsync} callback API -- the
     * counterpart to the {@code suspend fun} the Kotlin samples call directly. Since this sample's
     * config never calls {@code withScreenshotPolicy}, {@link ScreenshotResult.Disabled} is the
     * branch actually reached here; the callback runs on a background thread, so the result is
     * routed back through {@link #runOnUiThread}.
     */
    private void captureScreenshot() {
        DevConsole.captureScreenshotAsync(result -> runOnUiThread(() -> showResult(formatScreenshotResult(result))));
    }

    private static String formatScreenshotResult(ScreenshotResult result) {
        if (result instanceof ScreenshotResult.Captured) {
            ScreenshotResult.Captured captured = (ScreenshotResult.Captured) result;
            return "Screenshot captured: " + captured.getWidthPx() + "x" + captured.getHeightPx()
                    + ", " + captured.getByteCount() + " bytes";
        } else if (result instanceof ScreenshotResult.Disabled) {
            return "Screenshot capture is off -- set screenshotPolicy.enabled = true on DevConsoleConfig "
                    + "(left at the default here; see the class Javadoc for why mocks being editable "
                    + "doesn't imply screenshots are)";
        } else if (result instanceof ScreenshotResult.DisabledForBuild) {
            return "Screenshot capture isn't available in this release build";
        } else if (result instanceof ScreenshotResult.NoForegroundActivity) {
            return "No foreground activity to capture -- bring the app to the front and try again";
        } else if (result instanceof ScreenshotResult.SecureWindow) {
            return "This screen is FLAG_SECURE and cannot be captured";
        } else if (result instanceof ScreenshotResult.Failed) {
            return "Screenshot failed: " + ((ScreenshotResult.Failed) result).getReason();
        }
        return "Unknown screenshot result: " + result;
    }

    /**
     * HAZARD: throws directly on the UI thread (button clicks already run there), producing a real
     * uncaught crash that kills the process.
     */
    private void triggerCrash() {
        throw new IllegalStateException("views-java-app sample: deliberate uncaught crash");
    }

    /**
     * HAZARD: blocks the UI thread past the default {@link io.devconsole.api.CrashPolicy}'s 5s
     * {@code anrThresholdMs} so the ANR watchdog's all-thread dump fires. The process survives,
     * unlike {@link #triggerCrash}.
     */
    private void triggerAnr() {
        try {
            Thread.sleep(ANR_BLOCK_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void postResult(String text) {
        new Handler(Looper.getMainLooper()).post(() -> showResult(text));
    }

    private void showResult(String text) {
        connectUrl = null;
        lastResponse.setText(text);
        lastResponse.setClickable(false);
        lastResponse.setContentDescription(text);
    }

    private void showConnectUrl(String url) {
        connectUrl = url;
        String message = "Tap to copy the session credential: " + url + "\n\n"
                + "This is a full, single-use session credential -- the same code also renders as a QR on "
                + "the dashboard's More screen once you're connected. Treat it like a password.\n\n"
                + "This debugging session uses local-network HTTP. Other participants on an untrusted network "
                + "may observe or modify traffic. Use ADB localhost mode or a trusted isolated network for sensitive testing.";
        lastResponse.setText(message);
        lastResponse.setClickable(true);
        lastResponse.setContentDescription(message + " Double tap to copy the session credential.");
    }

    /**
     * Seeds the Data inspectors (preferences/database/files) so the browser dashboard and the
     * SDK's in-app inspector have real, browsable content the moment this sample launches.
     * Preferences/database/files stay read-only per this file's {@link EditingCapabilities}
     * posture (see the class-level comment); only mocks and capture rules are editable here.
     */
    private static void seedSampleData(Context context) {
        seedPreferences(context);
        seedDatabase(context);
        seedFiles(context);
    }

    private static void seedPreferences(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("views_java_sample_prefs", MODE_PRIVATE);
        preferences.edit()
                .putString("displayName", "Margaret Hamilton")
                .putInt("cartItemCount", 2)
                .putBoolean("darkModeEnabled", true)
                .putFloat("checkoutProgress", 0.4f)
                // Sensitive-named key (not a real secret): demonstrates the Preferences inspector's
                // key-based redaction without ever writing a live credential to disk.
                .putString("apiSecret", "sample-not-a-real-secret-77cd")
                .apply();
    }

    private static void seedDatabase(Context context) {
        try (SampleOrdersDatabase helper = new SampleOrdersDatabase(context);
             SQLiteDatabase db = helper.getWritableDatabase()) {
            if (DatabaseUtils.queryNumEntries(db, "orders") > 0) return;
            insertOrder(db, "Views + Java sample mug", 12.0);
            insertOrder(db, "DevConsole keychain", 3.5);
        }
    }

    private static void insertOrder(SQLiteDatabase db, String item, double price) {
        ContentValues values = new ContentValues();
        values.put("item", item);
        values.put("price", price);
        db.insert("orders", null, values);
    }

    private static void seedFiles(Context context) {
        writeTextFile(new File(context.getFilesDir(), "sample-notes.txt"),
                "DevConsole views-java sample\nSeeded so the Files inspector has something to preview and download.\n");
        writeBinaryFile(new File(context.getFilesDir(), "sample-binary.dat"));
    }

    private static void writeTextFile(File file, String content) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Best-effort seed data; a failed write just leaves the Files inspector with less to show.
        }
    }

    private static void writeBinaryFile(File file) {
        byte[] bytes = new byte[64];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        } catch (IOException ignored) {
            // Best-effort seed data; a failed write just leaves the Files inspector with less to show.
        }
    }

    /** Minimal SQLite schema so the Database inspector has a real table + rows to browse and query. */
    private static final class SampleOrdersDatabase extends SQLiteOpenHelper {
        SampleOrdersDatabase(Context context) {
            super(context, "views_java_sample.db", null, 1);
        }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE orders (id INTEGER PRIMARY KEY AUTOINCREMENT, item TEXT NOT NULL, price REAL NOT NULL)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // No schema migrations in a sample database.
        }
    }

    /**
     * Reflection-shaped stand-in for {@code com.google.firebase.messaging.RemoteMessage}, letting
     * {@link FirebaseRemoteMessageAdapter} be exercised without a real Firebase dependency.
     */
    private static final class FakeRemoteMessage {
        public Map<String, String> getData() {
            Map<String, String> data = new HashMap<>();
            data.put("campaign", "java-sample-firebase");
            return data;
        }

        public FakeNotification getNotification() {
            return new FakeNotification();
        }

        public String getMessageId() {
            return "java-sample-firebase-message-1";
        }

        public long getSentTime() {
            return System.currentTimeMillis();
        }

        public String getFrom() {
            return "123456789";
        }

        public String getCollapseKey() {
            return "java-sample-collapse-key";
        }
    }

    private static final class FakeNotification {
        public String getTitle() {
            return "Sample notification";
        }

        public String getBody() {
            return "Simulated via FirebaseRemoteMessageAdapter";
        }

        public String getChannelId() {
            return "sample-channel";
        }

        public String getImageUrl() {
            return null;
        }
    }
}
