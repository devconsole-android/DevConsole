# Getting started - XML/Kotlin

1. Apply the production-safety plugin and split the dependency by variant. 

```kotlin
plugins {
    id("io.github.devconsole-android")
}

dependencies {
    debugImplementation("io.github.devconsole-android:devconsole:<version>")
    releaseImplementation("io.github.devconsole-android:devconsole-noop:<version>")
    debugImplementation("io.github.devconsole-android:devconsole-ui-views:<version>") // optional, only for the launcher panel
}

devConsole {
    enabledVariants.set(setOf("debug"))
    protectedVariantPatterns.set(listOf("release"))
}
```

There is no BOM. `devconsole` (the debug runtime) and `devconsole-noop` are the only two coordinates
a normal integration names; everything else is `devconsole-<module>`.

**`devconsole-ui-views` must be `debugImplementation`, never plain `implementation`.** It has no
release no-op counterpart, so a plain `implementation` dependency ships the launcher panel into your
release build with no build-time warning. The Gradle plugin's variant protection does not cover this
module today; it is your responsibility to scope it to `debugImplementation` yourself.

2. Add `INTERNET` to your own app's manifest. The SDK's manifests auto-merge
   `ACCESS_LOCAL_NETWORK`/`ACCESS_NETWORK_STATE`, but not `INTERNET` — without it, the embedded
   server fails with an opaque socket error instead of a clear permission message:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

3. Add the optional launcher view to a debug-only layout:

```xml
<io.devconsole.ui.views.DevConsolePanelView
    android:id="@+id/dev_console_panel"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

4. Initialize and bind the panel. On a debuggable build the SDK has already auto-initialized by
   this point, so `initialize` is only needed when you have configuration to pass (state providers,
   flags, open triggers). The panel starts out showing "not running" either way -- the browser
   server is never auto-started, so `onStart` below is what actually opens it. `DevConsoleState.Running` carries no
   payload, so call `panel.setEndpoint(...)` with the `StartResult.Started.endpoint` your own
   `onStart` receives if you want the running address shown — it's cleared automatically the next
   time the panel renders a non-running state:

```kotlin
DevConsole.initialize(
    application,
    DevConsoleConfig.default()
        // Optional: let the SDK open the in-app inspector on a shake or from a floating
        // button. Both are off by default and never start the server.
        .withOpenTriggers(OpenTriggers(shakeToOpen = true, floatingButton = true)),
)

val panel: DevConsolePanelView = findViewById(R.id.dev_console_panel)
panel.bind(
    state = DevConsole.state(),
    scope = lifecycleScope,
    onStart = {
        lifecycleScope.launch {
            val result = DevConsole.startBrowser()
            panel.setEndpoint((result as? StartResult.Started)?.endpoint)
        }
    },
    onStop = { lifecycleScope.launch { DevConsole.stop(StopReason.UserRequested) } },
)
```

5. Tap Start — the panel now shows the bound address (e.g. `DevConsole server is running at 192.168.0.15:8080`)
   — then open that address (or the connect URL with its `#code=` fragment) in a browser on the same machine (or
   `adb reverse tcp:8080 tcp:8080` first if the device isn't local).

Every SDK entry point used above is also directly Java-callable — see [GETTING_STARTED_XML_JAVA.md](GETTING_STARTED_XML_JAVA.md) if your project is Java rather than Kotlin. [`samples/foundation-app`](../samples/foundation-app/src/main/kotlin/io/devconsole/sample/MainActivity.kt) exercises every capability (network + WebSocket capture, mocks, push, a feature flag, a state provider) using only stock Android widgets, no UI toolkit dependency at all. See [NETWORK_INSPECTOR.md](NETWORK_INSPECTOR.md), [WEBSOCKET_INSPECTOR.md](WEBSOCKET_INSPECTOR.md), [PUSH.md](PUSH.md), [COMPOSER_AND_MOCKS.md](COMPOSER_AND_MOCKS.md), and [STATE_AND_FLAGS.md](STATE_AND_FLAGS.md) for wiring the actual inspectors once the panel is working.
