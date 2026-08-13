# Remote Config

A read-only view of the Remote Config values active on the device: every key, its current value, and
— the part that actually matters — **where that value came from**.

Knowing a flag reads `false` is rarely the hard part. The hard part is knowing whether it reads
`false` because that is what you published, or because the last fetch was throttled and the app fell
back to an in-app default. Those two situations look identical from inside the app and are the
difference between "the rollout works" and "the rollout never reached this device".

## Registering a provider

```kotlin
DevConsole.initialize(
    application,
    DevConsoleConfig(/* … */)
        .withRemoteConfigProviders(listOf(FirebaseRemoteConfigAdapter(Firebase.remoteConfig))),
)
```

`FirebaseRemoteConfigAdapter` takes the `FirebaseRemoteConfig` instance as `Any` and reads it by
reflection, so DevConsole has **no compile-time Firebase dependency** — Firebase stays off the
classpath of consumers that do not use this adapter, exactly like `FirebaseRemoteMessageAdapter` for
Cloud Messaging. Add the adapter coordinate yourself; it is deliberately not re-exported by the
`devconsole` umbrella.

```kotlin
debugImplementation("io.github.devconsole-android:devconsole-remote-config-firebase:<version>")
releaseImplementation("io.github.devconsole-android:devconsole-remote-config-firebase-noop:<version>")
```

A provider constructed later — through DI, or after a first fetch completes — can register itself:

```kotlin
DevConsole.registerRemoteConfigProvider(FirebaseRemoteConfigAdapter(Firebase.remoteConfig))
```

Late registration is supported here (unlike feature flags) because snapshots are pulled on demand
rather than captured at startup, so there is no race with a dashboard that already read an earlier
list. It returns `false` when the runtime is disabled or `CaptureCategory.STATE` is off.

## Value sources

| Source | Meaning |
| --- | --- |
| `remote` | Fetched from the server and activated. |
| `default` | An in-app default (`setDefaultsAsync`); no server value is active for this key. |
| `static` | Set nowhere — the SDK's static fallback for an unknown key. |
| `override` | A local override is masking whatever the provider resolved. |
| `unknown` | The provider could not attribute the value. Never guessed at. |

Alongside the values, each provider reports its last fetch time, last fetch status (`success`,
`no_fetch_yet`, `failure`, `throttled`, `unknown`), and configured minimum fetch interval. A provider
that has never fetched reports a **null** timestamp and is rendered as "never" — never as an epoch
date.

## Another Remote Config vendor

The model is vendor-neutral. Implement `RemoteConfigProvider` for any other service:

```kotlin
class ConfigCatProvider(private val client: ConfigCatClient) : RemoteConfigProvider {
    override val id = "configcat"

    override fun snapshot() = RemoteConfigSnapshot(
        providerId = id,
        entries = client.allValues().map { (key, value) ->
            RemoteConfigEntry(key, value.toString(), RemoteConfigSource.REMOTE)
        },
        fetchInfo = RemoteConfigFetchInfo(
            lastFetchEpochMs = client.lastRefreshMillis(),
            status = RemoteConfigFetchStatus.SUCCESS,
            minimumFetchIntervalSeconds = null,
        ),
    )
}
```

`RemoteConfigRegistry` normalizes whatever you return: it caps values at 8 KB (setting
`truncated`), forces the registered id onto the snapshot, and — because a debugging tool must never
crash the app it exists to observe — converts a provider that throws into an `unavailableReason`
rather than propagating the exception.

## Redaction

Values are redacted by key name at a single boundary in `:sdk:full`, so the browser dashboard and the
on-device inspector both receive already-redacted entries. The key itself is never redacted: knowing
*which* value was withheld is the point of the view.

Matching is separator-insensitive, which the raw `RedactionPolicy` is not. Its
`sensitiveFieldNames` defaults are an HTTP-header list (`api-key`, `apikey`) compared by exact name,
while Remote Config keys are written snake_case and camelCase — so `api_key` and `apiKey` are matched
against the policy's `api-key` here, instead of being displayed in full. Values are also run through
the engine's text patterns, so a bearer token pasted into an otherwise innocuous key is still caught.

This remains allowlist-based: a key holding a secret under a name nobody thought to list is still
shown. Add your own names through `RedactionPolicy.sensitiveFieldNames`.

## Dashboard and inspector

The browser dashboard's **Remote Config** page (under Data, next to State & flags) lists each
provider with its fetch line and a Key / Value / Source table, filterable by key and by source. The
Compose inspector shows the same data as a section on its **Control** screen, beside feature flags.

Both distinguish four states explicitly rather than rendering a blank table: no provider registered,
capture category disabled, provider unavailable, and "fetched nothing / never fetched".

## Scope

This inspector is **read-only**. DevConsole does not set overrides and does not trigger `fetch()` or
`activate()` — it observes the Remote Config lifecycle rather than driving it. The `override` source
is displayed when a host has applied one of its own.

## REST

`GET /api/v1/remote-config` (auth required, gated by `CaptureCategory.STATE`) returns every
provider's snapshot:

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

See [PROTOCOL_REFERENCE.md](PROTOCOL_REFERENCE.md) and
[STATE_AND_FLAGS.md](STATE_AND_FLAGS.md) for the neighbouring surfaces.
