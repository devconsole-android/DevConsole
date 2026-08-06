# Crash and ANR capture

DevConsole captures uncaught exceptions and main-thread stalls as timeline events, gives them their
own first-class surface on both the web dashboard and the Android in-app inspector, and auto-flags
every one of them into the evidence tray. This page covers exactly what gets captured, the format of
the all-thread dump (including its literal truncation markers), breadcrumbs, `CrashPolicy`, and the
previous-run-crashed banner.

## What is captured

Two kinds of event, both recorded through `CrashCapture` (`sdk:full`) onto the same durable timeline
everything else uses, with `pluginId = "crash"`:

- **`UNCAUGHT`** — an uncaught exception. `CrashCapture.install()` chains itself onto
  `Thread.setDefaultUncaughtExceptionHandler`, records the crash, marks the current run's stored
  status `CRASHED`, and only then delegates to whatever handler was already installed. If a host's own
  crash reporter (Crashlytics, Sentry, a custom handler) was installed first, it still runs — this SDK
  never swallows it, and if `crashCaptureEnabled = false` the host's handler is left **completely
  untouched**, not chained around at all.
- **`ANR`** — a main-thread stall, detected by `AnrWatchdog` (see below).

Each event's payload carries a redacted stack trace (or, for an ANR, the all-thread dump — see next
section) and a bounded list of breadcrumbs. The summary text is capped at 200 characters
(`CrashKind.UNCAUGHT` → `"<ExceptionClass>: <message>"`; `ANR` → `"Main thread unresponsive"`), and the
stack trace/dump is redacted and truncated to `CrashPolicy.maxStackChars` before it is ever persisted.

## The ANR watchdog is a heartbeat, not the platform's ANR signal

**Be precise about what `AnrWatchdog` actually detects.** It is not Android's own ANR mechanism (which
is driven by the input dispatcher and broadcast/service timeouts and surfaces through
`ApplicationExitInfo` on API 30+). It is this SDK's own main-looper heartbeat: every
`pollIntervalMs` (500ms), the watchdog posts a token to `Looper.getMainLooper()` and checks whether it
came back within `anrThresholdMs` (**default 5,000ms**, i.e. 5 seconds). If the token hasn't returned
by the deadline, the main thread is considered stalled and an ANR is reported — once per stall, not on
every poll, so a long block doesn't flood the timeline with duplicate reports.

