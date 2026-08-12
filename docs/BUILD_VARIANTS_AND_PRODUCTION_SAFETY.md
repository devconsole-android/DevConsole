# Build variants and production safety

The full runtime is excluded from production by **dependency selection**, not a boolean flag. A
release build that only depends on `devconsole-noop` never links the embedded server, the
dashboard assets, storage, or any capture code — there is nothing to disable at runtime because it
was never compiled in.

## The Gradle plugin

Apply it to every module that depends on `devconsole` or `devconsole-noop`. With no configuration at
all it does two useful things: it **protects release variants by default**, and it **auto-wires the
per-variant dependency** so you never hand-write the debug/release split — writing `implementation`
where you meant `debugImplementation` is precisely the mistake the variant policy exists to catch,
so not requiring you to write it is better than catching it afterwards.

```kotlin
plugins {
    id("io.github.devconsole-android")
}
```

On a flavored project, zero-config already covers you: `enabledVariants` matches by AGP *build
type* as well as by exact variant name, so its `["debug"]` default resolves every flavor's debug
variant (e.g. `stagingDebug`, `prodDebug`) to `ENABLED` without listing each one. Use
`enabledVariants` yourself only to opt in a *non-debug* build type by its exact variant name (e.g.
`enabledVariants.set(setOf("internal"))` for a variant named exactly `"internal"`) — an exact-name
entry there is the one thing that outranks a matching `protectedVariantPatterns` entry; a
build-type match does not.

Configure it when your variant names are not the stock `debug`/`release`, or when you want the full
runtime in an extra internal variant:

```kotlin
devConsole {
    enabledVariants.set(setOf("debug", "internal"))              // gets the full runtime
    protectedVariantPatterns.set(listOf("(?i).*release", "prod.*")) // verified free of it
    protectedDependencyPaths.set(setOf(":sdk:full"))        // default; override for a differently-pathed module
    failBuildOnUnsafeVariant.set(true)                      // default; false downgrades to a warning
    autoWireDependencies.set(true)                          // default; false to declare coordinates yourself
    sdkVersion.set("1.1.1")                                 // default; the plugin's own version
}
```

`protectedVariantPatterns` entries are matched with `Regex.matches` — a **full** match against the
whole variant name, not a substring search. The bare pattern `"release"` only matches a variant
literally named `"release"`; it silently misses a flavored variant like `"prodRelease"`. Anchor
patterns with `.*` (as `(?i).*release` above does) so they match regardless of what a flavor
prefixes onto the build type.

**A variant that already names a DevConsole dependency is left alone.** Auto-wiring only fills a
gap, so an explicit `debugImplementation("io.github.devconsole-android:devconsole:...")` — or a
`project(":sdk:full")` dependency inside this repository — wins over anything the plugin would have
added.

### What each policy means

| Policy | Effect |
|---|---|
| `ENABLED` | Auto-wired to `devconsole` (the full runtime) |
| `PROTECTED` | Auto-wired to `devconsole-noop`, **and** verified not to depend on the full runtime |
| `DISABLED` | Auto-wired to `devconsole-noop`, not verified |

The difference between `PROTECTED` and `DISABLED` is enforcement, not wiring: both get the no-op
artifact, but only `PROTECTED` variants fail the build if the full runtime shows up anyway.

## `devConsoleVariantReport` and `verifyDevConsoleProtectedArtifacts`

Two tasks come with the plugin:

- `devConsoleVariantReport` writes `build/reports/devconsole/variants.json` listing the effective
  policy (`ENABLED`/`DISABLED`/`PROTECTED`) for every variant.
- `verifyDevConsoleProtectedArtifacts` inspects each protected variant's dependency graph for a
  direct `ProjectDependency` on any path in `protectedDependencyPaths`, and fails the build if one
  is found. Run it as part of your release checklist — the three sample apps run it in CI on every
  push (see [`.github/workflows/verify.yml`](../.github/workflows/verify.yml)).

```bash
./gradlew :app:assembleRelease :app:verifyDevConsoleProtectedArtifacts
```

## Full/no-op parity

