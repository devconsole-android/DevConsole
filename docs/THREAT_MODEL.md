# Threat model and safe operation

DevConsole exists to make a running app's internals visible. That is the feature, and it is also
the risk: everything the dashboard shows you, it shows to whoever else can reach it. This document
is the honest version of that trade-off. Read it before turning on LAN mode.

## The one-paragraph summary

The dashboard is served over **plaintext HTTP**. There is no TLS anywhere in this SDK. Whenever the
server is bound to a network address, the bearer token, every captured request and response header,
every WebSocket frame, every push payload, and every HAR export cross the network unencrypted.
Anyone in a position to observe that traffic — another device on the same Wi-Fi, a compromised
router, a guest network operator, someone running a packet capture in a café — reads all of it.

**The default binding reaches the network.** `BindingMode.AUTO` — what a bare
`StartRequest()` and an unconfigured `BrowserConfig` both mean — binds a real interface whenever the
device has one, and only falls back to `127.0.0.1` when it does not. Reachability is the default
because a debug-only dashboard that needs an `adb forward` incantation before it shows anything is a
dashboard most people never see. **The exposure is real regardless of the reasoning: on any network
you do not administer, pass `BindingMode.LOOPBACK` (and set
`BrowserConfig(binding = BrowserBinding.LOOPBACK)` for the on-device Start button).** Loopback plus
`adb forward` still avoids this entire class of attack; it is now something you ask for rather than
something you get.

## Screenshots are unredactable by construction

**If you turn on screenshot capture, unredacted screen content crosses your network in plaintext.**
This is the single most important thing to understand before setting `ScreenshotPolicy.enabled = true`,
so it is stated here, not buried in a feature doc.

A screenshot is pixels. Redaction works by recognizing field *names* — a header called
`Authorization`, a JSON key called `password` — and replacing the *value* behind that name. There is
no equivalent operation for a bitmap: nothing in this SDK inspects a captured frame for a session
token rendered in a debug overlay, a password visible in an unmasked text field, another user's PII on
screen, or anything else a screenshot happens to contain. **Whatever was on screen when the capture
fired is exactly what leaves the device**, byte for byte, the same way it would if you took the
screenshot yourself and copied the file. Nothing about this feature reduces that to a "usually safe"
proposition — it is categorically true of every capture, every time.