That means DevConsole's ANR threshold and the platform's own (which is not a single fixed number and
varies by what's blocking — input dispatch, a broadcast receiver, a foreground service) are
independent and can disagree: this watchdog can report a stall the platform never surfaces as an ANR
dialog (a 5-second block with no pending input event), and in principle the platform could show its own
ANR dialog for something shorter than this watchdog's threshold. Set `anrThresholdMs` with that in mind
rather than assuming it lines up with what users would actually see.

The watchdog runs on a daemon thread, so it can never keep the process alive on its own.

## The all-thread dump

The original implementation reported only `Looper.getMainLooper().thread.stackTrace` on an ANR — the
main thread's own stack, which tells you *where* the block surfaced but not *what caused it*, since the
thread actually holding a contended lock is usually a different one. `AnrWatchdog` now captures
`Thread.getAllStackTraces()` in full and renders a bounded, deterministically-ordered text dump: the
stalled main thread first, then every other thread sorted by name (so the same stall produces the same
dump layout across runs, which is what lets a test assert on it).

Three independent caps bound the dump, each with its own **literal, verbatim** truncation marker
rendered into the text — nothing is ever dropped silently:

| Cap | Default | What's capped | Marker text when exceeded |
|---|---|---|---|
| `maxThreadsInDump` | 64 | Number of threads included | `... N more threads (truncated)` |
| `maxFramesPerThread` | 64 | Stack frames per thread | `\t... N more frames (truncated)` (appended under that thread's own frames) |
| `maxStackChars` | 32 KiB (32 × 1024) | Total characters across the whole rendered dump, applied last | `\n... (truncated, dump exceeded <maxStackChars> chars)` (appended at the very end, replacing whatever text would have overflowed the cap) |

The dashboard's Crashes detail view renders this text verbatim inside the existing full-screen code
overlay (the same one JSON payloads use) — split into lines, but never re-parsed or specially
formatted, so the truncation markers show up exactly as written above rather than being stripped or
summarized into something friendlier-looking. A dump that silently looked complete would be worse than
one that admits it was cut.

## Breadcrumbs

Every crash and ANR payload also carries a `"breadcrumbs"` array: the most recent entries from a
bounded ring buffer (`CrashPolicy.breadcrumbDepth`, default 50) fed continuously from the capture
timeline as events are appended, read synchronously at crash time and serialized oldest-first.

**Breadcrumbs carry summaries only — deliberately no payload bodies.** Each entry is
`{ts, plugin, type, severity, summary}`: a timestamp, the originating plugin id, the event type, its
severity, and its already-redacted summary text. This is a deliberate scope limit, not an oversight,
for two reasons:

1. **No new redaction surface.** A summary has already been through redaction as part of the normal
   event-append path; carrying it into a breadcrumb needs no second pass. A payload body would need
   its own redaction pass on the crash-capture path specifically, which is exactly the kind of new
   surface this design avoids.
2. **The synchronous crash-path budget.** `CrashCapture`'s persistence is deliberately synchronous —
   every other capture path hands work to a background queue, but a process that has just thrown an
   uncaught exception is about to die, and an asynchronous flush would routinely lose the one event
   that matters. The crash record's own insert and the evidence auto-flag (breadcrumbs included) now
   share **one** `PERSIST_TIMEOUT_MS` window (2 seconds) rather than each getting its own — auto-flag
   used to open a second, independent timeout of its own, which meant a slow evidence store could cost
   an entire extra `PERSIST_TIMEOUT_MS` before the previously-installed handler ran. `markCrashed()`
   still runs afterward in its own, separate `PERSIST_TIMEOUT_MS` window, so normal-case latency before
   the previously-installed handler (and, downstream of it, the host's own crash reporter) gets to run
   is **roughly 4 seconds** — two round trips, not three — not the 2-second figure this page previously
   stated. A bounded, summary-only breadcrumb list serializes fast enough to stay well inside its shared
   window; a set of full payload bodies would not be a safe thing to add to a synchronous crash-path
   write. See the KDoc on `CrashCapture` for the exact (and narrower-than-it-looks) guarantee this
   figure describes — it bounds *waiting for the write lock*, not a transaction already in flight.

`breadcrumbDepth = 0` disables the buffer entirely (`record` no-ops, `snapshot` always returns empty) —
that's how "no breadcrumbs" is expressed, not a separate config flag.

## `CrashPolicy`

```kotlin
data class CrashPolicy(
    val crashCaptureEnabled: Boolean = true,
    val anrWatchdogEnabled: Boolean = true,
    val anrThresholdMs: Long = 5_000L,
    val breadcrumbDepth: Int = 50,
    val maxStackChars: Int = 32 * 1024,
    val maxThreadsInDump: Int = 64,
    val maxFramesPerThread: Int = 64,
)
```

| Field | Default | Valid range | Outside the range |
|---|---|---|---|
| `anrThresholdMs` | 5,000 | 1,000 – 60,000 | `INVALID_ANR_THRESHOLD` |
| `breadcrumbDepth` | 50 | 0 – 500 | `INVALID_BREADCRUMB_DEPTH` |
| `maxStackChars` | 32,768 (32 KiB) | 1,024 – 1,048,576 (1 MiB) | `INVALID_MAX_STACK_CHARS` |
| `maxThreadsInDump` | 64 | 1 – 512 | `INVALID_MAX_THREADS_IN_DUMP` |
| `maxFramesPerThread` | 64 | 1 – 512 | `INVALID_MAX_FRAMES_PER_THREAD` |

`crashCaptureEnabled` and `anrWatchdogEnabled` have no numeric range — they're plain booleans, each
gating whether its respective mechanism ever installs at all. `crashCaptureEnabled = false` means
`CrashCapture.install()` is never called, so the host's `Thread.defaultUncaughtExceptionHandler` is
left completely untouched. `anrWatchdogEnabled = false` means the watchdog thread never starts.

`CrashPolicy` follows the same additive-`var`-with-`withX(...)` pattern as every other post-1.x policy
on `DevConsoleConfig`, so adding it did not touch the 1.x JVM ABI:

```kotlin
DevConsole.initialize(
    application,
    DevConsoleConfig.default().withCrashPolicy(
        CrashPolicy(anrThresholdMs = 3_000L, breadcrumbDepth = 100),
    ),
)
```

Every field is validated in `DevConsoleConfig.validationErrors()`; an out-of-range value surfaces as
one of the `ConfigValidationCode` entries in the table above rather than silently clamping.

## The Crashes surface

### Web dashboard

**Crashes** is a rail view in the **Signals** group, alongside Timeline, Push, and SDK Health — and,
unlike most of the Advanced-only surfaces, it's visible in **Simple** mode too, because it's a
QA-facing surface, not an implementation-detail one. It's a list + detail view like Network and
WebSockets: the list shows a `CRASH`/`ANR` badge, the summary, the originating thread, and the
timestamp; metrics above the list break down total/crashes/ANRs/flagged counts. The detail pane shows
the summary, kind, thread, a breadcrumb strip, and the all-thread dump in a collapsed code block (see
[above](#the-all-thread-dump) for exactly how that dump renders). Every crash and ANR arrives
auto-flagged into the evidence tray (see
[EVIDENCE_AND_BUG_REPORTS.md](EVIDENCE_AND_BUG_REPORTS.md#auto-flagging-crashes-and-anrs)), and the
list's footer says so plainly ("auto-flagged as evidence") rather than leaving a reader to notice the
flag icon on their own.

### Android in-app inspector

`ObserveTab.CRASHES` is the fifth tab on the Observe screen (`Traffic · Sockets · Push · Logs ·
Crashes`) — five tabs, at the documented maximum for that surface (see
[DESIGN_SPEC.md](DESIGN_SPEC.md) §4b's "max-5 rule"). Selecting a crash opens its detail as its own
screen; the all-thread dump renders inside the inspector's existing full-screen "Code" overlay so a
long, multi-thread ANR dump gets the same scroll/copy treatment a JSON payload does rather than being
squeezed into a cramped inline block. The detail screen's flag action writes to the same durable
`EvidenceStore` the dashboard reads, using the same snapshot shape the server produces, so a crash
flagged on the device shows up in the browser's tray and the reverse — see
[EVIDENCE_AND_BUG_REPORTS.md](EVIDENCE_AND_BUG_REPORTS.md).

## The previous-run-crashed banner

On connect, if the browser's most recent **non-active** retained run has
`StoredSessionStatus.CRASHED`, the Overview shows a banner: *"The previous run crashed"*, naming that
run's app version and device model where known, with a **VIEW** action.

This is backed by `GET /api/v1/runs`, which exposes every retained run's status, sorted newest-first
server-side (the dashboard does not trust whatever order the underlying store happens to return). The
`CRASHED` marker itself was already being written by `CrashCapture.markCrashed()` well before this
route existed — the gap this closes is that nothing previously *surfaced* it to a browser, which can't
reach in-process session state the way the on-device inspector can.

**Known limitation, by design, not yet closed:** the banner's **VIEW** action opens the general
Crashes view (`show('crashes')`), not a view filtered down to that specific past run. Nothing in this
SDK yet supports filtering the Crashes list (or the Timeline, or Network) to a session other than the
active one — the retained-runs table on Session & Security lets you see *that* a past run crashed, and
the general Crashes view lets you see *all* recorded crashes across retained history, but connecting
those two — "show me only this run's crashes" — is unbuilt. Session & Security also gained a full
retained-runs table (id, status, start/end time, app version, device) as part of this work, answering
the question that view's own heading had always promised without previously delivering.

## Known limitations

- **`ScreenshotCaptureInstrumentedTest`'s `PixelCopy`/`FLAG_SECURE` coverage and the crash/ANR/
  screenshot trigger paths added to the three sample apps have never been run on a device or
  emulator** — none was available while this was built. They compile, and the `androidTest` APK
  assembles, but treat the on-device behavior as reviewed-but-unverified until someone runs them.
- The all-thread dump's caps are enforced by `CrashPolicy`, but a genuinely wedged Room transaction
  during crash persistence is not bounded by `PERSIST_TIMEOUT_MS` the way *waiting* for the write lock
  is — see the KDoc on `CrashCapture` for the exact (and narrower than it first looks) guarantee.
