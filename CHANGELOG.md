# Changelog

Notable changes to the DevConsole SDK. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[semantic versioning](https://semver.org/) once 1.0.0 ships.

## Versioning and stability policy

Until **1.0.0**, the public API may change in any release — that is the point of the pre-1.0 window,
and breaking changes made now are cheaper than the ones made later.

Since 0.3.0, every published `sdk/` module carries a committed ABI baseline
(`sdk/<module>/api/<module>.api`) checked on every build. Any change to those surfaces shows up as
a failing `apiCheck` and must be accepted deliberately with `./gradlew apiDump`. (There was briefly
a separate `sdk:plugin-api` module for a generic third-party plugin framework; it was removed
before ever shipping — see Removed, below — so it never joined this list.)

From 1.0.0 onward: breaking changes to `sdk:api` require a major version, new API requires a minor,
and everything else is a patch.

## Unreleased

### Fixed

- **LAN mode silently served nobody on Android 17.** `LocalNetworkPermissionGate` required both
  `deviceApi >= 37` **and** `targetSdk >= 37` before asking for `ACCESS_LOCAL_NETWORK`, but the
  platform enforces Local Network Access on the device version alone. An app targeting API 35 on an
  Android 17 device was therefore told no permission was needed: `startBrowser` returned
  `StartResult.Started` with a real LAN endpoint, the socket bound, the port even completed TCP
  handshakes — and the platform dropped the traffic, so every dashboard request hung until it timed
  out. Every signal said success. The gate now keys on the device API only, so that host gets an
  actionable `StartResult.PermissionRequired` and the normal permission prompt instead of a dead
  server. Verified on an Android 17 device: with the permission denied the LAN start now prompts,
  and once granted the dashboard answers over LAN immediately.
- **The instrumented test that guards this rule could not run.** `sdk:full`'s androidTest APK
  declared no `INTERNET` permission, so `LocalNetworkPermissionInstrumentedTest` died at `EPERM`
  opening a `ServerSocket` before reaching its assertions — which is how a rule contradicted by its
  own test shipped. The androidTest manifest (test-only; never part of the published AAR) now
  declares it, and the test passes on a real API 37 device.

### Changed

- **The in-app inspector's Start button now honours `browserConfig.binding`.** `BrowserConfig.binding`
  existed on `DevConsoleConfig` but was documented as inert: the More screen's Start button always
  sent a default `StartRequest`, so it bound loopback no matter what the host had configured, and a
  host that wanted LAN could only get it by calling `startBrowser` from its own UI — leaving the same
  app handing out two different connect URLs depending on where you pressed start. That field now
  decides what an on-device start binds, along with its port range. Nothing about the defaults moved:
  `BrowserBinding.LOOPBACK` is still the default, LAN is still an explicit opt-in, and a LAN start
  from this surface passes through the same local-network permission gate as any other. The two
  binding settings remain independent by design — `StartRequest.bindingMode` governs starts the host
  issues, `browserConfig.binding` governs the ones it doesn't — and the KDoc on both, plus
  `docs/THREAT_MODEL.md`, now says so instead of describing the config field as unused.
- **Samples:** all three now declare `BrowserConfig(binding = BrowserBinding.LAN)`, matching the
  `BindingMode.LAN` their own Start buttons already passed, so both start paths agree. `compose-app`
  additionally gained a Ktor-on-CIO network demo exercising 0.4.0's response-body capture.

## 0.4.0 — 2026-08-08

Network capture closes its two body-capture gaps: chunked responses on OkHttp and response bodies
on Ktor, on every engine. The SDK also gets its own icon, and stops claiming a crash after runs
that did not crash.

### Added

- **DevConsole now has an icon, and uses it.** The mark ships as `drawable/devconsole_logo` in
  `sdk:full` (WebP, quality 90, five density buckets, transparent corners) and replaces the
  placeholder artwork everywhere one was standing in: the draggable open-trigger floating button was
  a grey circle reading "DC" and is now the mark itself, clipped and shadowed to its own rounded
  square; the keep-alive foreground-service notification borrowed
  `android.R.drawable.stat_sys_upload_done` and now carries the mark as its large icon plus a
  purpose-drawn monochrome `drawable/devconsole_notification` vector as the status-bar small icon
  (small icons are flattened to a tinted alpha mask by the platform, so the colour artwork cannot
  serve there). The web dashboard gained a favicon at `GET /assets/favicon.webp`, served
  unauthenticated alongside the CSS and JS for the same reason they are.

### Changed

- **OkHttp adapter now captures chunked/unknown-length response bodies.** Previously any response
  without a declared `Content-Length` (`Transfer-Encoding: chunked`, transparently-gzipped bodies,
  most long-poll/NDJSON feeds) was recorded metadata-only, because peeking an unbounded body could
  block the interceptor forever. `DevConsoleOkHttpInterceptor` now wraps such bodies in a
  non-blocking tee instead: bytes are copied into a bounded 512 KiB capture buffer as the host reads
  them, with no effect on what the host receives. The transaction records at body EOF or close,
  whichever comes first (`bodyOmittedReason` is `"truncated"` if the cap was hit, `"partial"` if the
  host closed the body before EOF); a body still open 500ms after `intercept()` returns gets a
  provisional metadata-only `"streaming"` record that the completion record later replaces in
  place, so a long-lived stream — or a body the host never reads or closes at all — still shows up
  instead of vanishing. `text/event-stream` responses are unaffected and stay immediate
  metadata-only. Adds an additive `NetworkTransactionRecorder.record(request, response, startedAt,
  completedAt, transactionId)` overload so an adapter can replace its own earlier record in place;
  `sdk:network`'s ABI baseline was updated for it.
- **Ktor adapter now captures response bodies, on every engine.** `DevConsoleKtorClientPlugin`
  previously never captured response bodies at all — Ktor's pipeline offered no non-consuming read
  at the stage it hooked. It now intercepts at `HttpReceivePipeline.After` and splits the raw
  response channel (the same mechanism `ResponseObserver` uses), draining one half into a bounded
  256 KiB capture buffer while handing the other back to the host untouched — saved-body
  double-reads and non-saved `DoubleReceiveException` behavior are both preserved. Binary content
  types, `text/event-stream`, `101 Switching Protocols` upgrades, and declared-oversized bodies stay
  metadata-only; everything else is captured up to the cap (`bodyOmittedReason = "too-large"` past
  it). This closes the capability gap the OkHttp-engine workaround documented in
  `docs/NETWORK_ADAPTERS.md` existed to route around — that section now only recommends the OkHttp
  engine when timing phases or mock rules are needed, not for bodies.

### Fixed

- **"Previous run crashed" no longer fires for runs that did not crash.** A process death cannot run
  `DevConsole.stop()`, so an ordinary exit — swiping the app away, the system reclaiming the process,
  Android Studio's stop button — leaves behind the same unclosed `ACTIVE` session row an uncaught
  exception does. The next launch closed *all* of them as `CRASHED`, so both the on-device Observe
  banner and the dashboard's Overview banner (which read that one stored status) claimed a crash on
  essentially every launch. A leftover run is now closed `CRASHED` only when it actually recorded a
  `"crash"` plugin event of type `"uncaught"`, and `COMPLETED` otherwise; ANR records under that same
  plugin no longer count, since a stall the run survived is not a crash. Deaths that leave no
  evidence at all (native crashes, low-memory kills) read as `COMPLETED`.

## 0.3.0 — 2026-08-08

A compatibility-focused release: the SDK and Gradle plugin now build against a broadly adopted
toolchain instead of the newest one, and the runtime floor drops to Android 6.0.

### Breaking

- The Java-friendly async facade methods (`DevConsole.startBrowserAsync`, `DevConsole.stopAsync`,
  `DevConsole.captureScreenshotAsync`) now take `io.devconsole.DevConsoleCallback<T>` instead of
  `java.util.function.Consumer<T>`. `Consumer` is API 24+ and would have required core-library
  desugaring at the new `minSdk 23`; `DevConsoleCallback` is a plain `fun interface`, so Java
  callers keep passing the same lambda shape (`result -> ...`) but must recompile.

### Changed

- `minSdk` lowered from 24 to 23. All API 24+ platform calls were replaced with minSdk-23-safe
  equivalents (`Map.putIfAbsent`/`getOrDefault`, `Comparator.reversed`,
  `AtomicLong.accumulateAndGet`, `java.util.Base64`, `java.time` in the HAR exporter) — no
  core-library desugaring required.
- Toolchain moved to a broadly available matrix: Gradle 8.14.3, AGP 8.13.0, Kotlin 2.2.20,
  `compileSdk`/`targetSdk` 35, OkHttp 4.12.0, Ktor 3.0.3, coroutines 1.9.0, Room 2.7.1,
  Compose BOM 2024.10.01.
- The published Gradle plugin resolves project-dependency paths reflectively so one artifact works
  on Gradle 8.9 through 9.x hosts (`ProjectDependency.getPath()` on 8.11+, with a
  `getDependencyProject()` fallback for 8.9/8.10). Note: the plugin's functional-test rig now runs
  on Gradle 8.14.3 + AGP 8.13.0, so the Gradle 9 / AGP 9 consumer path is covered by the
  reflection design rather than an automated TestKit leg.
- Binary-compatibility validation now gates every published `sdk/` module (previously only the
  Kotlin/JVM modules); each module's ABI baseline is committed under `sdk/<module>/api/`.

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
