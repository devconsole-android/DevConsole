# DevConsole

An on-device developer console for Android. It embeds a small web server inside your **debug**
builds and serves a full debugging dashboard to any browser on your machine — no desktop app, no
IDE plugin required. Release builds compile against a no-op twin artifact, so none of this code
ever ships to production, and the Gradle plugin fails the build if it would.

## TL;DR

**1. Add the plugin and two dependencies** to your app's `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("io.github.devconsole-android") version "0.1.0"
}

dependencies {
    debugImplementation("io.github.devconsole-android:devconsole:0.1.0")
    releaseImplementation("io.github.devconsole-android:devconsole-noop:0.1.0")
}
```

*(The Gradle plugin is awaiting the Plugin Portal's one-time approval for newly published
plugins. If the `id("io.github.devconsole-android")` line doesn't resolve yet, just omit it — the
two dependencies work on their own; the plugin only adds automatic debug/release wiring and
build-time enforcement of the split.)*

**2. Make sure your manifest has `INTERNET`** (most apps already do):

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

**3. Start the dashboard server** from anywhere in your app — a debug menu, a shake gesture, or
plain `onCreate`:

```kotlin
lifecycleScope.launch { // startBrowser is a suspend function
    val result = DevConsole.startBrowser(StartRequest(bindingMode = BindingMode.LOOPBACK))
    val connectUrl = (result as? StartResult.Started)?.access?.connectUrl
    // e.g. http://127.0.0.1:8080/#code=B7KQ2XWZ — surface this in your debug UI
}
```

**4. Bridge the port and open the URL** — the *whole* URL, `#code=` fragment included; that
fragment is the credential:

```bash
adb reverse tcp:8080 tcp:8080   # use the port from the DevConsole log line
```

