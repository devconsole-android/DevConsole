# FAQ / troubleshooting

**Can I connect from another machine on the same network?** Usually with no setup at all — the
default `BindingMode.AUTO` binds your device's network address whenever there is one, so the connect
URL and its QR code already work from another device. If you got a `127.0.0.1` URL, AUTO fell back
(no eligible interface, or an ungranted `ACCESS_LOCAL_NETWORK` on API 37+); Logcat names the reason.
Pass `BindingMode.LAN` to make that a hard failure instead of a fallback. Because the dashboard
speaks plaintext HTTP, pass `BindingMode.LOOPBACK` and use `adb forward` on any network you do not
trust. See [LAN_PERMISSION_AND_TROUBLESHOOTING.md](LAN_PERMISSION_AND_TROUBLESHOOTING.md) and
[THREAT_MODEL.md](THREAT_MODEL.md).

**Do I need a second interceptor for mock rules?** No. `installDevConsole(...)` wires the mock
interceptor from the engine DevConsole publishes at `initialize`, so `DevConsoleMockInterceptor` no
longer needs to be added by hand. Keeping an existing manual line is harmless — the second
interceptor detects the first and stands down — but you can delete it. Mock editing is also on by
default now; `EditingCapabilities.readOnly()` turns it back off.

**Why does my release build still contain `sdk:network`/`sdk:state`/... classes?** Those five
domain modules (network, socket, push, state, mocks) are pure-Kotlin contract types with no
Android/OkHttp/Ktor dependency, and `sdk:api` depends on them directly so the public facade can
expose typed methods like `networkRecorder()`. Their classes are present but genuinely inert in
`devconsole-noop` — no redaction, capture, or storage code runs. The full embedded server,
dashboard assets, and storage layer live only in `sdk:full`, and `verifyDevConsoleProtectedArtifacts`
checks specifically for a dependency on that module, not for the absence of every DevConsole class.
See [BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md](BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md).

**I toggled a feature flag in the dashboard but my app didn't change.** Flags are pulled, not
pushed. Call `DevConsole.featureFlagValue(key)` wherever you'd otherwise check a local flag. There
is no change notification, so re-check after whatever user action would plausibly follow a QA
override. See [STATE_AND_FLAGS.md](STATE_AND_FLAGS.md).

**Can I register a state provider or feature flag after `initialize()`?** No, and that's deliberate.
Both are declared once through `DevConsoleConfig` at `initialize()` time, which avoids a race
between a late registration and a dashboard that already read an earlier snapshot.

**Shaking the device does nothing, and there's no floating button.** Both triggers are off by
default. Turn them on with
`withOpenTriggers(OpenTriggers(shakeToOpen = true, floatingButton = true))` at `initialize()` time,
and tune how hard a shake has to be with `ShakeIntensity` (`LIGHT`, `MEDIUM`, or `FIRM`). Either
trigger only opens the in-app inspector. Neither ever starts the server.

**A mock rule isn't matching.** Check the Mocks page's conflict detector first, since a
higher-priority or more specific rule may be winning. Matching is deterministic: highest priority
wins, ties break by specificity, then by creation order. Also check that the global switch is on;
it and the per-rule toggles are independent. See [COMPOSER_AND_MOCKS.md](COMPOSER_AND_MOCKS.md).

**My WebSocket, mock, or network interceptor does nothing in release builds.** That's the intent.
It's still wired into your `OkHttpClient.Builder`, but every recorder and engine behind it is
`enabled = false` in `devconsole-noop`. It becomes a fast no-op, so you never have to strip it out
conditionally.

**My session code says expired or invalid, and a second browser can't join.** Codes are single-use,
only one is live at a time, and each lasts five minutes. There's no approval step on the device and
no automatic regeneration, so issue a fresh code from the More screen for each new browser. See
[LAN_PERMISSION_AND_TROUBLESHOOTING.md](LAN_PERMISSION_AND_TROUBLESHOOTING.md#session-codes-session_code_expired--session_code_invalid).

**My debug build fails with a manifest merger conflict on `androidx.core.content.FileProvider`.**
Upgrade to 1.2.4, where the SDK declares its provider as `io.devconsole.DevConsoleFileProvider`
instead. The merger keys `<provider>` nodes by `android:name` rather than by authority, so through
1.2.3 the SDK's provider and the one your app already declares — for camera capture, image picking,
or any share sheet — were the same node, and the merge failed on the differing `android:authorities`
and paths resource. Do not apply the `tools:replace="android:authorities"` the merger suggests: it
resolves the conflict by dropping `<applicationId>.devconsole.files`, so the Files screen's Share
action throws the first time someone taps it. If you cannot upgrade yet, give *your* provider a
unique class name instead — `class AppFileProvider : FileProvider()`, with `android:name` pointing
at it — which keeps your authority, your paths file, and every `getUriForFile` call exactly as they
are.

**Where do I report a security issue?** Privately, never in a public issue.
[SECURITY.md](../SECURITY.md) has the details.

**Something else is broken.** Check the module-specific guide first (
[NETWORK_INSPECTOR.md](NETWORK_INSPECTOR.md),
[WEBSOCKET_INSPECTOR.md](WEBSOCKET_INSPECTOR.md),
[PUSH.md](PUSH.md),
[COMPOSER_AND_MOCKS.md](COMPOSER_AND_MOCKS.md),
[STATE_AND_FLAGS.md](STATE_AND_FLAGS.md)). Each one spells out the exact capture and redaction
bounds for that feature, plus its known gaps.
