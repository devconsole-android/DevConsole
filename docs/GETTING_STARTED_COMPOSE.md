# Getting started: Compose

About five minutes, most of it waiting for Gradle. By the end you'll have the inspector opening on
your device and the dashboard reachable from a browser.

1. Add the JitPack repository to `settings.gradle.kts` — that is where the library artifacts live
   (the Gradle plugin comes from the Gradle Plugin Portal and needs nothing extra).

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

2. Apply the production-safety plugin and split the dependency by variant.

```kotlin
plugins {
    id("io.github.devconsole-android")
}

dependencies {
    debugImplementation("com.github.devconsole-android.DevConsole:devconsole:<version>")
    releaseImplementation("com.github.devconsole-android.DevConsole:devconsole-noop:<version>")
    debugImplementation("com.github.devconsole-android.DevConsole:devconsole-ui-compose:<version>") // optional, only for the launcher panel
}

devConsole {
    enabledVariants.set(setOf("debug"))
    protectedVariantPatterns.set(listOf("release"))
}
```

`<version>` is a JitPack version — `1.2.4` for the current release. Releases are tagged `v1.2.4`,
and JitPack resolves a bare version against the `v`-prefixed tag, so the `v` is optional; the bare
form is used here because it also matches the plugin's version on the Gradle Plugin Portal.

There is no BOM. `devconsole` (the debug runtime) and `devconsole-noop` are the only two coordinates
a normal integration names; everything else is `devconsole-<module>`.

**`devconsole-ui-compose` must be `debugImplementation`, never plain `implementation`.** It has no
release no-op counterpart and merges `DevConsoleActivity` into your manifest, so a plain
`implementation` dependency ships the whole in-app inspector — and an exported-by-merge Activity —
into your release build with no build-time warning. The Gradle plugin's variant protection does not
cover this module today; it is your responsibility to scope it to `debugImplementation` yourself.

3. Add `INTERNET` to your own app's manifest. The SDK's manifests auto-merge
   `ACCESS_LOCAL_NETWORK`/`ACCESS_NETWORK_STATE`, but not `INTERNET` — without it, the embedded
   server fails with an opaque socket error instead of a clear permission message:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

4. On a debuggable build you can skip explicit `initialize` entirely — the SDK auto-initializes, so
   state/timeline/capture are ready without any `Application.onCreate` boilerplate. The browser
   server itself is never auto-started; call `DevConsole.startBrowser()` yourself (step 5) and read
   the connect URL from the returned `StartResult.Started.access` (or the device's More screen — the
   logged URL deliberately omits the credential fragment). Initialize explicitly when you need to pass
   configuration (state providers, flags, open triggers):

```kotlin
DevConsole.initialize(
    application,
    DevConsoleConfig.default()
        // Optional: let the SDK open the in-app inspector on a shake or from a floating
        // button. Both are off by default and never start the server.
        .withOpenTriggers(OpenTriggers(shakeToOpen = true, floatingButton = true)),
)
```

5. Drop in the optional Compose launcher panel, or build your own with `DevConsole.state()`.
   `DevConsoleState.Running` carries no payload, so capture the endpoint from `onStart`'s own
   `StartResult` and pass it to the panel if you want the running address displayed:

```kotlin
setContent {
    val state by DevConsole.state().collectAsState()
    val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf<BrowserEndpoint?>(null) }
    MaterialTheme {
        DevConsolePanel(
            state = state,
            onStart = {
                scope.launch {
                    val result = DevConsole.startBrowser()
                    endpoint = (result as? StartResult.Started)?.endpoint
                }
            },
            onStop = {
                scope.launch {
                    DevConsole.stop(StopReason.UserRequested)
                    endpoint = null
                }
            },
            endpoint = endpoint,
        )
    }
}
```

6. Tap Start. The panel shows the bound address, something like `DevConsole server is running at
   192.168.0.15:8080`. Open that address in a browser, or use the connect URL from
   `StartResult.Started.access.connectUrl`. If the device isn't local, run
   `adb forward tcp:8080 tcp:8080` first.

For a full working example, [`samples/compose-app`](../samples/compose-app/src/main/kotlin/io/devconsole/sample/compose/MainActivity.kt)
has one button per capability: network and WebSocket capture, mocks, push simulation, a feature
flag, and a state provider.

For how each piece works, see [NETWORK_INSPECTOR.md](NETWORK_INSPECTOR.md),
[WEBSOCKET_INSPECTOR.md](WEBSOCKET_INSPECTOR.md), [COMPOSER_AND_MOCKS.md](COMPOSER_AND_MOCKS.md),
[PUSH.md](PUSH.md), and [STATE_AND_FLAGS.md](STATE_AND_FLAGS.md).