That's the setup. The dashboard is live with crash/ANR reports, feature flags, and inspectors for
SharedPreferences, SQLite, and files (read-only by default). Wire your HTTP client with one line
(["Wire up your network stack"](#wire-up-your-network-stack) below) and network, WebSocket, and
MQTT traffic live-tails in too — with mock rules and one-click HAR / Postman / bug-report exports.

> **Not on Maven Central yet.** The first release is being prepared. Until it lands, clone this
> repo and publish everything to your local Maven cache:
>
> ```bash
> ./gradlew publishToMavenLocal                  # the SDK artifacts
> ./gradlew -p gradle-plugin publishToMavenLocal # the Gradle plugin
> ```
>
> then add `mavenLocal()` to both repository blocks of the consuming project's
> `settings.gradle.kts` — the coordinates above then resolve from `~/.m2/repository`.

## What you get

| Area | What it does |
|---|---|
| **Network inspector** | Every HTTP call with headers, bodies, and a DNS/TCP/TLS/send/wait/receive timing bar. Live-tails as traffic happens. |
| **WebSocket & MQTT inspectors** | Connection lifecycles and every frame, inbound and outbound. MQTT rides the Eclipse Paho adapter. |
| **Mock rules** | Serve canned responses for matching requests (OkHttp), toggled from the dashboard, with deterministic priority matching. |
| **Request composer** | Make the device issue ad-hoc HTTP requests from the dashboard. Off by default, host-allowlist confinable. |
| **Crash & ANR capture** | Uncaught exceptions and a main-looper watchdog with bounded all-thread dumps and breadcrumbs. Always delegates to any crash reporter you already have. |
| **Push timeline** | Record FCM (or any) push messages and their lifecycle: received → displayed → opened. The Firebase adapter uses reflection — no compile-time Firebase dependency. |
| **State & feature flags** | Snapshot host-registered state and override feature flags from the browser. |
| **Data inspectors** | Browse SharedPreferences, SQLite (incl. a SQL console), and app files. Read-only by default; every edit surface is opt-in. |
| **Evidence tray & exports** | Flag anything, attach it to a bug report bundle or Markdown/Jira/GitHub clipboard text. Export HAR, Postman Collection, or a full session ZIP. |
| **In-app inspector** | `DevConsole.open(context)` shows the same inspectors as an on-device screen (included with `devconsole`), plus a QR code for pairing the browser. |
| **Background keep-alive** | Opt-in foreground service that keeps the server alive while your app is backgrounded. Manifest-only opt-in, zero SDK-declared permissions. |

Capture is category-scoped: `DevConsoleConfig.withCaptureCategories(...)` narrows what's recorded
(`NETWORK`, `SOCKET`, `MQTT`, `PUSH`, `LOGS`, `CRASHES`, `STATE`, `INSPECTION`, `MOCKS` — default
is all). Events persist in a Room database bounded by a retention policy (7 days / 100 MB by
default).

## How it works

`devconsole` (debug) and `devconsole-noop` (release) expose the **same public API**. The no-op
twin records nothing, serves nothing, and links no server code — production safety comes from
dependency selection, not a runtime flag. The Gradle plugin
(`io.github.devconsole-android`) auto-wires the debug/release split if you omit the
dependencies, and verifies — declared dependencies, resolved runtime classpath, and final
APK/AAB bytes — that the full runtime never reaches a protected variant.

On debuggable builds the SDK auto-initializes from its own `ContentProvider`; no
`Application.onCreate` boilerplate needed. The server itself **never auto-starts** — you always
call `startBrowser(...)` explicitly.

## Step-by-step guide

### Start and stop the server

```kotlin
// Optional — auto-init already ran on debuggable builds. Call it yourself to customize:
DevConsole.initialize(application, DevConsoleConfig.default())

// startBrowser and stop are suspend functions — call them from a coroutine:
val result = DevConsole.startBrowser(StartRequest(bindingMode = BindingMode.LOOPBACK))
when (result) {
    is StartResult.Started -> {
        result.endpoint              // host + port actually bound
        result.access.connectUrl     // the full credential URL — treat as a secret
    }
    is StartResult.PermissionRequired -> { /* LAN only: request result.permission */ }
    else -> { /* NoEligibleNetwork, ServerUnavailable, ... */ }
}

DevConsole.stop(StopReason.UserRequested)
```

Java is fully supported via async variants and builders:

```java
DevConsole.initialize(getApplication(), DevConsoleConfig.builder().build());
StartRequest request = new StartRequest(BindingMode.LAN, new kotlin.ranges.IntRange(8080, 8099));
DevConsole.startBrowserAsync(request, result -> runOnUiThread(() -> {
    if (result instanceof StartResult.Started started) {
        String url = started.getAccess().getConnectUrl();
    }
}));
```

### Find the connect URL

After `startBrowser`, filter Logcat on the `DevConsole` tag:

```text
I/DevConsole: Dashboard available at: http://127.0.0.1:8080/ (access link available through the DevConsole API/launcher; binding: LOOPBACK)
```

**The session code is deliberately absent from Logcat.** The full URL — with its
`#code=<session code>` credential fragment — is available only from
`StartResult.Started.access.connectUrl`, `DevConsole.accessInfo()`, and the device's More screen
(as text and QR code). The port is the **first free one in 8080–8099**, so read it from the log
rather than assuming 8080.

### Connect from your browser

- **Loopback (default):** run `adb reverse tcp:<port> tcp:<port>`, then open the connect URL.
- **LAN:** pass `BindingMode.LAN` and open the URL from any device on the same network — read
  [the threat model](docs/THREAT_MODEL.md) first; the dashboard speaks plaintext HTTP. A LAN start
  that finds no eligible network interface returns `StartResult.NoEligibleNetwork` — it never
  silently falls back to loopback (or vice versa).

Open the **whole URL**. The `#code=` fragment is the credential: single-use, expires in five
minutes, creates a session immediately with no approval step. Bare `http://host:port/` sits
unauthenticated forever. Issue a fresh code from the device if one lapses.

### Wire up your network stack

```kotlin
// OkHttp / Retrofit — capture + timing in one call:
val client = OkHttpClient.Builder()
    .installDevConsole(DevConsole.networkRecorder())
    .addInterceptor(DevConsoleMockInterceptor(DevConsole.mockEngine())) // optional, mocks
    .build()

// Ktor client (captures metadata + request bodies; response bodies are never read):
val ktor = HttpClient { install(DevConsoleKtorClientPlugin) { recorder = DevConsole.networkRecorder() } }

// WebSocket (OkHttp): inbound + outbound
val socket = client.newWebSocket(request, DevConsoleOkHttpWebSocketListener(DevConsole.socketRecorder()))
val recording = DevConsoleRecordingWebSocket.wrap(socket, DevConsole.socketRecorder())

// MQTT (Eclipse Paho):
val publisher = DevConsolePahoMqtt.install(mqttClient, DevConsole.socketRecorder())

// Push (from FirebaseMessagingService.onMessageReceived):
DevConsole.recordPush(FirebaseRemoteMessageAdapter().toPushInput(remoteMessage))

// Anything else (Cronet, Volley, custom): record directly — never throws, never blocks:
DevConsole.networkRecorder().record(requestInput, responseInput, startedAtMs, completedAtMs)
```

Java equivalents exist throughout (e.g. `DevConsoleOkHttp.install(builder, recorder)`). Details:
[docs/NETWORK_ADAPTERS.md](docs/NETWORK_ADAPTERS.md), [docs/MQTT_CAPTURE.md](docs/MQTT_CAPTURE.md),
[docs/PUSH.md](docs/PUSH.md).

## Artifacts

Group `io.github.devconsole-android`, one version for everything. Two coordinates cover a normal
integration (there is deliberately no BOM):

| Coordinate | Scope | What it is |
|---|---|---|
| `devconsole` | `debugImplementation` | The full debug runtime: server, dashboard, capture, storage, exports, OkHttp adapters. |
| `devconsole-noop` | `releaseImplementation` | Same API, does nothing, links nothing. |

Opt-in add-ons (each `-noop` twin is the matching `releaseImplementation`):

| Coordinate | Release twin | Adds |
|---|---|---|
| `devconsole-ui-compose` | *(none — `debugImplementation` only)* | Compose launcher-panel API (the on-device inspector itself already ships inside `devconsole`; name this coordinate only to call the panel composables) |
| `devconsole-ui-views` | *(none — `debugImplementation` only)* | `DevConsolePanelView` launcher for XML/Views hosts |
| `devconsole-network-ktor` | *(none)* | Ktor `HttpClient` capture plugin |
| `devconsole-socket-paho` | `devconsole-socket-paho-noop` | MQTT capture via Eclipse Paho |
| `devconsole-push-firebase` | `devconsole-push-firebase-noop` | FCM push adapter (reflection-based) |

Every other module (`devconsole-core`, `devconsole-storage-room`, …) arrives transitively — you
never name it. All modules publish sources, javadoc, and signed POMs.

## Security model — read this

DevConsole is a debugging tool that intentionally exposes your app's internals to a browser.
The design keeps that safe by default, but you should know exactly where the edges are:

- **The dashboard speaks plaintext HTTP.** There is no TLS. In LAN mode, anyone who can observe
  your network packets can read everything the dashboard shows — headers, tokens, bodies, exports.
  Loopback + `adb reverse` is the default for this reason; LAN is always an explicit opt-in.
- **The connect URL is a credential.** Possession of a live `#code=` fragment creates a session —
  no on-device approval step. Codes are single-use with a five-minute TTL; sessions last 30
  minutes. Treat the URL like a password; the device's More screen can revoke sessions.
- **Redaction is an allowlist.** ~25 well-known field names (plus `Bearer` tokens) are masked.
  Custom header names, signed-URL query params, and PII inside bodies pass through verbatim.
  See [docs/SECURITY_AND_REDACTION.md](docs/SECURITY_AND_REDACTION.md).
- **Screenshots cannot be redacted** — pixels don't have field names. Screenshot capture is **off
  by default** and everything it produces is marked `UNREDACTED`.
- **Editing is off by default.** Preferences/database/file writes, mock editing, and capture-rule
  editing are each gated per surface via `EditingCapabilities`; the composer and state mutation
  have their own `DevConsoleConfig` flags. Everything defaults to off/read-only.

The full analysis, including what a malicious network peer or co-installed app can and cannot do:
[docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).

## Sample apps

Three runnable samples under [`samples/`](samples/) cover every integration style:

| Sample | Stack | Posture |
|---|---|---|
| [`compose-app`](samples/compose-app) | Jetpack Compose | Everything unlocked: all editing capabilities, composer, screenshots, MQTT + WebSocket demos, in-app inspector |
| [`foundation-app`](samples/foundation-app) | Stock widgets, no UI framework | Everything at its locked-down default — the read-only contrast |
| [`views-java-app`](samples/views-java-app) | Java + XML, `ui-views` panel | Middle ground: mocks and capture rules editable, data read-only, async Java APIs |

```bash
./gradlew :samples:compose-app:assembleDebug
```

Each sample seeds preferences, a SQLite table, and files so the inspectors have real content, and
includes a hazard section that triggers a real crash and a real ANR.

## Compatibility

| | |
|---|---|
| `minSdk` | 24 |
| `compileSdk` / `targetSdk` | 37 |
| Android Gradle Plugin | 9.3.0 |
| Kotlin | 2.4.10 |

## Documentation

Full index: [docs/README.md](docs/README.md). Highlights:

- [Threat model and safe operation](docs/THREAT_MODEL.md) — **start here before LAN mode**
- Getting started: [Compose](docs/GETTING_STARTED_COMPOSE.md) ·
  [XML/Kotlin](docs/GETTING_STARTED_XML_KOTLIN.md) · [XML/Java](docs/GETTING_STARTED_XML_JAVA.md)
- [Build variants and production safety](docs/BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md)
- [Network adapters](docs/NETWORK_ADAPTERS.md) · [MQTT capture](docs/MQTT_CAPTURE.md) ·
  [Push](docs/PUSH.md)
- [Data inspectors and exports](docs/DATA_INSPECTORS_AND_EXPORTS.md) ·
  [Evidence and bug reports](docs/EVIDENCE_AND_BUG_REPORTS.md)
- [Crash and ANR capture](docs/CRASH_AND_ANR.md) ·
  [Background keep-alive](docs/BACKGROUND_KEEPALIVE.md)
- [FAQ / troubleshooting](docs/FAQ_TROUBLESHOOTING.md)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build prerequisites and the test/lint/API-check
commands. CI runs the same `./gradlew` tasks on every PR.

## License

[MIT](LICENSE)
