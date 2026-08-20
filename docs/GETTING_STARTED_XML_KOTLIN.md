# Getting started: XML / Kotlin

No Compose required. The inspector ships as an Activity, and the launcher panel is a plain `View`,
so a Views-based host gets exactly what a Compose one does.

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
    debugImplementation("com.github.devconsole-android.DevConsole:devconsole-ui-views:<version>") // optional, only for the launcher panel
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

**`devconsole-ui-views` must be `debugImplementation`, never plain `implementation`.** It has no
release no-op counterpart, so a plain `implementation` dependency ships the launcher panel into your
release build with no build-time warning. The Gradle plugin's variant protection does not cover this
module today; it is your responsibility to scope it to `debugImplementation` yourself.

3. Add `INTERNET` to your own app's manifest. The SDK's manifests auto-merge
   `ACCESS_LOCAL_NETWORK`/`ACCESS_NETWORK_STATE`, but not `INTERNET` — without it, the embedded
   server fails with an opaque socket error instead of a clear permission message:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

4. Add the optional launcher view to a debug-only layout:

```xml
<io.devconsole.ui.views.DevConsolePanelView
    android:id="@+id/dev_console_panel"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

5. Initialize and bind the panel. On a debuggable build the SDK has already auto-initialized by
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

6. Tap Start — the panel now shows the bound address (e.g. `DevConsole server is running at 192.168.0.15:8080`)
   — then open that address (or the connect URL with its `#code=` fragment) in a browser on the same machine (or
   `adb forward tcp:8080 tcp:8080` first if the device isn't local).

Every SDK entry point used above is also directly Java-callable — see [GETTING_STARTED_XML_JAVA.md](GETTING_STARTED_XML_JAVA.md) if your project is Java rather than Kotlin. [`samples/views-java-app`](../samples/views-java-app/src/main/java/io/devconsole/sample/viewsjava/MainActivity.java) shows a complete working example with `DevConsolePanelView`, network capture, mocks, and the open triggers. See [NETWORK_INSPECTOR.md](NETWORK_INSPECTOR.md), [WEBSOCKET_INSPECTOR.md](WEBSOCKET_INSPECTOR.md), [PUSH.md](PUSH.md), [COMPOSER_AND_MOCKS.md](COMPOSER_AND_MOCKS.md), and [STATE_AND_FLAGS.md](STATE_AND_FLAGS.md) for wiring the actual inspectors once the panel is working.
