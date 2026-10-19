# Migration guide

## From the original DevConsole integration

1. Apply the `io.github.devconsole-android` Gradle plugin to the Android application module.
2. Depend on `:sdk:full` only in explicitly allowlisted debug/internal variants and on `:sdk:noop` in every protected variant.
3. Call `DevConsole.initialize(application)` once during application startup.
4. Start the server with `DevConsole.startBrowser()` (or Java's `startBrowserAsync()`), inspect the typed result, and stop it with `DevConsole.stop()` when the debug session ends.

Validate each debug and release variant with `verifyDevConsoleProtectedArtifacts` before rollout.

## Upgrading from 1.2.1

**The library artifacts moved from Maven Central to JitPack.** No API changed; the coordinates did.

1. Add JitPack to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

2. Change the group on every DevConsole dependency from `io.github.devconsole-android` to
   `com.github.devconsole-android.DevConsole`. Artifact IDs and the version spelling are unchanged —
   JitPack serves a build under its git ref name, and resolves a bare version against the matching
   `v*` release tag:

```kotlin
debugImplementation("com.github.devconsole-android.DevConsole:devconsole:1.3.1")
releaseImplementation("com.github.devconsole-android.DevConsole:devconsole-noop:1.3.1")
```

If you rely on the plugin's `autoWireDependencies` (the default), step 2 is done for you — you only
need step 1, because the coordinate the plugin declares now resolves from JitPack.

The Gradle plugin itself is unaffected: it still comes from the Gradle Plugin Portal as
`io.github.devconsole-android`, and its variant-policy check still recognises the old
`io.github.devconsole-android` library group, so a module you have not migrated yet keeps its
release-build protection.

## Security layers are now opt-in

DevConsole remains debug-only. Constructor and Java Builder defaults now favor inspection:

| Setting | Previous default | New default | Restore previous behavior |
|---|---|---|---|
| Redaction | Built-in field rules | None | `DevConsoleConfig(redactionPolicy = RedactionPolicy.recommended())` |
| Editing capabilities | All false except mocks | All true | `.withEditingCapabilities(EditingCapabilities.readOnly().copy(mocks = true))` |
| Composer | Disabled | Enabled | `DevConsoleConfig(composerEnabled = false)` |
| Composer hosts | Empty denies every host | Empty permits any host | Disable Composer, or supply an explicit non-empty host set |
| Composer host editing (new) | — | On, once hosts are listed | `.withComposerHostsEditable(false)` |
| State mutations | Disabled | Enabled | `DevConsoleConfig(stateMutationsEnabled = false)` |
| Screenshots | Disabled | Enabled | `.withScreenshotPolicy(ScreenshotPolicy(enabled = false))` |
| Rate limits | Enabled | Disabled | `.withNetworkProtection(NetworkProtection(rateLimiting = true))` |
| Host/Origin checks | Enabled | Disabled | `.withNetworkProtection(NetworkProtection(hostAndOriginChecks = true))` |
| Public browser authentication | NONE | NONE (unchanged) | `.withBrowserSecurity(BrowserSecurity.SESSION_CODE)` opts in |

Use `NetworkProtection.recommended()` to enable both network switches together. Network protection
is an additive policy, preserved by other `with...` methods and supported by
`DevConsoleConfig.builder().networkProtection(...)` for Java. `RedactionPolicy.default()` now returns
`none()`. `recommended()` preserves the exact previous built-in set, including its intentional
preservation of Authorization headers and Bearer text. Add rules for those if required.

The standalone Ktor module and engine now also default to `BrowserSecurity.NONE` and editable
capabilities. Direct callers testing authentication must explicitly select SESSION_CODE. It always
retains bearer/CSRF validation, expiry, and code-attempt limits, independently of network protection.
Static response headers and the release/noop guard are unchanged.

`/api/v1/meta.redaction` keeps its shape and gains `enabled` (false by default);
`sensitiveFieldNames` is unchanged. The new `networkProtection` object reports both effective switches.
Other metadata fields remain. See [security defaults and the hardening checklist](SECURITY_AND_REDACTION.md).

## Upgrading from 1.2.0 (historical changes)

Three defaults changed. No API was removed and nothing has to be rewritten — but two of these change
behaviour on a build you do not otherwise touch, so read them before upgrading.

**1. The server now binds your network by default.** `StartRequest.bindingMode` and
`BrowserConfig.binding` default to a new `AUTO` value instead of `LOOPBACK`. AUTO binds a real
interface when the device has one and falls back to loopback when it does not, so a start that used
to hand back `http://127.0.0.1:8080/...` now hands back `http://192.168.x.y:8080/...` — reachable by
anyone on that network, over plaintext HTTP.

To keep 1.2.0 behaviour exactly, say so at both surfaces:

```kotlin
DevConsole.startBrowser(StartRequest(bindingMode = BindingMode.LOOPBACK))
DevConsoleConfig.default().withBrowserConfig(BrowserConfig(binding = BrowserBinding.LOOPBACK))
```

Explicit `BindingMode.LAN` is unchanged, including its refusal to fall back. Note that `AUTO` never
returns `StartResult.PermissionRequired`, so if your app prompts for `ACCESS_LOCAL_NETWORK` off the
back of that result, keep asking for `LAN` by name on the start that drives the prompt.

`AUTO` is a reused name, not a restored feature: the pre-1.0 `BindingMode.AUTO` came with
zero-config auto-start and NSD discovery, both still gone. This one only picks a binding.

**If you `when` exhaustively over `BindingMode` or `BrowserBinding`**, the new constant makes that
`when` non-exhaustive and your build will fail until you add a branch. This is the only source-level
break in the release.

**2. Mock rules are wired by `installDevConsole`.** Delete the now-redundant line if you have it:

```diff
 val client = OkHttpClient.Builder()
     .installDevConsole(DevConsole.networkRecorder())
-    .addInterceptor(DevConsoleMockInterceptor(DevConsole.mockEngine()))
     .build()
```

Leaving it costs nothing — the second interceptor detects the first and stands down, so a `Delay`
rule is not applied twice. Pass `mockEngine = null` to opt out of the mock interceptor entirely.

Kotlin callers who rely on default arguments should recompile: `installDevConsole` and
`DevConsoleOkHttp.install` gained a parameter, so their synthetic `$default` signature changed. The
2- and 3-argument overloads are still emitted, so Java call sites and existing bytecode are
unaffected.

**3. Mock editing is on by default.** `EditingCapabilities.mocks` now defaults to `true`, and
`DevConsoleConfig` seeds `EditingCapabilities()` rather than `EditingCapabilities.readOnly()`. A
mock rule writes nothing of yours — it only short-circuits DevConsole's own interceptor — so the
read-only posture that protects preferences, databases, and files had nothing to protect here.
`EditingCapabilities.readOnly()` still grants nothing at all, mocks included, so a host already
calling it is unaffected.

## Pre-1.0 breaking changes

The SDK is pre-1.0 (see [CHANGELOG.md](../CHANGELOG.md)), so the API surface above has moved more
than once during active development — most recently a facade reshape (`start()` →
`startBrowser()`/`startBrowserAsync()`, returning `StartResult.Started(endpoint, access)`), the
removal of `BindingMode.AUTO`/NSD/auto-start, the replacement of pairing/role-based access with the
single SESSION_CODE flow, and the removal of the generic plugin framework. If you're carrying code
against an earlier snapshot of this repository, check the pre-1.0 entries in
[CHANGELOG.md](../CHANGELOG.md) for the full list before updating.
