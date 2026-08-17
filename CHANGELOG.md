# Changelog

Notable changes to the DevConsole SDK. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[semantic versioning](https://semver.org/).

## Versioning and stability policy

**1.0.0 shipped on 2026-08-08, and the public API is now stable.** Breaking changes to `sdk:api`
require a major version, new API requires a minor, and everything else is a patch. The pre-1.0
window — where any release could change the API — is closed.

Since 0.3.0, every published `sdk/` module carries a committed ABI baseline
(`sdk/<module>/api/<module>.api`) checked on every build. Any change to those surfaces shows up as
a failing `apiCheck` and must be accepted deliberately with `./gradlew apiDump`. That baseline is
what makes the promise above enforceable rather than aspirational: an accidental break fails CI
before it can reach a release. (There was briefly a separate `sdk:plugin-api` module for a generic
third-party plugin framework; it was removed before ever shipping — see Removed, below — so it never
joined this list.)

## Unreleased

Three default changes, aimed at the same complaint: a fresh integration showed a `127.0.0.1` URL
that needed `adb forward` before it displayed anything, and a Mocks screen that could not add a
mock. See [docs/MIGRATION.md](docs/MIGRATION.md#upgrading-from-120) for the upgrade path.

**Version note, deliberately unresolved here:** under this file's own
[policy](#versioning-and-stability-policy), adding a constant to a public enum is source-breaking
for any host that `when`s exhaustively over `BindingMode`/`BrowserBinding`, and flipping a security
default is a behavioural break. That reads as a major, not a minor. The number is left for the
release decision rather than assumed.

### Added

- **`BindingMode.AUTO` and `BrowserBinding.AUTO`**, now the default for `StartRequest.bindingMode`
  and `BrowserConfig.binding`. AUTO binds a real network interface when the device has one and
  quietly binds loopback when it does not, so a start always yields a working server and the
  connect URL is scannable from another machine without configuration.

  Introduced as a third mode rather than by re-pointing the default at `LAN`, because `LAN` earns
  its keep by *not* degrading: a host sharing a connect URL with another device needs
  `NoEligibleNetwork`/`PermissionRequired` back, not a `127.0.0.1` link that works for nobody but
  itself. Explicit `LOOPBACK` and `LAN` behave exactly as before.

  `BrowserEndpoint.bindingMode` never reports `AUTO` — it reports the socket that actually bound, so
  the QR code, the connect URL, and the "LAN MODE — UNENCRYPTED" banner stay truthful. AUTO
  fallbacks are logged with their reason.

  The name is reused, not restored: the pre-1.0 `BindingMode.AUTO` came with zero-config auto-start
  and NSD service discovery, both still removed. This one only picks a binding.

- **`MockEngineRegistry`** (`sdk:mocks`), a process-wide handle the enabled facade publishes its
  `MockEngine` to at `initialize`. It exists to close a module-layering gap — `sdk:network-okhttp`
  sits below the facade and cannot call `DevConsole.mockEngine()` — and is what lets
  `installDevConsole` wire mocking without the host naming an engine. The protected build publishes
  nothing, so a release APK reads `null` and wires no mock interceptor.

### Changed

- **The default binding now reaches the network.** This is a deliberate loosening of a security
  default: the dashboard speaks plaintext HTTP, so on a network you do not administer, headers,
  tokens, and bodies are readable by anyone who can see the traffic. Pass `BindingMode.LOOPBACK`
  (and `BrowserConfig(binding = BrowserBinding.LOOPBACK)` for the on-device Start button) to keep
  1.2.0 behaviour. [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) has been rewritten around the new
  default rather than patched.

  One consequence worth knowing: **AUTO never prompts for `ACCESS_LOCAL_NETWORK`.** It treats a
  missing grant as a reason to bind loopback, not a reason to fail, so on an API 37+ device it
  settles for `127.0.0.1` until something requests the grant. Hosts that want
  `StartResult.PermissionRequired` back to drive a prompt ask for `BindingMode.LAN` by name — which
  all three samples still do.

- **`installDevConsole` wires mock rules.** Both the Kotlin extension and the Java-friendly
  `DevConsoleOkHttp.install` gained a `mockEngine` parameter defaulting to the published engine, so
  the separate `.addInterceptor(DevConsoleMockInterceptor(DevConsole.mockEngine()))` line is no
  longer needed. The mock interceptor is added after the capture interceptor, so a served mock is
  still recorded and tagged `mocked`. Pass `mockEngine = null` to opt out.

  The 2- and 3-argument overloads are still emitted, so Java call sites and existing bytecode are
  unaffected; Kotlin callers relying on default arguments should recompile, since the synthetic
  `$default` signature changed.

- **`EditingCapabilities.mocks` defaults to `true`**, and `DevConsoleConfig` seeds
  `EditingCapabilities()` instead of `EditingCapabilities.readOnly()`. A mock rule writes nothing of
  the host's — it only short-circuits DevConsole's own interceptor — so the read-only posture that
  protects preferences, databases, and files had nothing to protect here. `readOnly()` is now
  spelled out field by field rather than delegating to the constructor, so it still grants nothing
  at all, mocks included.

### Fixed

- **A `Delay` mock rule is no longer applied twice** when a host keeps its own
  `DevConsoleMockInterceptor` alongside the one `installDevConsole` now adds. The second interceptor
  recognises the first one's request tag and stands down. Without this, upgrading would have
  silently doubled every simulated latency that proceeds up the chain.

## 1.2.0 — 2026-08-15

A minor under the [policy](#versioning-and-stability-policy): the Remote Config inspector is
entirely additive — three new modules, one new REST route, and new API on the config and runtime
facades — with no change to any existing surface. Two concurrency fixes to the state registry ride
along.

### Added

- **Remote Config inspector** ([#6](https://github.com/devconsole-android/DevConsole/issues/6)).
  Read-only view of the Remote Config values active on the device, with the attribution that makes
  them diagnosable: whether each value came from the server, an in-app default, the SDK's static
  fallback, or a local override — plus last fetch time, last fetch status, and minimum fetch
  interval. A key serving a default because the fetch was throttled is otherwise indistinguishable
  from one you actually published.

  Three new modules following the existing push triad: `devconsole-remote-config` (vendor-neutral
  model and registry), `devconsole-remote-config-firebase` (reads `FirebaseRemoteConfig` by
  reflection, so Firebase stays off the classpath of consumers that do not use it), and
  `devconsole-remote-config-firebase-noop` (the protected-build twin, which reports
  `disabled-build` rather than pretending config is simply empty). Neither adapter is re-exported by
  the `devconsole` umbrella.

  Surfaces on both the browser dashboard (a Remote Config page under Data) and the Compose
  inspector's Observe screen (a **Config** tab, shown only when a provider is registered, since the
  on-device tab row splits its width equally and should not spend a tab on an app with no Remote
  Config), and over `GET /api/v1/remote-config`. Gated by the existing
  `CaptureCategory.STATE`, alongside state providers and feature flags. Register a provider with
  `DevConsoleConfig.withRemoteConfigProviders(...)` or, for a lazily-built client,
  `DevConsole.registerRemoteConfigProvider(...)`. Read-only by design: DevConsole does not set
  overrides and never triggers `fetch()`/`activate()`. See
  [docs/REMOTE_CONFIG.md](docs/REMOTE_CONFIG.md).

  A config value is a string of any length, so the tables show a preview and open the full value on
  demand: click a key on the dashboard, tap a row on the Config tab. Both default to pretty-printed
  JSON and fall back to the raw string with a toggle between them — a value like `on` or `v2` is
  never re-quoted to make it parse, since the quotes would be the viewer's invention rather than
  something the server sent, and a truncated or redacted value says so rather than failing to parse
  for an unexplained reason.

  Values are redacted by key name at a single boundary shared by both surfaces — the on-device
  inspector reads in-process and never crosses the HTTP boundary, so redacting at the route alone
  would have left it exposed. Matching is separator-insensitive here, unlike the raw
  `RedactionPolicy`, whose defaults are an HTTP-header list compared by exact name: `api_key` and
  `apiKey` now match the policy's `api-key` instead of being displayed in full.

### Fixed

- **State providers no longer race their readers.** `StateRegistry` held a plain `LinkedHashMap`
  read without a lock, so a host registering a provider late — through
  `DevConsole.registerStateProvider`, which is a documented path — while the dashboard's server
  thread or the in-app inspector was iterating could throw `ConcurrentModificationException` into
  the app being debugged. Every access is guarded now, and the insertion order surfaces list
  providers in is preserved. `RemoteConfigRegistry` had the same shape and got the same fix before
  it ever shipped.

- **Re-initializing replaces registered providers instead of silently keeping the old ones.** Both
  registries reject a duplicate id, and neither was cleared between `initialize()` calls, so a
  `stop()` → `initialize(...)` carrying a replacement provider under the same id had its
  registration refused — leaving every surface reading through the torn-down original. Both are now
  cleared as part of the same per-init reset, and a duplicate id within one config is ignored rather
  than thrown out of `initialize()` (state providers previously threw; Remote Config already did not).

## 1.1.1 — 2026-08-12

A build-and-distribution patch: no SDK code changed, and the public API is byte-for-byte the
1.1.0 surface. It exists because 1.1.0 could not be built by anything that lacks the maintainer's
signing key — including JitPack, which this release adds as a channel.

### Added

- **JitPack as a secondary channel, for unreleased code.** Maven Central remains the supported
  one; JitPack covers trying a branch or an unmerged fix. Artifacts resolve under the group
  `com.github.devconsole-android.DevConsole` with the same artifact ids
  (`com.github.devconsole-android.DevConsole:devconsole:1.1.1`), and any branch resolves as
  `<branch>-SNAPSHOT` (a `/` in the branch name is written `~`). Two limits worth knowing: the
  Gradle plugin is **not** served there — it lives in an `includeBuild` and JitPack lists only the
  31 library modules, so JitPack consumers name coordinates directly and give up the plugin's
  variant-policy enforcement — and JitPack artifacts are unsigned.

### Fixed

- **`./gradlew publishToMavenLocal` failed for anyone without the maintainer's signing key.**
  `signAllPublications()` registers a signing task *and* the resulting `.asc` files as publication
  artifacts. With no key the task fails outright ("no configured signatory") rather than skipping,
  and even when the task is explicitly excluded the publication still demands `.asc` files nothing
  produced ("artifact file does not exist: …-release.aar.asc"). Both shapes broke the same three
  audiences: contributors running the exact command `CONTRIBUTING.md` asks for, CI building from a
  clean checkout, and JitPack. Publications are now signed only when a key is actually configured —
  the key itself, not the id/password a maintainer's machine keeps between releases. Release
  signing is unchanged and verified both ways: five signatures with the key present, none and a
  green build without. `docs/MAVEN_PUBLISHING.md` has claimed since 1.0.0 that signing "no-ops when
  no key is configured"; that is now true rather than aspirational.

- **`docs/COMPOSER_AND_MOCKS.md` described re-enabling mocks without saying how**, from before
  [1.1.0](#110--2026-08-12) added `POST /api/v1/mocks/enabled` — until then the browser genuinely
  could not. Now documents both directions and why turning mocking on is capability-gated while
  turning it off is not.

## 1.1.0 — 2026-08-12

A minor in the strict sense the [policy](#versioning-and-stability-policy) commits to: two additive
methods on `DevConsoleRuntime`, one new REST route, and no change to any existing surface. Most of
the release is the native-siblings UI pass across both surfaces, plus four defects a full
feature sweep of the shipped build turned up.

### Added

- **`POST /api/v1/mocks/enabled` — the way back from the mock kill switch.** The browser could
  disable all mocking (`POST /api/v1/mocks/disable-all`) but had no route to turn it back on, so
  the only way to resume mocking was to restart the host app — while the Android in-app inspector
  had a two-way toggle the whole time. The dashboard's "Disable all mocks" button is now a two-way
  switch that reads its label from the engine's live state. The two directions gate differently and
  deliberately: turning mocking *off* stays ungated (falling back to real traffic is always
  allowed), while turning it back *on* requires the host's `mocks` editing capability, since it
  changes how the app behaves.

- **`DevConsoleRuntime.recordPublishedEvent()` / `recordDroppedEvents(count)`**, the counters behind
  the fix below.

### Fixed

- **Every list row in the dashboard rendered without its path or summary.** `.row-main` is the
  flexible column between a row's fixed ones, and those fixed columns — checkbox 24px, method badge
  54px, duration 74px, status 62px, flag 24px, five 12px gaps, 20px padding — added up to 318px
  inside a list pane whose default width was 320px. The path text was laid out into the leftover
  2px and clipped to nothing, so Network, Timeline, WebSockets, Push, and Crashes all showed rows
  identifiable only by their method badge and duration; on the two-column rows a sliver of the
  leading `/` survived and read as a stray comma. The detail pane had the full URL all along, which
  is what kept this from being obvious. The row gap is now 8px and the list column defaults to
  440px with a 360px floor (it stays drag-resizable), leaving the main text 142px at the default.
  The `.col-*` header cells were re-aligned to match, including `.col-flag`, which had been 34px
  against the 24px flag button it labels.

- **The SDK-health card reported "0 published events" no matter how much the SDK captured.**
  `publishedEventCount` and `droppedEventCount` are surfaced on the dashboard's SDK Health screen
  and the Android More screen's health rows, and both were hardcoded 0 in every shipping build:
  the counters live on `EventPipeline`, which nothing in the runtime ever instantiates — real
  captures funnel through `CaptureTimelineBridge.emit()` instead, and `DevConsoleRuntime`'s own
  `SdkHealth` only ever tracked `initializationCount` and `state`. A dropped-event count that can
  never rise is the more consequential half: it is the SDK's only signal that capture lost data to
  buffer overflow. `emit()` now counts each published event and the persistence queue's existing
  `onDrop` hook counts each dropped one.

- **A malformed request answered `500 INTERNAL_ERROR`.** Routes that read a form body — `POST
  /api/v1/push/simulate`, the `POST` halves of the HAR and Postman exports, `POST /api/v1/exports`,
  and the rest — call `receiveParameters()`, which throws when handed JSON or no `Content-Type` at
  all. That landed on the module's catch-all boundary and came back as a server error, sending
  anyone writing a third-party client hunting a bug in the SDK for what was their own malformed
  request. Content-transformation failures now answer `400 VALIDATION_FAILED` and the typed
  media-type rejection answers `415 UNSUPPORTED_MEDIA_TYPE`, so an `INTERNAL_ERROR` from this
  server once again means only a genuine server-side fault. The dashboard was never affected — it
  sets the right content type on every one of these calls.

- **A pill button clipped its own label.** `InspectorPillButton` laid its label out with `maxLines =
  1` and the default clip overflow, so the More screen's "Code" action — squeezed by the "Copy URL"
  pill's 2f weight — rendered as "Cod". The label now ellipsizes rather than silently dropping a
  glyph, the pill's horizontal padding is 12dp, and the URL card's primary action takes 1.5f.

- **Starting the server on a port that was taken between the availability probe and the real bind
  could crash the host app.** `KtorLocalServerEngine.start` preflights each candidate port with
  `isPortAvailable`, then calls `embeddedServer(CIO, ...).start(wait = false)` — which returns
  *before* CIO's accept loop has actually bound. Losing that race throws `BindException` inside
  Ktor's own accept coroutine, long after the `runCatching` around `start()` has returned, and with
  nothing on the server's parent context it reached the thread's default uncaught handler: on
  Android, the host application's crash handler. A debug console taking down the app it exists to
  observe, for a condition it already handles — `isServerBound` sees the failure and the loop falls
  forward to the next port exactly as intended. The embedded server now runs in an engine-owned
  `SupervisorJob` scope carrying a `CoroutineExceptionHandler`, so a detected-and-recovered bind
  failure stays the SDK's business. Surfaced by a flaky test rather than a report: Gradle runs
  `testDebugUnitTest` and `testReleaseUnitTest` in parallel, both binding this module's fixed
  `8400..8419` range, and the loser's escaped exception landed on whichever unrelated test called
  `runTest` next as `UncaughtExceptionsBeforeTest` — roughly two failures in every five suite runs,
  on two tests that had nothing to do with it.

- **OkHttp responses without a `Content-Length` showed a blank response body unless the host read
  the whole thing.** Those responses — `Transfer-Encoding: chunked`, and every transparently
  gzipped response, which is most of them — are captured by the tee, which copies bytes *as the
  host reads them*. A call site that reads only the status code and closes (`execute().use {
  it.code }`, the shape all three samples had), or a parser that stops at the end of the value it
  wanted, therefore left nothing to capture: the transaction was recorded with an empty body and
  `bodyOmittedReason = "partial"`. The same response *with* a `Content-Length` was captured whole
  by the eager `peekBody`, and the Ktor plugin — which drains its own half of the split channel —
  was never affected, so identical app code showed the body on one client and a blank pane on the
  other. `TeeCapturingSource.close()` now drains whatever the host left behind before completing
  the close, bounded by the existing 512 KiB cap and a 300 ms deadline (the same
  bounded-drain-on-close OkHttp itself performs for connection reuse). A body that runs out of that
  budget is still recorded `"partial"`, and one no bytes were recovered from is now recorded with
  no body instead of an empty one — an empty preview beside a 200 reads as "the server sent
  nothing", which is a different claim. The compose sample's OkHttp card now reads its response
  body, like its Ktor card already did.

## 1.0.1 — 2026-08-08

A patch in the strict sense the [policy](#versioning-and-stability-policy) now commits to: one
browser-side bug fix, no API change of any kind.

### Fixed

- **Long response bodies were cut off in the dashboard's detail pane, with nothing to scroll.** The
  shared `detailHeadHtml` builder opened `<div class="detail-head">` and never closed it, so the
  browser nested the find bar and `.detail-body` *inside* the head instead of leaving all three as
  siblings of `.detail-pane-v2`'s flex column. `.detail-body`'s `flex: 1; min-height: 0;
  overflow: auto` — the only thing that scrolls a long body — therefore applied to nothing: the head
  grew past the pane and `.detail-pane-v2`'s `overflow: hidden` clipped whatever didn't fit, with no
  scroller anywhere. One `</div>` fixes it for every pane built on that helper (Network, WebSockets,
  Push, Crashes). Measured in a real browser before and after: the pane went from one child with a
  347px non-filling body to three siblings whose body fills the pane and scrolls its full overflow.

## 1.0.0 — 2026-08-08

The API is stable from here (see the policy above). No API was broken to get here: 1.0.0 is the
0.4.0 surface plus one additive `NetworkTransactionRecorder.record(...)` overload, and everything
else in this release is a fix to behaviour that looked right and wasn't — a LAN server that bound
and served nobody, a notification that was posted and never shown, a QR code that sat off-centre,
and a config field that had been documented as inert since it was added.

### Fixed

- **The keep-alive notification never appeared, and the prompt to fix that was on the wrong screen.**
  Two compounding causes. First, `POST_NOTIFICATIONS` is what makes the foreground service's
  notification visible, and the snackbar offering that grant lived only on the Control surface —
  while the server is started from More (or the host's own UI), so someone who started the server
  and went looking for a notification never saw the offer. It is now on both. Second, and worse,
  accepting it still showed nothing: Android suppresses a foreground service's notification when the
  service starts without the permission and does **not** post it retroactively on grant, so the
  service sat there `isForeground=true` with a notification attached and no posted record at all.
  Granting now re-issues the keep-alive start, which re-posts for real. Verified end to end on an
  Android 17 device — from denied and invisible, through the prompt, to the notification in the
  shade.
- **The More screen's QR code sat off-centre in its dialog.** `AlertDialog` aligns its `text` slot
  content to the start, and the QR is a fixed-size square narrower than the dialog, so it hugged the
  left edge with all the slack on the right; the empty `confirmButton` — whose button-row padding is
  laid out whether or not it has content — pushed it above centre as well. It is now centred on both
  axes.
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

- **The floating open-trigger button is translucent at rest.** It sits over the host app's own UI
  for the whole session, so it now rests at 65% opacity instead of fully opaque, going opaque again
  while touched — which doubles as the affordance for the drag it already supported.
- **Permissions are documented.** The README gained a Permissions table covering every permission
  DevConsole needs, who declares it, why, and the fact that none of them reach a release build
  (`devconsole-noop` declares nothing), with an `aapt2 dump permissions` command to verify it
  against a real artifact rather than take the docs' word for it.
  `docs/BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md` carries the same in detail.
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
