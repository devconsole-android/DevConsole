# Getting started - Compose

1. Apply the production-safety plugin and split the dependency by variant. 

```kotlin
plugins {
    id("io.github.shakibuzzaman3104.android")
}

dependencies {
    debugImplementation("io.github.shakibuzzaman3104:devconsole:<version>")
    releaseImplementation("io.github.shakibuzzaman3104:devconsole-noop:<version>")
    debugImplementation("io.github.shakibuzzaman3104:devconsole-ui-compose:<version>") // optional, only for the launcher panel
}

devConsole {
    enabledVariants.set(setOf("debug"))
    protectedVariantPatterns.set(listOf("release"))
}
```

There is no BOM. `devconsole` (the debug runtime) and `devconsole-noop` are the only two coordinates
a normal integration names; everything else is `devconsole-<module>`.

**`devconsole-ui-compose` must be `debugImplementation`, never plain `implementation`.** It has no
release no-op counterpart and merges `DevConsoleActivity` into your manifest, so a plain
`implementation` dependency ships the whole in-app inspector — and an exported-by-merge Activity —
into your release build with no build-time warning. The Gradle plugin's variant protection does not
cover this module today; it is your responsibility to scope it to `debugImplementation` yourself.

2. Add `INTERNET` to your own app's manifest. The SDK's manifests auto-merge
   `ACCESS_LOCAL_NETWORK`/`ACCESS_NETWORK_STATE`, but not `INTERNET` — without it, the embedded
   server fails with an opaque socket error instead of a clear permission message:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

3. On a debuggable build you can skip explicit `initialize` entirely — the SDK auto-initializes, so
   state/timeline/capture are ready without any `Application.onCreate` boilerplate. The browser
   server itself is never auto-started; call `DevConsole.startBrowser()` yourself (step 4) and read
   the connect URL from the returned `StartResult.Started.access` (or the device's More screen — the
   logged URL deliberately omits the credential fragment). Initialize explicitly when you need to pass
   configuration (state providers, flags):

```kotlin
DevConsole.initialize(application, DevConsoleConfig.default())
```

4. Drop in the optional Compose launcher panel, or build your own with `DevConsole.state()`.
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

5. Tap Start — the panel now shows the bound address (e.g. `DevConsole server is running at 192.168.0.15:8080`)
   — then open that address (or the connect URL from `StartResult.Started.access.connectUrl`) in a
   browser on the same machine (or `adb reverse tcp:8080 tcp:8080` first if the device isn't local).

The full working example — network and WebSocket capture, mocks, push simulation, a feature flag, and a state provider, one button per capability — is [`samples/compose-app`](../samples/compose-app/src/main/kotlin/io/devconsole/sample/compose/MainActivity.kt). See [NETWORK_INSPECTOR.md](NETWORK_INSPECTOR.md), [WEBSOCKET_INSPECTOR.md](WEBSOCKET_INSPECTOR.md), [COMPOSER_AND_MOCKS.md](COMPOSER_AND_MOCKS.md), [PUSH.md](PUSH.md), and [STATE_AND_FLAGS.md](STATE_AND_FLAGS.md) for how each piece works.