And it doesn't stay on the device. The dashboard that displays it, and the export bundle that can
contain it, both travel exactly as described in [the one-paragraph summary](#the-one-paragraph-summary)
above: **plaintext HTTP**, with no TLS anywhere in this SDK. In LAN mode, a captured screenshot crosses
the network the same way a captured header does — visible to anyone in a position to observe that
traffic. Turning screenshot capture on and running in LAN mode together means an unredacted picture of
whatever is on someone's screen is one HTTP response away from anyone else on that network.

Because of that, several design decisions in this feature are deliberately unforgiving:

- **Capture defaults to off** (`ScreenshotPolicy.enabled = false`). This is the single most sensitive
  artifact this SDK can produce, so it does not ship enabled the way network capture does — a host
  must opt in explicitly, the same posture `EditingCapabilities.readOnly()` takes for every other
  high-consequence feature.
- **A `FLAG_SECURE` window is refused, never captured as a black rectangle — and the check is race-free
  by construction, not just by a single read.** `ScreenshotCapture` reads `Window.attributes.flags` for
  `FLAG_SECURE` before any capture is attempted, deterministically, rather than trying to infer
  "secure" from a possibly-blank result afterward. A single check taken before dispatching to the main
  thread would be TOCTOU — a host that sets `FLAG_SECURE` in `onResume()`, a common pattern, could flip
  the flag on a background coroutine's watch after the check passed and before the draw ran — so the
  flag is **re-checked a second time on the main thread, immediately before the draw/copy is issued,
  inside the same posted block**, closing that window. On API 26+, `PixelCopy.ERROR_SOURCE_NO_DATA`
  maps to `ScreenshotResult.SecureWindow`; any other `PixelCopy` error maps to a generic `Failed`, so a
  secure-window rejection is never confused with an ordinary capture failure. On `minSdk`'s API 23–25
  fallback — a hand-drawn `Canvas` against the decor view, since there is no `PixelCopy` overload for a
  `Window` that old — `FLAG_SECURE` is not enforced by the platform at all; this class's own re-check is
  the *only* protection on that path, and if the decor view's attachment state is ambiguous at draw time
  (activity mid-teardown, window replaced), that ambiguity is treated as secure rather than captured, so
  the failure mode is a refused screenshot, never a leaked one. The alternative — silently persisting a
  black bitmap and presenting it as "a screenshot" — would be actively misleading: it looks like a
  redacted or empty capture succeeded when in fact nothing was captured at all. Capture is also bounded
  by a timeout, with the bitmap recycled on every exit path (success, failure, timeout, cancellation),
  so a capture can never keep an `Activity` and a multi-megabyte bitmap alive past that bound.

  **Two gaps remain, stated plainly rather than softened:**
  - **Child windows are invisible to this check.** `FLAG_SECURE` set on a `Dialog`/`DialogFragment`'s
    own `Window`, or via `SurfaceView.setSecure(true)`, never appears in the foreground Activity's own
    `window.attributes.flags`, and this class has no reliable way to enumerate an Activity's child
    windows. A secure dialog shown over a non-secure Activity is **not detected** — the capture proceeds
    and can include content the app deliberately marked secure.
  - **A timed-out `PixelCopy` request cannot be cancelled.** `PixelCopy.request` has no cancel API, so
    if the system-side copy is still in flight when the capture timeout fires, it is racing a bitmap
    this class has already recycled. This is believed low-risk (`PixelCopy` is a single-frame operation,
    not the kind of thing a stalled main thread should be able to delay), but it has never been exercised
    on real hardware — the `ERROR_SOURCE_NO_DATA` mapping and the re-check-before-copy ordering are
    covered by Robolectric unit tests only, because no device or emulator was available while this was
    hardened.
- **`RedactionApplicability.NOT_APPLICABLE` marks the honesty of this gap at the storage boundary, and
  the store enforces that claim rather than trusting it.** `AttachmentStore` normally refuses to persist
  unredacted content — writing with `isRedacted = false` under `redactionApplicability = APPLIED` is
  rejected outright. A screenshot cannot honestly claim `isRedacted = true`, because no redaction was
  possible in the first place, so it is written with `redactionApplicability = NOT_APPLICABLE` instead:
  an explicit, structural admission that this content never went through — and could never go through —
  the redaction engine. That claim is not merely caller-promised: the store additionally rejects a
  `NOT_APPLICABLE` write outright when the attachment's own MIME type is one redaction *can* apply to,
  so a caller cannot launder redactable text content past the invariant by mislabeling it
  `NOT_APPLICABLE` — only content types redaction genuinely cannot reach (like a screenshot's PNG bytes)
  may claim it. The field travels with the attachment everywhere it's read back
  (`GET /api/v1/attachments/{id}`'s response header, the evidence tray's item JSON, the export
  manifest), so a screenshot is labelled **UNREDACTED** wherever it surfaces rather than looking
  identical to a redacted text body next to it. See
  [SECURITY_AND_REDACTION.md](SECURITY_AND_REDACTION.md#redactionapplicability-and-the-storage-boundary)
  for the exact mechanics.
- **The evidence bundle contains screenshots as-is.** A flagged `SCREENSHOT` evidence item's attachment
  ships inside the export ZIP's `attachments/screenshots/` directory, and `manifest.json` records its
  `redactionApplicability` as `NOT_APPLICABLE` — the same unredacted image, now packaged for handoff to
  a tracker or a teammate. Attaching an evidence bundle with a screenshot in it to a public issue
  tracker republishes exactly what was on screen at capture time. See
  [EVIDENCE_AND_BUG_REPORTS.md](EVIDENCE_AND_BUG_REPORTS.md#the-evidence-bundle).

None of this is a defect to be fixed later — a screenshot that could be selectively redacted wouldn't
be a screenshot. If your app ever displays session tokens, unmasked PII, payment details, or anything
else you would not want to leave the device unencrypted, either leave screenshot capture off, or only
enable it on `BindingMode.LOOPBACK` + `adb forward` where the capture never crosses a network at all —
the same recommendation this document makes for every other sensitive artifact this SDK can produce.

## What is protected, and against what

| Control | Stops | Does not stop |
|---|---|---|
| Bearer token + CSRF token | Cross-site requests from another origin | Reading the tokens off the wire |
| Origin allowlist | Requests claiming a foreign `Host` | An attacker on the allowed network |
| Redaction | Known-sensitive field *names* | Every field name not on the list |
| 5-minute session-code TTL, 30-minute session TTL | Indefinite reuse of a stale credential | Use inside the window |

**There is no on-device approval gate.** SESSION_CODE (see
[PROTOCOL_REFERENCE.md](PROTOCOL_REFERENCE.md#2-auth-handshake-session_code)) is the only
browser-access flow: whoever presents the current, unexpired, not-yet-used 8-character code to
`POST /api/v1/auth/session-code/exchange` gets a session immediately -- no human decision in the
loop, no notification to tap. The trust model collapses to a single fact -- **possession of the code
within its 5-minute TTL is the entire authorization decision** -- which makes the fragment-URL
delivery of that code (and everywhere it might land: a screen share, a shoulder-surfed device,
browser history, a saved bookmark) the whole perimeter. The code is single-use and there is no
automatic regeneration or fallback on expiry. Be precise about what that bounds: the code's TTL
limits when a session can be **acquired**, not how long one lives. A session exchanged in the final
second of the TTL is a normal 30-minute, refresh-extendable session that outlives the code
indefinitely — watching the code expire on the device tells you nothing about whether someone
already traded it in. Audit and revoke live sessions from the More screen or
`DELETE /api/v1/auth/principals/{id}`. Because there is no approval step, treat the code's delivery
channel as the entire security boundary -- SESSION_CODE only belongs where that channel is itself
trusted (typically loopback + `adb forward`, where the code never crosses a network at all). Also
count the clipboard among the places the code lands: copying the connect URL to paste it somewhere
hands a complete credential to every app with clipboard access.

**There is no read-only tier.** Every authenticated session is equivalent and has full control of
everything the dashboard exposes; the only remaining gates are the host's per-feature
`EditingCapabilities` flags (mocks, capture rules, preferences, database, files), which bound *what
the console can do*, not *who* can do it. If you previously handed out READ_ONLY browser sessions,
that concept no longer exists -- a session is a session.

**Revocation is symmetric.** Any session can list every connected browser (label and source IP) and
revoke any other session, including yours. An attacker who wins the code race doesn't just get in --
they can log the operator out and, since the code was single-use and nothing regenerates it, keep
the device to themselves until the host issues a new code or stops the server. Session revocation is
a remedy, not a proof of control.

## Redaction is an allowlist, and allowlists miss things

`RedactionPolicy.default()` matches a fixed set of ~25 field names (case-insensitively) against
header names, JSON keys, and form/query field names, plus one regex for `Bearer <token>` text. See
[SECURITY_AND_REDACTION.md](SECURITY_AND_REDACTION.md) for the exact list.

Everything not on that list is transmitted and stored **verbatim**. In practice that means:

- **Custom auth headers.** `X-Company-Session`, `X-Tenant-Key`, `X-Signature` — not on the list, not
  redacted. Extend the policy with your own names.
- **Signed URLs.** A pre-signed S3 or CDN URL carries its credential in query parameters whose names
  (`X-Amz-Signature`, `sig`, `token` variants) mostly are not matched. The URL is captured in full.
- **PII in bodies.** Email addresses, phone numbers, addresses, government IDs, free-text fields —
  redaction is keyed on field *names* it recognises, so an unrecognised key holding a national ID
  number is broadcast as-is.
- **Response bodies generally.** Your API's response shape is your API's response shape; nothing
  infers sensitivity from content.
- **The SQL console.** Database rows are redacted by *result-set column name*, so a caller who
  writes their own SQL can alias any column past it (`SELECT password AS p ...`). This is why
  `POST /api/v1/database/{name}/sql` requires the explicit `database` capability even for SELECT:
  enabling that capability is enabling raw, unredacted database access over the session.

Redaction is a guard against the *predictable* leak — the `Authorization` header you would otherwise
paste into a bug report. It is not a data-loss-prevention system, and it should not be the reason
you feel comfortable putting a device on an untrusted network.

Bodies are also truncated to 64 KiB before storage. That is a resource bound, not a privacy control.

## Safe defaults

**Untrusted or shared network — use loopback.** This is the recommended posture, and it is no
longer the SDK's default: you have to ask for it.

```kotlin
DevConsole.startBrowser(StartRequest(bindingMode = BindingMode.LOOPBACK))
```

```bash
adb forward tcp:8080 tcp:8080   # use the port from the logged URL, not always 8080
```

The server binds `127.0.0.1`, so nothing is reachable over the network at all. `adb forward` tunnels
the port over the USB/ADB connection. You lose cross-device connection; you gain the entire class of
network attacks not applying.

**Trusted network — LAN mode, with eyes open.** A private office or home network with no guests is a
reasonable place for LAN mode. A conference Wi-Fi, a hotel, a café, or any network you do not
administer is not. Note that LAN mode binds one specific interface address, never `0.0.0.0`, which
limits reach but does not make the traffic private.

**Staying off the network is the explicit choice.** This used to be the other way round.
`startBrowser()`
picks `BindingMode.AUTO` when you pass no mode, and AUTO reaches the network whenever it can. On a
network you do not trust, say so:
`DevConsole.startBrowser(StartRequest(bindingMode = BindingMode.LOOPBACK))`.

The three modes differ only in what they do when LAN is unavailable:

| Mode | Network available | No eligible interface | `ACCESS_LOCAL_NETWORK` ungranted (API 37+) |
|---|---|---|---|
| `AUTO` (default) | binds LAN | binds loopback | binds loopback |
| `LAN` | binds LAN | `NoEligibleNetwork` | `PermissionRequired` |
| `LOOPBACK` | binds loopback | binds loopback | binds loopback |

Two consequences worth internalising. First, **AUTO never prompts for the local-network
permission** — it treats a missing grant as a reason to settle rather than a reason to ask — so on an
API 37+ device an AUTO start quietly serves `127.0.0.1` until something requests the grant. Ask for
`LAN` by name if you want the `PermissionRequired` result to drive a prompt. Second,
`StartResult.Started.endpoint.bindingMode` is the only honest answer to "did this reach the
network?"; it reports the socket, never the request, and is never `AUTO`.

There is a second way to start the server: the **Start button on the in-app inspector's More
screen**, which issues no `StartRequest` and instead binds whatever
`DevConsoleConfig.browserConfig.binding` declares. That field defaults to `BrowserBinding.AUTO` too.
The two settings are independent and neither overrides the other -- a host that wants to pin one
surface to loopback has to say so on that surface.

**Stop the server when you are done.** `DevConsole.stop(...)` revokes browser sessions immediately
and unbinds the port. A dashboard left running on a desk overnight is an open dashboard.

## Things teams get surprised by

**Debug builds silently gain a permission.** `sdk:full`'s manifest declares
`android.permission.ACCESS_LOCAL_NETWORK`, and manifest merge folds it into any variant that depends
on it. It is never *requested* by the SDK, but it appears in the merged manifest of your debug APK.
If you ship debug-signed builds to QA, to a customer, or to an internal distribution channel, those
builds carry the permission and the full runtime. That is a shipping decision worth making
deliberately rather than discovering in a review.

**The bug report bundle, HAR export, Postman export, evidence bundle, and Android session ZIP are
full-fidelity dumps.** `GET /api/v1/report`, `GET /api/v1/network/har`, and
`GET /api/v1/network/postman` return the captured timeline and network trail as downloadable files;
the in-app "Export session ZIP" action (MORE destination) bundles the same network trail alongside
the timeline and non-sensitive app metadata; the evidence bundle
(`POST /api/v1/exports` with `scope=EVIDENCE`, see
[EVIDENCE_AND_BUG_REPORTS.md](EVIDENCE_AND_BUG_REPORTS.md#the-evidence-bundle)) additionally embeds
any flagged screenshots **unredacted**, clearly marked as such in its manifest — see
[above](#screenshots-are-unredactable-by-construction). All of them are redacted with the same
policy as live capture (screenshots excepted, by construction) — and inherit exactly the same blind
spots. Treat any of these exports as the sensitive artifact it is: each is a
transcript of your app's traffic (Postman collections additionally embed full request/response
bodies, not just headers), and attaching one to a public issue tracker republishes everything
redaction missed.

**Both the export directory and the capture database persist under `filesDir`/`databases` and
inherit your app's backup settings.** Two paths are relevant, and the database is by far the larger
exposure:

- `AndroidInspectorExporter` writes HAR, Postman, and session ZIP artifacts to
  `<app>/files/devconsole-exports/`, pruning down to the five most recent artifacts before each new
  export so the directory doesn't grow forever.
- `PlatformFacadeProvider` opens the Room capture database at `<app>/databases/devconsole-events.db`
  (plus its `-wal`/`-shm` sidecar files while open). This is the live network/socket/push/timeline
  store, bounded by [`RetentionPolicy`](STORAGE.md) to 7 days / 100 MB by default -- meaningfully
  larger than the export directory, and it exists on every debug install that has ever run the SDK,
  not just ones where a host tapped "export."

Both are regular app-private storage, not a cache -- they're included in Android's key/value or
full-data auto-backup (`android:allowBackup`, default `true`) exactly like the rest of `filesDir`
unless your app opts out. A device restore can therefore resurrect a previously-captured,
redacted-but-still-sensitive traffic transcript (exports) or the full capture database itself onto a
different device. The SDK does not add its own backup-exclusion rule, to avoid a manifest-merge
conflict with a host app's own backup configuration -- if your app allows backup, add both paths
yourself.

For [Auto Backup](https://developer.android.com/guide/topics/data/autobackup) (API 31+,
`android:fullBackupContent`) and [Key/Value Backup](https://developer.android.com/identity/data/keyvaluebackup)
together, `data_extraction_rules.xml` (referenced via `android:dataExtractionRules`) covers both
device-to-device transfer and cloud backup in one file:

```xml
<!-- res/xml/data_extraction_rules.xml -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="file" path="devconsole-exports/" />
        <exclude domain="database" path="devconsole-events.db" />
        <exclude domain="database" path="devconsole-events.db-wal" />
        <exclude domain="database" path="devconsole-events.db-shm" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="file" path="devconsole-exports/" />
        <exclude domain="database" path="devconsole-events.db" />
        <exclude domain="database" path="devconsole-events.db-wal" />
        <exclude domain="database" path="devconsole-events.db-shm" />
    </device-transfer>
</data-extraction-rules>
```

If you still support the older `android:fullBackupContent` mechanism (pre-API 31, or you haven't
migrated), the equivalent is:

```xml
<!-- res/xml/full_backup_content.xml -->
<full-backup-content>
    <exclude domain="file" path="devconsole-exports/" />
    <exclude domain="database" path="devconsole-events.db" />
    <exclude domain="database" path="devconsole-events.db-wal" />
    <exclude domain="database" path="devconsole-events.db-shm" />
</full-backup-content>
```

Wire whichever file(s) apply into your `<application>` tag:

```xml
<application
    android:allowBackup="true"
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="@xml/full_backup_content">
```

Both snippets are additive to whatever exclusion rules your app already ships for its own data --
merge the `<exclude>` entries into your existing files rather than replacing them wholesale.

**The Composer turns the device into a request proxy.** When enabled, an authenticated session can make the
device issue arbitrary outbound HTTP requests from wherever it sits — including inside a corporate
network. It is off by default for this reason, and can be confined to an allowlist of hosts. See
[COMPOSER_AND_MOCKS.md](COMPOSER_AND_MOCKS.md).

**The `files` capability is write access to the app sandbox.** With it enabled, an authenticated session
can create, replace, rename, and delete regular files anywhere under the four sandbox roots, and
download raw, unredacted bytes. If your app loads anything executable or executable-adjacent out of
those directories — a JS or Dex bundle, a dynamic config, a plugin — granting `files` to a browser
session is effectively granting code execution in your app. It is off by default; treat enabling it
with the same weight as the Composer and the SQL console. The Share action also registers a
non-exported `FileProvider` (`<applicationId>.devconsole.files`) via manifest merge in every build
that depends on `sdk:full`; grants are single-URI and temporary, but the provider's existence in
your merged manifest is worth knowing about.

## What production builds guarantee

A variant depending only on `devconsole-noop` never links the server, the dashboard assets, the
storage layer, or any capture code. The recorders are constructed with `enabled = false`, so the
redaction engine is not "invoked and returns nothing" — it is never called, because there is nothing
to call it. See [BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md](BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md).

## Reporting a problem

Vulnerability reporting policy: [SECURITY.md](../SECURITY.md). Do not open a public issue for
credential exposure, production inclusion, or authentication bypass.
