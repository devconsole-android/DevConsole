# State and feature flags

Both are declared once, at `initialize()` time, via `DevConsoleConfig`. State providers can also be
registered later via `DevConsole.registerStateProvider()`, gated by the `CaptureCategory.STATE`
permission. Feature flags, however, are fixed at initialization — late registration is not supported
for flags to avoid a race between a late registration and the dashboard already having read an
earlier snapshot.

## State providers

```kotlin
DevConsole.initialize(
    application,
    DevConsoleConfig(
        stateProviders = listOf(
            stateProvider("cart") {
                StateSnapshot(mapOf("itemCount" to StateValue.NumberValue(cart.size)))
            },
        ),
    ),
)
```

A provider is evaluated on demand, when the dashboard's State & Flags page requests it — the SDK
never reflects over arbitrary application objects; you decide exactly which fields exist in the
snapshot. `StateValue` covers `Null`, `BooleanValue`, `NumberValue`, `StringValue`, `ObjectValue`,
`ArrayValue`, `Redacted`, `Unavailable(reason)`, and `BinaryMetadata` — use `Redacted`/`Unavailable`
for anything sensitive or not currently computable rather than omitting the key silently.

State is read-only by default. A `StateProvider.mutators` list can expose explicit,
narrowly-scoped `StateMutator` commands (each with its own `id` and JSON input schema) if your
application wants the dashboard to trigger a specific, host-approved mutation — there is no generic
"set any field" mutation path.

## Feature flags

```kotlin
DevConsoleConfig(
    featureFlags = listOf(
        FeatureFlag(key = "sample.show_extra_panel", defaultValue = false),
    ),
)
```

Read the *current* value — including any dashboard override — from your own code with:

```kotlin
val enabled = DevConsole.featureFlagValue("sample.show_extra_panel")
```

Without this read-back, a dashboard override would only ever change what the dashboard displays,
never your application's actual behavior — call `featureFlagValue` wherever you'd otherwise check a
local flag, and re-check it after any user action that might have triggered a dashboard override
(there's no push notification when a flag changes; polling on the next relevant check is the
supported pattern, matching the sample apps).

An override never mutates the flag's declared default — it's a session-scoped layer on top,
cleared when the session ends. `FeatureFlag.allowedValues` restricts what a dashboard override can
set (defaults to `{true, false}`); `mutable = false` makes a flag read-only even from the dashboard.

## Dashboard

The State & Flags page shows a provider tree with sensitivity badges, and a flag table with
source/default/current/override columns plus a "reset all session overrides" command. Every flag
change produces both a command-audit record and a timeline event with the before/after values.

## Remote Config

Values fetched from a Remote Config service are inspected separately, with source attribution
(remote / default / static / override) and fetch metadata — see [REMOTE_CONFIG.md](REMOTE_CONFIG.md).
It shares the `CaptureCategory.STATE` gate with everything on this page.

## Flags with named options (environment switching)

A flag is not limited to on/off. `FeatureFlag.ofOptions` declares a set of named values, which is
the shape an environment or account-tier switcher needs:

```kotlin
DevConsole.initialize(
    application,
    DevConsoleConfig(
        featureFlags =
            listOf(
                FeatureFlag.ofOptions(
                    key = "api.environment",
                    defaultValue = "staging",
                    options = setOf("staging", "production"),
                    description = "Backend the app talks to",
                ),
                FeatureFlag.ofBoolean("checkout_v2", defaultValue = false),
            ),
    ),
)
```

Read the current value with `DevConsole.featureFlagValue(key)` for booleans, or through your own
`FeatureFlagProvider` for string flags. Overriding from the dashboard is a `POST /api/v1/flags/{key}`
with the bare value in the body — `production`, or `true` for a boolean.

Overrides are session-scoped: they never mutate the host's declared default, they are reported by
`GET /api/v1/session/integrity`, and they are cleared by
`POST /api/v1/session/integrity/reset`. Any bug report captured while an override is live records
that fact, which is the difference between a reproducible bug and a wasted afternoon.
