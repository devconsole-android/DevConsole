# Remote Config Inspector

Date: 2026-08-13
Status: Approved design
Issue: [#6 — Add Remote Config Inspector to DevConsole](https://github.com/devconsole-android/DevConsole/issues/6)

## Summary

Add a read-only Remote Config inspector to DevConsole: every fetched Remote Config key, its current
value, and the source that value came from (remote server, in-app default, SDK static fallback, or a
local override), alongside fetch metadata (last fetch time, last fetch status, minimum fetch
interval).

The feature ships as a vendor-neutral core module plus a Firebase adapter that carries no
compile-time Firebase dependency, following the existing `push` / `push-firebase` /
`push-firebase-noop` triad exactly. Both DevConsole surfaces render it: the browser dashboard gets a
new rail view, and the Compose inspector gets a section on its Control screen.

## Problem

There is no way to see Remote Config state from DevConsole today — grep finds zero references to
Remote Config anywhere in the repository. A developer or QA engineer verifying a feature flag on a
device has to fall back to logcat or a purpose-built debug screen in the host app.

The specific question that is hard to answer without this is not "what is the value" but **"why is it
that value"** — whether a key is showing the remote value the console published, or has silently
fallen back to an in-app default because the fetch failed or was throttled. That distinction is the
difference between "the rollout works" and "the rollout never reached this device."

## Goals

- List every Remote Config key currently active on the device with its value and source attribution.
- Surface fetch metadata: last fetch time, last fetch status, minimum fetch interval.
- Work without adding a Firebase dependency to consumers who do not use Firebase.
- Support non-Firebase Remote Config providers through a host-implementable interface.
- Render on both the browser dashboard and the on-device Compose inspector.
- Never throw into the host application, and never leak secrets held in config values.

## Non-goals

- **No setting of overrides from DevConsole.** The `OVERRIDE` source is displayed when a host has
  applied one, but DevConsole does not create overrides in this change. Deferred deliberately; see
  "Deferred: override editing".
- No triggering of `fetch()` / `activate()` from DevConsole. The inspector observes; it does not
  drive the Remote Config lifecycle.
- No timeline events or historical fetch records. This is a current-state view, not an event stream.
- No `ui-views` work. That module is a thin launcher panel and renders no flag or state data today.

## Approach

Three new modules, mirroring the existing push triad:

| Module | Namespace | Contents |
| --- | --- | --- |
| `:sdk:remote-config` | `io.devconsole.remoteconfig` | Vendor-neutral model + registry. No Android or Firebase dependencies. |
| `:sdk:remote-config-firebase` | `io.devconsole.remoteconfig.firebase` | Reflection adapter + `RemoteConfigFirebaseCaptureMarker`. |
| `:sdk:remote-config-firebase-noop` | `io.devconsole.remoteconfig.firebase.noop` | Protected-build twin returning an empty, clearly-labelled snapshot. |

Rejected alternatives:

- **Firebase-only single module.** Fewer modules, but breaks the repo's core+adapter convention and
  locks the feature to one vendor; adding a second provider later would be a breaking restructure.
- **Document `StateProvider` instead of shipping code.** Zero SDK code, but `StateValue` has no
  concept of value *source* or fetch metadata — precisely what the issue asks for — so every host
  would hand-roll Firebase reflection and still get no source attribution.

## Data model (`:sdk:remote-config`)

```kotlin
enum class RemoteConfigSource(val wireName: String) {
    REMOTE("remote"),      // fetched and activated from the server
    DEFAULT("default"),    // in-app defaults (setDefaultsAsync)
    STATIC("static"),      // set nowhere; the SDK's static fallback
    OVERRIDE("override"),  // a host applied a local override
    UNKNOWN("unknown"),    // the provider could not attribute it
}

data class RemoteConfigEntry(
    val key: String,
    val value: String,
    val source: RemoteConfigSource,
    val redacted: Boolean = false,
    val truncated: Boolean = false,
)

enum class RemoteConfigFetchStatus { SUCCESS, NO_FETCH_YET, FAILURE, THROTTLED, UNKNOWN }

data class RemoteConfigFetchInfo(
    val lastFetchEpochMs: Long?,
    val status: RemoteConfigFetchStatus,
    val minimumFetchIntervalSeconds: Long?,
)

data class RemoteConfigSnapshot(
    val providerId: String,
    val entries: List<RemoteConfigEntry>,
    val fetchInfo: RemoteConfigFetchInfo,
    val unavailableReason: String? = null,
)

interface RemoteConfigProvider {
    val id: String

    fun snapshot(): RemoteConfigSnapshot
}
```

`RemoteConfigRegistry` mirrors `StateRegistry`: `register(provider)` rejecting blank and duplicate
ids, plus `providerIds()`, `snapshot(id)`, and `snapshots()`.

Decisions embedded in the model:

- **`value` is a `String`.** Remote Config is string-typed at the source; `asLong()` / `asBoolean()`
  are interpretations applied by the reader. Recording a guessed type would invent information the
  server never sent. The dashboard may still pretty-print a value that parses as JSON.
- **Values are truncated at 8 KB** with `truncated = true`, following the repo's existing
  body-preview convention. A single pathological config value must not bloat every snapshot response.
  Truncation is enforced by `RemoteConfigRegistry` when it normalizes a provider's snapshot, not by
  each adapter, so a host-written provider inherits the cap for free rather than having to remember
  it. `MAX_VALUE_LENGTH` is a public constant on the registry's companion.
- **`OVERRIDE` exists but nothing emits it yet.** It is what the issue asks us to *display*, and
  Firebase has no such concept — it arises only where a host layers one on. Reserving the enum case
  now means the deferred override feature is not a wire-format break later.
- **`unavailableReason` instead of exceptions.** Per CONTRIBUTING, capture code must never throw into
  the host. `RemoteConfigRegistry` wraps every `provider.snapshot()` in `runCatching` and converts a
  failure into an empty snapshot carrying the reason, so a broken provider degrades to a visible
  "unavailable" row rather than taking down the inspector. Such a snapshot carries
  `fetchInfo = RemoteConfigFetchInfo(lastFetchEpochMs = null, status = UNKNOWN,
  minimumFetchIntervalSeconds = null)` — a failed provider must not report a confident fetch status.
  The reason string is the throwable's message, or its class name when the message is null.
- **Pull, not push.** Snapshots are evaluated on demand when a surface asks, exactly like
  `StateProvider`. "Which values are currently active" is a state question, not an event stream.

## Firebase adapter (`:sdk:remote-config-firebase`)

```kotlin
class FirebaseRemoteConfigAdapter @JvmOverloads constructor(
    private val remoteConfig: Any, // com.google.firebase.remoteconfig.FirebaseRemoteConfig
    override val id: String = "firebase",
) : RemoteConfigProvider
```

Reflection only — no compile-time Firebase dependency — identical in construction to
`FirebaseRemoteMessageAdapter`, including its private `Any.call(name)` helper that returns `null`
rather than throwing when a method is absent.

Reads `getAll()` → `Map<String, FirebaseRemoteConfigValue>`, then per value `asString()` and
`getSource()`; and `getInfo()` → `getFetchTimeMillis()`, `getLastFetchStatus()`, and
`getConfigSettings().getMinimumFetchIntervalInSeconds()`.

Constant values, verified against `firebase-android-sdk` source rather than assumed:

```java
VALUE_SOURCE_STATIC = 0;   VALUE_SOURCE_DEFAULT = 1;   VALUE_SOURCE_REMOTE = 2;
LAST_FETCH_STATUS_SUCCESS = -1;  NO_FETCH_YET = 0;  FAILURE = 1;  THROTTLED = 2;
```

Two traps this mapping must handle explicitly, both verified above:

1. **`LAST_FETCH_STATUS_SUCCESS` is `-1`, not `0`.** An "unknown status" fallback keyed on negative
   numbers would silently mislabel every successful fetch.
2. **`getFetchTimeMillis()` returns `-1` when no fetch has happened.** Normalized to
   `lastFetchEpochMs = null`, never rendered as a 1969 timestamp.

The adapter resolves these constants reflectively from the Firebase class when it is present and
falls back to the literals above, so a future renumbering cannot silently mis-badge every row. Any
unrecognized integer maps to `UNKNOWN` rather than guessing.

The noop twin returns `RemoteConfigSnapshot(providerId = id, entries = emptyList(), …,
unavailableReason = "disabled-build")` and does not reflect on the supplied object at all, matching
the noop push adapter's contract.

## Host wiring

`DevConsoleConfig` gains `remoteConfigProviders` as an **additive field outside the primary
constructor** (`withRemoteConfigProviders()`, `Builder.addRemoteConfigProvider()`) — the rule that
file already states for preserving the 1.x JVM ABI.

```kotlin
DevConsole.initialize(
    application,
    DevConsoleConfig(/* … */)
        .withRemoteConfigProviders(listOf(FirebaseRemoteConfigAdapter(Firebase.remoteConfig))),
)
```

Late registration via `DevConsole.registerRemoteConfigProvider()` mirrors `registerStateProvider`.
This is safe here precisely because reads are pull-based — unlike feature flags, whose init-only
restriction exists to avoid a race between a late registration and a snapshot the dashboard has
already read.

**Capture gating uses the existing `CaptureCategory.STATE`** rather than a new category. STATE
already means "state providers + feature flags"; Remote Config is the same shape of data. A new enum
constant would widen the wire format and force every host to revisit their `captureCategories` set
for no real benefit. The STATE KDoc and docs are updated to read "state providers, feature flags, and
remote config".

## Redaction

Remote Config values routinely hold API keys, endpoints, and third-party tokens, so they are treated
as a capture boundary like any other.

Redaction is applied **once, in `:sdk:full`**, by wrapping the registry before it is handed to either
consumer. Both the Ktor routes and `FullInspectorDataSource` therefore receive already-redacted
entries, and neither the JSON serializer nor the Compose renderer needs to know redaction exists —
the arrangement CONTRIBUTING prescribes. This matters because the Compose inspector reads in-process
through `InspectorDataSource` and never crosses the HTTP boundary, so redaction applied only in
`server-ktor` would leave the on-device surface unprotected.

Matching is by **key name** through `RedactionEngine.redactFields()`. `RedactionPolicy`'s default
`sensitiveFieldNames` already carries `api-key`, `apikey`, `access_token`, `refresh_token` and
similar — exactly the shape of a leaky Remote Config key. A redacted entry has its `value` replaced
by `RedactionPolicy.replacement` (`<redacted>` by default) **and** sets `redacted = true`, so both
surfaces can badge it as withheld rather than letting a reader mistake the replacement string for the
literally configured value. The key itself is never redacted — knowing *which* key is withheld is the
whole point of the view, and the key name is what the host wrote in the Remote Config console.

## REST API

A single endpoint, `GET /api/v1/remote-config`, returning every provider's snapshot inline:

```json
{"data":[{
  "id":"firebase",
  "fetch":{"lastFetchEpochMs":1755043200000,"status":"success","minimumFetchIntervalSeconds":3600},
  "unavailableReason":null,
  "entries":[
    {"key":"checkout_v2","value":"true","source":"remote","redacted":false,"truncated":false}
  ]
}]}
```

Wire names deliberately differ from the Kotlin field names in two places, and the serializer must map
them: `RemoteConfigSnapshot.providerId` → `"id"`, and `RemoteConfigSnapshot.fetchInfo` → `"fetch"`.
`RemoteConfigSource.wireName` and a lowercase form of `RemoteConfigFetchStatus` supply the enum
strings. These wire names are the stable contract; the Kotlin names may not be changed to match.

Unlike `/api/v1/state`, this does not split into a list-ids call plus a per-id snapshot call. That
two-step exists because state providers can be numerous and individually expensive to evaluate;
Remote Config providers are effectively always one, and the whole snapshot is small.

Authentication matches `GET /api/v1/flags` (authenticated session required), and the response when
`CaptureCategory.STATE` is disabled matches whatever that route already returns, rather than
inventing a second convention for the same category.

## Surfaces

### Browser dashboard

- New `viewRemoteConfig` rail button in `index.html`, grouped with "State & flags".
- A view section with a provider header (last fetch, status, minimum interval) and a table of
  Key / Value / Source, with a source badge per row.
- `dashboard.js` fetches `/api/v1/remote-config` and wires into the existing view-switch and refresh
  machinery. `dashboard.css` gains `.badge-remote` / `.badge-default` / `.badge-static` /
  `.badge-override` on top of the existing badge classes.
- A client-side key filter input, since real Remote Config sets get large.

### Compose inspector

- UI-shaped mirror types `InspectorRemoteConfigUi` and `InspectorRemoteConfigProviderUi` in
  `InspectorDataSource.kt`, carrying no `:sdk:remote-config` dependency — the precedent set by
  `InspectorBodyKind` mirroring `network.BodyPreview`.
- `InspectorSnapshot` gains `remoteConfig: List<InspectorRemoteConfigProviderUi> = emptyList()`. The
  default keeps every existing fake and adapter compiling unchanged.
- Rendered on the **Control** screen, where feature flags already live, as a `CollapsibleSection`:
  header shows provider id, last fetch, and status; rows show key, value, and source badge. Key
  filtering reuses `InspectorDetailSearchField`.
- `FullInspectorDataSource` populates it from the redacted registry, honoring the STATE gate.

### Empty and error states

Four distinct states, none of which may render as a blank page:

1. No provider registered → "No Remote Config provider registered", linking to the docs.
2. `CaptureCategory.STATE` disabled → the same shape the flags surface already uses.
3. Provider threw → `unavailableReason` surfaced as a warning row.
4. Zero keys / never fetched → "No values" plus an explicit "last fetch: never". This is the
   `NO_FETCH_YET` case and the single most common real-world confusion, so it is called out rather
   than shown as an empty table.

## Gradle plugin registration

A new protected adapter module must be registered in **four** places, or the release-variant
protection silently does not apply to it:

1. `FULL_RUNTIME_COORDINATE` regex (`DevConsoleVariantPolicyPlugin.kt`) — add
   `devconsole-remote-config-firebase`.
2. `FORBIDDEN_SIGNATURES` — add `DEVCONSOLE_ENABLED_REMOTE_CONFIG_FIREBASE_V1`.
3. `protectedDependencyPaths` convention — add `:sdk:remote-config-firebase`.
4. `NoopRuntimeExcludesFullModulesTest` — add the module and its noop twin.

`:sdk:remote-config` itself is **not** protected, exactly as `:sdk:push` is not: it is an inert data
model with no capture behavior.

## Testing

- **`:sdk:remote-config`** — registry rejects blank/duplicate ids; unknown id returns null; a provider
  that throws yields `unavailableReason` rather than propagating; truncation boundary.
- **`:sdk:remote-config-firebase`** — adapter driven by fake Firebase-shaped Kotlin objects with
  matching method names, the technique `FirebaseRemoteMessageAdapterTest` already uses, so no
  Firebase dependency enters the test classpath. Covers all three sources, all four fetch statuses
  including `SUCCESS = -1`, `fetchTimeMillis = -1` → `null`, absent methods → `UNKNOWN`, and a
  malformed object that would throw.
- **`:sdk:remote-config-firebase-noop`** — returns the disabled-build snapshot and does not reflect,
  mirroring `FirebaseRemoteMessageAdapterNoopTest`.
- **`:sdk:full`** — redaction applied on both the HTTP and in-process paths; STATE gating added to
  `CaptureCategoryGatingTest`; `FullInspectorDataSource` mapping.
- **`:sdk:server-ktor`** — route JSON shape, auth required, category-gated response.
- **`gradle-plugin`** — the extended `NoopRuntimeExcludesFullModulesTest`.

## Documentation and CI

- New `docs/REMOTE_CONFIG.md`; linked from `docs/README.md`.
- Row added to `docs/PROTOCOL_REFERENCE.md`; cross-reference from `docs/STATE_AND_FLAGS.md`.
- README feature list and `CHANGELOG.md`.
- `./gradlew apiDump` for the three new modules, which carry `api/*.api` baselines.
- New Kotlin files carry the `@author` / `@since 13/08/26` header the repo requires.
- `settings.gradle.kts` include lines; each module applies `devconsole.android.library`,
  `devconsole.quality`, and `devconsole.publishing`.

Verification commands: `./gradlew build`, `./gradlew testDebugUnitTest`,
`./gradlew ktlintCheck detekt`, `./gradlew apiCheck`, `./gradlew -p gradle-plugin test`, and — because
this touches a protected-variant boundary — the three sample `assembleDebug` / `assembleRelease` /
`verifyDevConsoleProtectedArtifacts` runs.

## Deferred: override editing

The natural follow-up is a session-scoped override layer letting QA force a value without publishing
to the Remote Config console. It is deliberately out of scope here because it needs machinery this
change does not: hosts must read values back through DevConsole for an override to affect behavior
(the `featureFlagValue` problem), plus an `EditingCapabilities` gate, command-audit records, timeline
events, and session-integrity reporting.

The `OVERRIDE` enum case and the `RemoteConfigProvider` interface are shaped so that work lands
additively rather than as a breaking change.
