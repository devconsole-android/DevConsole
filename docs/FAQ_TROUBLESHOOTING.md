# FAQ / troubleshooting

**Can I connect from another machine on the same network?** Yes — pass
`BindingMode.LAN` to `startBrowser(...)` and open the connect URL (or scan the QR code) from
another device on the same network. Loopback + `adb reverse` is still the safer default; only use
LAN mode on a network you trust, since the dashboard speaks plaintext HTTP. See
[LAN_PERMISSION_AND_TROUBLESHOOTING.md](LAN_PERMISSION_AND_TROUBLESHOOTING.md) and
[THREAT_MODEL.md](THREAT_MODEL.md).

**Why does my release build still contain `sdk:network`/`sdk:state`/... classes?** Those five
domain modules (network, socket, push, state, mocks) are pure-Kotlin contract types with no
Android/OkHttp/Ktor dependency, and `sdk:api` depends on them directly so the public facade can
expose typed methods like `networkRecorder()`. Their classes are present but genuinely inert in
`devconsole-noop` — no redaction, capture, or storage code runs. The full embedded server,
dashboard assets, and storage layer live only in `sdk:full`, and `verifyDevConsoleProtectedArtifacts`
checks specifically for a dependency on that module, not for the absence of every DevConsole class.
See [BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md](BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md).

**I toggled a feature flag in the dashboard but my app didn't change.** Flags are read, not pushed
— call `DevConsole.featureFlagValue(key)` from your own code at the point you'd otherwise check a
local flag. There's no change notification; re-check after whatever user action would plausibly
follow a QA override. See [STATE_AND_FLAGS.md](STATE_AND_FLAGS.md).

**Can I register a state provider or feature flag after `initialize()`?** No, by design — both are
declared once via `DevConsoleConfig` at `initialize()` time, to avoid a race between a late
registration and the dashboard having already read an earlier snapshot.

**A mock rule isn't matching.** Check the Mocks page's conflict detector first — a
higher-priority or more-specific rule may be winning instead. Matching is deterministic: highest
priority wins, ties broken by specificity, then by creation order. See
[COMPOSER_AND_MOCKS.md](COMPOSER_AND_MOCKS.md).

**My WebSocket/mock/network interceptor doesn't do anything in release builds.** That's correct —
it's still wired in your `OkHttpClient.Builder`, but every recorder/engine behind it is
`enabled = false` in `devconsole-noop`, so it becomes a fast no-op rather than something you need to
conditionally remove.

**My session code says expired or invalid, and a second browser can't join.** Codes are single-use
and one is live at a time with a 5-minute TTL — there is no on-device approval step and no automatic
regeneration, so issue a fresh code from the device (More screen) for each new browser. See
[LAN_PERMISSION_AND_TROUBLESHOOTING.md](LAN_PERMISSION_AND_TROUBLESHOOTING.md#session-codes-session_code_expired--session_code_invalid).

**Where do I report a security issue?** Not in a public issue — see [SECURITY.md](../SECURITY.md).

**Something else is broken.** Check the module-specific guide first (
[NETWORK_INSPECTOR.md](NETWORK_INSPECTOR.md),
[WEBSOCKET_INSPECTOR.md](WEBSOCKET_INSPECTOR.md),
[PUSH.md](PUSH.md),
[COMPOSER_AND_MOCKS.md](COMPOSER_AND_MOCKS.md),
[STATE_AND_FLAGS.md](STATE_AND_FLAGS.md)) — each documents the exact capture/redaction bounds and
known gaps for that feature.
