# Changelog

Notable changes to the DevConsole SDK. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[semantic versioning](https://semver.org/) once 1.0.0 ships.

## Versioning and stability policy

Until **1.0.0**, the public API may change in any release — that is the point of the pre-1.0 window,
and breaking changes made now are cheaper than the ones made later.

`sdk:api` carries a committed ABI checked on every build, along with `mocks`, `network`, `push`,
`security`, `socket`, and `state`. Any change to those surfaces shows up as a failing `apiCheck` and
must be accepted deliberately with `./gradlew apiDump`. The remaining Android library modules are
not yet gated; see the comment on the `apiValidation` block in the root build file for why. (There
was briefly a separate `sdk:plugin-api` module for a generic third-party plugin framework; it was
removed before ever shipping — see Removed, below — so it never joined this list.)

From 1.0.0 onward: breaking changes to `sdk:api` require a major version, new API requires a minor,
and everything else is a patch.

## Unreleased

## 0.2.0 — 2026-08-07

A follow-up release driven by a full independent audit of the SDK and browser dashboard. It adds
the open triggers and More-screen server controls that were pending, and fixes a set of
correctness and security issues that audit surfaced. No `sdk:api` breaking changes.

### Added

- **Opt-in open triggers.** `DevConsoleConfig.openTriggers` lets the SDK itself open the in-app
  inspector on a device shake (`ShakeIntensity` — `LIGHT`/`MEDIUM`/`FIRM` — sets how hard) or from a
  draggable floating button. Both default to off, and neither has any path to starting the embedded
  server — triggers only open the inspector UI.
- **Start/Stop the server from the inspector's More screen.** A dedicated control card at the top
  of More (the same Start/Stop pair the sample apps put on their home screens) now drives the real
  server lifecycle: `sdk:full` wires `InspectorDataSource.setServerRunning` through to
  `startBrowser`/`stop`. The card — like the hero CTA before it — only renders on builds that
  actually wire the control.

### Changed

- **ANR detection now starts when the on-device inspector is opened**, in addition to when the
  browser server starts — so hosts that use only the on-device inspector (never calling
  `startBrowser`) now get ANR capture.
- **Feature-flag override audit records now show the real before/after values** (redacted by the
  flag key as a field name, so a flag named like a secret still masks), instead of a fixed
  `<redacted>` placeholder for both.

### Security

- **Browser feature-flag overrides are now gated server-side** by the `featureFlags` editing
  capability and the `STATE` capture category. Previously the `POST /api/v1/flags/{key}` route
  enforced neither, so any authenticated browser could flip a declared flag even on a read-only
  config.
- **Diagnostic exports re-apply the current redaction policy at export time.** HAR, Postman, the
  session ZIP, and the evidence bundle no longer rely solely on capture-time redaction.
- **Composer requests are refused when the target host resolves to a loopback, link-local (incl.
  the cloud metadata address), site-local, or IPv6 unique-local address**, unless private-network
  targets are explicitly permitted — hardening against SSRF via an allowlisted hostname. (A live
  TTL-0 DNS-rebinding attacker is only partially mitigated; see the transport's docs.)
- **MQTT topics are now redacted** with the same policy as other captured fields.

### Fixed

- **Binary request/response bodies are no longer corrupted** when captured as attachments (a lossy
  UTF-8 round-trip mangled non-text bodies; downloads from the dashboard/session ZIP now match the
  original bytes).
- **The OkHttp interceptor no longer blocks the response** on chunked / unknown-length (e.g.
  long-poll) streams while trying to buffer a body preview.
- **WebSocket connection state is no longer corrupted** when a `DevConsoleOkHttpWebSocketListener`
  and `DevConsoleRecordingWebSocket.wrap` are wired on the same socket (the documented pattern).
  The OkHttp listener also gained an optional host `WebSocketListener` delegate.
- **Gradle plugin:** zero-config auto-wiring now targets the released SDK coordinate (it previously
  defaulted to a nonexistent `-SNAPSHOT`, breaking the headline setup); applying it to an
  unsupported `com.android.dynamic-feature` module now fails loudly instead of silently skipping
  protection; and declaring only an add-on coordinate no longer suppresses auto-wiring of the core
  runtime.
- **Browser session-ZIP exports now include** `network.har`, `network.postman_collection.json`, and
  app metadata, matching the on-device export.
- **Crash/ANR breadcrumbs now include recent log lines**, and a failure inside ANR capture can no
  longer crash the host process.
- **HAR/Postman export correctness:** the HAR `content.size` field is emitted, `statusText`/`status`
  use the HTTP reason phrase instead of the captured error text, `redirectURL` is populated from the
  `Location` header, and a fully-redacted `Cookie` header no longer produces a bogus cookie entry.
- **Dashboard:** an expired session now shows a clear "get a fresh connect code" state instead of
  reconnecting forever; the mock-rule editor refuses to overwrite a fault-injection rule with an
  empty response; live-tail reconciles the gap after a reconnect.
- **Storage retention** no longer runs a full-table scan on every write, and its byte accounting is
  more accurate; a stale usage estimate could previously let the store grow past its cap across a
  stop/start cycle.
- **Docs: the port-bridging command is `adb forward`, not `adb reverse`.** Plus corrections to the
  session TTL, storage retention, WebSocket binary-preview size, MQTT redaction, SQL-console
  `sqlite_master` access, `registerStateProvider`, the migration plugin id, and the Java sample
  snippet's Java-11 compatibility.

## 0.1.0 — 2026-08-07

First public release.

This window includes a from-scratch redesign of how a browser connects (auth) and how the host
starts the server (facade), the removal of the generic plugin framework, and full browser/device
parity for app-sandbox data (preferences, database, files) with matching export formats. None of
this has shipped, so it is described here as the current end state rather than as a sequence of
intermediate steps.

### Added

- **SESSION_CODE is the only access flow.** `SessionCodeAuthority.issueCode()` generates an
  8-character, unambiguous-alphabet, single-use code with a 5-minute TTL; presenting it to `POST
  /api/v1/auth/session-code/exchange` within that window creates a full session immediately — no
  on-device approval step, no notification to tap, no fallback on expiry. Every authenticated
  session is equivalent (no `READ_ONLY`/`CONTROL`/`ADMIN` tiers); mutations instead gate on the
  host's per-feature `EditingCapabilities` (`preferences`, `database`, `files`, `mocks`,
  `captureRules`, `featureFlags`, `requestExecution`). Revocation is symmetric — any session can
  list and revoke any other. The connect URL/code never reaches Logcat; it is surfaced only through
  `AccessInfo` and the device's More screen (as text and as a QR code). See
  [THREAT_MODEL.md](docs/THREAT_MODEL.md) and
  [PROTOCOL_REFERENCE.md](docs/PROTOCOL_REFERENCE.md#2-auth-handshake-session_code).
- **Data parity: preferences, database, and files inspectors**, mirrored between the in-app
  inspector and the browser dashboard, each gated by its own `EditingCapabilities` flag. The files
  inspector covers all four app-sandbox roots (`files`, `cache`, `external-files`, `no-backup`)
  with create/replace/rename/delete/download plus a native Android Share action. See
  [DATA_INSPECTORS_AND_EXPORTS.md](docs/DATA_INSPECTORS_AND_EXPORTS.md).
- **Unified exports.** HAR and Postman Collection v2.1 network exports, plus a full Android session
  ZIP (`ExportSelection`-driven, shared between the browser `POST /api/v1/exports` route and the
  in-app `InspectorExporter`) bundling timeline, network trail, attachments, and app metadata in
  one artifact.
- **Capture-exclusion rules.** A `CaptureRule` (host + optional method/path prefix) excludes
  matching requests from network capture entirely, before redaction or storage — distinct from a
  mock, which still records the mocked exchange. Gated by `EditingCapabilities.captureRules`.
- **Device More screen.** SDK health, connected browser sessions with per-session revoke, retention
  usage, a persistent LAN-mode warning, export controls, and the session-code QR — one place to
  operate a running server without a connected browser.
- **`DevConsole.featureFlagStringValue(key)`** reads a multi-valued flag's effective value
  (`FeatureFlag.ofOptions`), alongside the existing boolean accessor.
- **Log capture.** `DevConsole.logRecorder()` records host log lines onto the timeline, redacted and
  bounded, alongside the network calls and crashes around them.
- **Crash and ANR capture.** Uncaught exceptions and main-thread stalls become timeline events.
  Crashes are persisted synchronously, and the handler always delegates to whatever crash reporter
  was already installed.
- **Durable timeline.** Events are written through to Room, so a crash no longer erases the history
  leading up to it. Growth is bounded by `EventQuotaPruner`.
- **Session integrity and bug reports.** `GET /api/v1/session/integrity` reports active mock rules
  and feature-flag overrides; `POST /api/v1/session/integrity/reset` returns the session to a clean
  state. `GET /api/v1/report` bundles the recent timeline, network trail, app/build info, and SDK
  health as one downloadable artifact.
- **Ktor client adapter** (`sdk:network-ktor`), engine-independent.
- **Zero-config initialization** on debuggable builds; the endpoint (never the credential) is logged
  under the `DevConsole` tag.
- **Durable, server-side evidence tray.** Flagged QA items (network, timeline, socket, push,
  screenshot, crash) and their bug-report draft now live in Room, not a browser-local `Map` — a
  refresh or app restart loses nothing. The subject is **materialized once, at flag time**, from the
  same already-redacted sources the detail endpoints use, so a report built later says exactly what
  it said when flagged, rather than degrading once the underlying list moves on. Every crash and ANR
  auto-flags itself. Caps: 200 items/session, 256 KiB per snapshot (truncated with an explicit
  marker beyond that), 4096 characters per report text field, 512-character labels. New routes under
  `/api/v1/evidence`; a new `ExportScope.Evidence` bundle (`report.md`/`report.json`, HAR/Postman of
  flagged network items, attachments, and a manifest recording per-file redaction applicability) rides
  the existing `POST /api/v1/exports`; three client-side clipboard formats (Markdown, Jira wiki
  markup, GitHub issue). See [EVIDENCE_AND_BUG_REPORTS.md](docs/EVIDENCE_AND_BUG_REPORTS.md).
- **Opt-in screenshot capture**, off by default. `DevConsole.captureScreenshot()` captures the
  foreground `Activity` window via `PixelCopy`; a `FLAG_SECURE` window is refused
  (`ScreenshotResult.SecureWindow`), never captured as a black rectangle. A screenshot cannot be
  redacted, so it is stored with a new `RedactionApplicability.NOT_APPLICABLE` marker rather than
  falsely claiming redaction — that field travels through `StoredAttachment`, an
  `X-DevConsole-Redaction-Applicability` response header, and an UNREDACTED badge everywhere the
  dashboard shows such an attachment. **Read
  [THREAT_MODEL.md](docs/THREAT_MODEL.md#screenshots-are-unredactable-by-construction) before
  enabling this** — the dashboard serves over plaintext HTTP, so a captured screenshot crosses the
  network exactly as unredacted as it looks.
- **Deeper crash/ANR capture.** ANRs now report a bounded all-thread dump (main thread first, then
  the rest by name), not just the main thread's own stack, with every cap's truncation marked
  explicitly in the text rather than dropped silently. Crash and ANR payloads carry bounded
  breadcrumbs (already-redacted timeline summaries, no payload bodies). A new `CrashPolicy` exposes
  `anrThresholdMs` (default 5000ms — this is a main-looper heartbeat, not the platform's own ANR
  signal), `breadcrumbDepth`, `maxStackChars`, `maxThreadsInDump`, and `maxFramesPerThread`, plus the
  existing enable/disable gates for crash capture and the ANR watchdog. See
  [CRASH_AND_ANR.md](docs/CRASH_AND_ANR.md).
- **A first-class Crashes surface** on both the dashboard (Signals group, visible in Simple mode) and
  the Android in-app inspector (`ObserveTab.CRASHES`, the fifth Observe tab). The Overview shows a
  banner when the previous run crashed, backed by a new read-only `GET /api/v1/runs` route.
- **Mobile-first responsive pass and Simple/Advanced density.** The dashboard's breakpoints are now a
  single documented, width-descending ladder (see `DESIGN_SPEC.md` §3.4) instead of ad hoc numbers
  scattered through the stylesheet; no horizontal overflow at 375/768/1024 on any view. Simple mode
  now relaxes density (type scale, row height) via CSS custom properties; Advanced mode is
  byte-identical to the previous hard-coded values.
- **Windowed rendering for Network, Timeline, Sockets, and Push.** DOM node count stays bounded
  regardless of session length, with incremental scroll-triggered append behind the existing
  Older/Newest pager.

### Changed

- **Facade reshape.** `DevConsole.startBrowser(request)` / `startBrowserAsync(callback)` replace the
  old `start()`/`startAsync()`; a successful call returns `StartResult.Started(endpoint:
  BrowserEndpoint, access: AccessInfo)`. `initialize()` only initializes — the server is never
  auto-started, so every host now calls `startBrowser` explicitly. `BindingMode` is `LOOPBACK` or
  `LAN` only; `BindingMode.AUTO` and NSD discovery are gone.
  `DevConsoleConfig` gained `composerEnabled`, `composerAllowedHosts`, `stateMutationsEnabled`, and
  `redactionPolicy`, and these now actually reach the running server — the Composer, push
  simulation, and state mutations were previously reachable only from test code, and a custom
  redaction policy could not be injected at all.
- **Dashboard assets split.** The dashboard is served as `index.html` plus
  `/assets/dashboard.css` and `/assets/dashboard.js`, under a tightened CSP (`script-src 'self'`)
  instead of one inline-scripted document.
- **Coordinates moved to `io.github.devconsole-android`** (group and the `io.github.devconsole-android`
  Gradle plugin id), a namespace this project can actually claim; `io.devconsole` was never publishable.
- **Publishing is Maven Central-ready.** Every publishable module now emits a sources jar and a
  javadoc jar, a POM with license/developer/SCM metadata, and a signature when a signing key is
  supplied (a keyless local `publishToMavenLocal` still works).
- **The variant policy is safe by default.** Release variants are protected out of the box and debug
  gets the real runtime; the protection check now inspects the fully resolved runtime classpath, so
  a full runtime arriving transitively is caught, not just a directly declared one.
- **The SDK is installable.** Every module the umbrella depends on is now published; previously
  `io.devconsole:devconsole` referenced ten artifacts under a group that was never published.
- **Two coordinates**: `io.github.devconsole-android:devconsole` (debug) and
  `io.github.devconsole-android:devconsole-noop` (release).
- **`minSdk` is 24.**
- **The Composer is off by default.** It can make the device issue arbitrary outbound requests, so
  the browser route now requires `composerEnabled`, with an optional host allowlist; the in-app
  composer UI is instead gated by `EditingCapabilities.requestExecution`.
- **Redaction** covers 25 field names, considerably more than before, and custom `RedactionPolicy`
  field names now match case-insensitively.

### Removed

- **The public plugin framework** — the `plugin-api` module, `DashboardExtension`, and every
  `/api/v1/plugins/*` route except the (renamed-in-purpose-only) command audit log at `GET
  /api/v1/plugins/audit`, kept at that path because renaming a route existing browser clients
  already consume is a protocol break for no gain. `pluginId` survives on events only as a plain
  source tag, with no extension surface behind it. State views, `CommandConcurrency`,
  `AuditRedaction`, and `DashboardPresentation` are gone with it.
- **Pairing, on-device approval, and access tiers.** The bootstrap-secret pairing flow, the
  notification-based approval step, `READ_ONLY`/`CONTROL`/`ADMIN`, and the human-readable
  device-identification code are all gone, replaced by the single SESSION_CODE flow described
  above. `matchesHumanCode`, which nothing called even before the removal, went with it.
- **Zero-config auto-start**, `BindingMode.AUTO`, and NSD service discovery — see Changed, above.
- **The Room-inspector sample app.**
- **`sdk:full` no longer re-exports** `ui-views`, `ui-compose`, or `push-firebase`, which dragged
  the Compose runtime and Firebase Messaging onto every consumer.

### Fixed

- **`startBrowser()` no longer risks an ANR.** The blocking bind loop runs on `Dispatchers.IO`, so a
  host launching it from a Main-thread coroutine does not stall the UI thread.
- **Timeline persistence survives a stop/start cycle.** `stop()` no longer tears down the event
  batch writer, which could not be restarted and silently ended persistence after the first restart.
- **A start can be retried after `PermissionRequired`/`Failed`.** Granting the local-network
  permission and calling `startBrowser` again now works instead of stranding the runtime.
- **Constant-time credential comparison.** Session codes, session tokens, and CSRF tokens are
  compared with `MessageDigest.isEqual`, closing a byte-at-a-time timing oracle over the LAN.
- **The session-code exchange is rate-limited per source IP** (5/min) so it cannot be used as an
  online-guessing oracle against the 8-character code space.
- Browser sessions no longer survive `stop()`.
- Network capture runs off the host's request thread.
- JSON responses escape control characters below `0x20`, so third-party header content cannot break
  the dashboard.
- Sample release builds compile again (adapters are declared for all variants rather than inherited
  from a debug-only module).
- A host calling `initialize()` with state providers/flags after auto-initialization no longer
  receives `InitResult.Conflict` with its configuration silently dropped.
- Repeated response headers such as `Set-Cookie` are folded rather than collapsed to the last value.
