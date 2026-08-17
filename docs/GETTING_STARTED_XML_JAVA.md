# Getting started: XML / Java

DevConsole's public facade is a Kotlin `object`, but every method carries `@JvmStatic` and the async
variants carry `@JvmOverloads`. From Java it reads like an ordinary utility class, and you never
touch a coroutine: the `...Async` variants take a callback instead.

1. Add the JitPack repository, then apply the production-safety plugin and split the dependency by
   variant (same `settings.gradle.kts` / `build.gradle.kts` shape as the Kotlin guide — see
   [GETTING_STARTED_XML_KOTLIN.md](GETTING_STARTED_XML_KOTLIN.md) steps 1–2).
   The two coordinates are `com.github.devconsole-android.DevConsole:devconsole` for debug and
   `com.github.devconsole-android.DevConsole:devconsole-noop` for release; there is no BOM.

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

4. Initialize, and use the `Async` counterparts of `startBrowser()`/`stop()` since Java has no
   coroutines. Pass `startBrowserAsync(...)` a callback and call `panel.setEndpoint(...)` from it if
   you want the panel to show the bound address once it's known (`DevConsoleState.Running` itself
   carries no payload, so this is the only way to get it):

```java
DevConsole.initialize(getApplication(), DevConsoleConfig.builder()
    // Optional: let the SDK open the in-app inspector on a shake or from a floating
    // button. Both are off by default and never start the server.
    .openTriggers(OpenTriggers.builder().shakeToOpen(true).floatingButton(true).build())
    .build());

DevConsolePanelView panel = findViewById(R.id.dev_console_panel);
CoroutineScope panelScope = CoroutineScopeKt.CoroutineScope(Dispatchers.getMain());
panel.bind(
    DevConsole.state(),
    panelScope,
    () -> {
        DevConsole.startBrowserAsync(result -> {
            if (result instanceof StartResult.Started) {
                panel.setEndpoint(((StartResult.Started) result).getEndpoint());
            }
        });
        return Unit.INSTANCE;
    },
    () -> { DevConsole.stopAsync(StopReason.UserRequested.INSTANCE); return Unit.INSTANCE; }
);
```

5. Push, mocks, network/socket capture, feature flags, and state providers all have plain
   Java-constructible types (`PushInput`, `MockRule`, `NetworkRequestInput`, `FeatureFlag`,
   `DevConsoleConfig`, ...) — none of them use Kotlin default arguments with `@JvmOverloads`, so
   every constructor parameter must be supplied explicitly from Java, and a `StateProvider` needs
   an anonymous-class implementation rather than a lambda (it isn't a Kotlin `fun interface`). See
   the full working example — network + WebSocket capture, mocks, a manually-built push event, a
   Firebase-shaped push event run through `FirebaseRemoteMessageAdapter` with no real Firebase
   dependency, a feature flag, and a state provider — in
   [`samples/views-java-app`](../samples/views-java-app/src/main/java/io/devconsole/sample/viewsjava/MainActivity.java).

See [PUSH.md](PUSH.md), [COMPOSER_AND_MOCKS.md](COMPOSER_AND_MOCKS.md), and
[STATE_AND_FLAGS.md](STATE_AND_FLAGS.md) for what those calls do.