`devconsole` and `devconsole-noop` are compiled from the same shared facade source
(`DevConsole.kt`) against two different concrete implementations of `DevConsoleFacadeProvider`, so
your application code never needs an `if (BuildConfig.DEBUG)` branch. Every public method exists in
both artifacts:

| Method | `devconsole` | `devconsole-noop` |
|---|---|---|
| `initialize()` | Starts the real runtime | Returns `InitResult.Disabled` immediately |
| `startBrowser()` / `stop()` | Binds the embedded server | Returns `StartResult.DisabledForBuild` |
| `networkRecorder()` / `socketRecorder()` / `recordPush()` | Redacts and stores | No-op: never redacts, serializes, or stores anything |
| `mockEngine()` | A real, mutable rule engine | Constructed with `enabled = false` — `decide()` always returns `Passthrough` |
| `featureFlagValue(key)` | Reads the live value for a key declared in `DevConsoleConfig.featureFlags` (dashboard override wins if one is set) | Returns the host's declared default for a declared key |

A key that was never declared in `DevConsoleConfig.featureFlags` returns `false` from
`featureFlagValue` in **both** artifacts — `devconsole` because there is no declared default (and no
dashboard override) to fall back to, `devconsole-noop` because an absent default coerces to `false`.
Neither build throws for an unrecognized key.

## What "disabled" actually guarantees

In a disabled variant, calling `DevConsole.networkRecorder().record(...)` or
`DevConsole.recordPush(...)` never touches the redaction engine, never allocates a capture object,
and never writes to any store — confirmed by dedicated tests in `NoopFacadeTest`,
`NetworkTransactionRecorderTest`, `SocketRecorderTest`, and `PushRecorderTest` (search each for
`disabled` to see the exact assertions). This matters because redaction/capture work itself is the
thing production builds must never perform, not just the resulting network request to a dashboard
that doesn't exist in that build.

## Permissions never reach a release build

Manifest entries merge from dependencies, so it is worth being explicit about which ones DevConsole
contributes and where they stop. `sdk:full` declares exactly two — `ACCESS_LOCAL_NETWORK` (Android
17+ gates local-network access; without it a LAN-bound dashboard binds and serves nobody) and
`ACCESS_NETWORK_STATE` (a normal permission, used only for connectivity-change timeline markers).
`sdk:noop` declares none. Since `sdk:full` is a `debugImplementation` and `sdk:noop` is what a
release variant compiles against, neither permission can appear in a released APK or AAB, and
there is nothing to declare to Google Play.

The keep-alive permissions (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
`POST_NOTIFICATIONS`) are not DevConsole's at all — the SDK declares none of them, and a host opts
in by putting them in its **own `src/debug` manifest**, which is what keeps them out of release too.
See [BACKGROUND_KEEPALIVE.md](BACKGROUND_KEEPALIVE.md).

`INTERNET` is the one permission a DevConsole feature needs that the SDK does not declare: binding
the dashboard's TCP socket requires it, but it is not DevConsole-specific and most apps already
carry it for their own networking, so hosts declare it themselves.

Rather than trusting any of that, check a real artifact:

```bash
aapt2 dump permissions app/build/outputs/apk/release/app-release.apk
```

Run against `samples/compose-app` — the sample with every feature switched on — the debug APK lists
`ACCESS_LOCAL_NETWORK`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` and `INTERNET`, while its release APK lists
only `INTERNET` (the sample's own) and the auto-generated dynamic-receiver permission.

## Publication status

The artifacts are `io.github.devconsole-android:devconsole` (the full runtime, from `:sdk:full`) and
`io.github.devconsole-android:devconsole-noop` (from `:sdk:noop`); there is no BOM and never was a
`-full` coordinate. The publishing pipeline is wired — every publishable module produces a POM with
license/developer/SCM metadata, a sources jar, a javadoc jar, and a signature when a key is provided
— but nothing is on Maven Central yet: the version is a pre-1.0 `-SNAPSHOT`, and a release still
needs a signing key and a Central account (see [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md)). Within
this repository the samples depend on the SDK from source via `project(":sdk:full")` /
`project(":sdk:noop")`.
