# Evidence tray and bug reports

The evidence tray is the QA loop's flagship surface: flag a network transaction, a socket frame, a
push event, a timeline entry, a screenshot, or a crash while you're looking at it, write down what's
wrong, and leave with a bundle a tracker can ingest. This page covers how flagging actually works,
what is captured and when, the caps that keep a tray usable, the bundle it produces, and the three
clipboard formats. All of it sits behind the same SESSION_CODE auth as everything else — see
[PROTOCOL_REFERENCE.md](PROTOCOL_REFERENCE.md#2-auth-handshake-session_code) — and every mutation
additionally requires the CSRF header and an `Origin` match, exactly like mocks, capture rules, and
every other command route.

## Why evidence lives server-side, not in the browser

Before this design, the evidence tray was a JavaScript `Map` in `dashboard.js`, with two consequences:
a browser refresh destroyed every flagged item along with the typed Summary/Expected/Actual text, and
a flagged item was re-derived on demand from whatever the Network view's own list still held — once
that list moved on (paged, refreshed, filtered), the flagged item degraded to a bare label.

Evidence is now durable, server-side state in Room, following the same precedent
`timeline_annotations` already set for per-subject annotations. It survives a browser refresh, an app
restart, and — because it lives in the same database the in-app inspector reads — it is reachable
from the device itself, which a browser-local store never could be. The dashboard's `evidenceFlags`
map is a **cache** over that server state, not the source of truth: flag/unflag are optimistic (the
UI updates immediately) and reconcile against the route's actual response, and a page reload discards
nothing because everything is re-fetched from `GET /api/v1/evidence`.

## Snapshot at flag time

The defect the old design had wasn't just "no persistence" — it was that a flagged item pointed at
*live* data rather than owning a copy of it. The server now **materializes the subject once, at flag
time**, from the same already-redacted sources the detail endpoints use, and stores that materialized
snapshot verbatim. A report built an hour after flagging says exactly what it said at the moment of
flagging, even if the underlying transaction has since scrolled off the list, the socket has closed,
or the push event store has moved on.

What gets captured into `snapshotJson` depends on the kind of thing flagged:

| Kind | Snapshot contents |
|---|---|
| `NETWORK` | The transaction's full detail JSON: method, URL, status, duration, request/response headers and bodies, timing breakdown. |
| `TIMELINE` | Summary, level, source, plugin, message, stack trace, tags, `attachmentId` if the event carries one. |
| `SOCKET` | Connection URL, frame direction, opcode/frame type, payload, timestamp. |
| `PUSH` | Provider, lifecycle, and the push event's own JSON (push events have no durable id, so the subject is the event's position in the store — see below). |
| `SCREENSHOT` | The screenshot timeline event's summary and snapshot JSON, plus `attachmentId`. |
| `CRASH` | Kind (`UNCAUGHT`/`ANR`), thread name, summary, stack trace/all-thread dump, and breadcrumbs — the same shape `CrashCapture` uses when it auto-flags a crash (see below), so a manually re-flagged crash and an auto-flagged one look identical. |

Fields that are genuinely unavailable are **omitted, not defaulted** — a screenshot snapshot never
invents a byte count it doesn't have, for instance. Nothing here is fabricated to fill a gap; that
honesty rule governs the rest of this SDK's capture paths and evidence is no exception.

Push events have no durable id of their own, so a flagged push item's `subjectId` is the event's
position in `PushStore.events()` at flag time — the same convention `GET /api/v1/push/events` already
uses for addressing a specific push event.

## Auto-flagging crashes and ANRs

Every crash and every ANR is flagged into the evidence tray automatically, at insert time, with kind
`CRASH` — this is the one thing nobody should have to remember to click. `CrashCapture` does this
itself (`sdk:full`'s `CrashCapture.autoFlagCrash`), immediately after the crash/ANR event is durably
persisted, so a missing, unavailable, or over-quota evidence store can never cost you the crash record
itself — the auto-flag attempt is best-effort and swallows its own failure. Auto-flagging is keyed by
the crash event's own id as the evidence subject, so a later manual re-flag of the same crash (same
`kind`/`subjectId`) is idempotent against it rather than creating a duplicate.

## Caps

Enforced in `RoomEvidenceStore` unconditionally — it never trusts a caller to have validated these —
and mirrored at the `sdk:server-ktor` route boundary so a rejection comes back as a clean
`VALIDATION_FAILED` rather than an unhandled exception:

| Limit | Value | What happens beyond it |
|---|---|---|
| `snapshotJson` per item | 256 KiB | Truncated, not rejected: replaced with a small JSON envelope `{"truncated":true,"originalLength":N,"snapshot":"<preview>"}` that still fits under the cap. A flagged response body must not become an unbounded database row. |
| Items per session | 200 | `EvidenceWriteResult.QuotaExceeded` → route responds `409 EVIDENCE_QUOTA_EXCEEDED`. A tray holding 200 items is already an unusable bug report; the fix is to clear or export, not raise the ceiling. |
| `summary` / `expected` / `actual` (report draft) | 4096 characters each | Route responds `400 VALIDATION_FAILED` before the store is ever called. |
| `label` | 512 characters | Same. |
| Evidence subject id (route boundary) | 512 characters | Same — guards against a pathological id reaching the store at all. |

Flagging the same `(sessionId, kind, subjectId)` triple twice is not an error in the everyday sense —
it returns `EvidenceWriteResult.AlreadyFlagged` → `409 ALREADY_FLAGGED`, since flagging is meant to be
idempotent per subject rather than accumulating duplicate rows for one transaction.

The three numeric caps above (`label`, `summary`/`expected`/`actual`, items per session) are public API:
`EvidenceStore.MAX_LABEL_LENGTH`, `EvidenceStore.MAX_TEXT_LENGTH`, and
`EvidenceStore.MAX_ITEMS_PER_SESSION` are `const val`s on `sdk:storage-api`'s `EvidenceStore` companion,
not magic numbers duplicated per call site. `RoomEvidenceStore`, the `sdk:server-ktor` route boundary,
and the Android in-app inspector's own flag validation all reference the same three constants rather
than each declaring a private copy — a review of this SDK found the `CRASH` evidence snapshot had
diverged between the device and the server precisely because caps like these had been re-declared in
two places, so a shared, public source is the fix, not a coincidence of refactoring.

Evidence mutations (`POST`/`DELETE`/`PUT` on `/api/v1/evidence*`) additionally share a
30-requests-per-minute sliding-window rate limiter across the whole route family, the same shape every
other command route in this SDK uses.

## Retention

Evidence items and the report draft are deleted in the same transaction that deletes a pruned
session's events and attachments (`RoomSessionStore.completeSessionDeletion`) — an evidence row
pointing at a session that no longer exists would be the same class of bug this transaction already
guards against for events and attachments. This is a JVM unit-tested path
(`RoomEvidenceStoreTest`); the migration that creates the evidence tables in the first place
(`MIGRATION_4_5`) is only exercised by an `androidTest` that has not been run on a device — see
[Known limitations](#known-limitations) below.

## Routes

Every mutation requires bearer auth, an `Origin` match, and the CSRF header, and records through the
command audit log (`GET /api/v1/plugins/audit`) — identical to the existing mocks/flags/capture-rules
routes. There is no separate `EditingCapabilities` gate for evidence: unlike mocks, capture rules,
preferences, files, or the database, flagging evidence never lets a session touch host state, so it is
gated the same way a bookmark or a note is, not the same way a write to app data is.

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/v1/evidence` | Items (oldest first) and the report draft for the active session. `503 EVIDENCE_UNAVAILABLE` if no store is wired. Accepts `?limit=` (1–200, defaults to 200) and `?offset=` (defaults to 0) to page through a session's items independently of the store's own 200-item quota; the response also carries `totalCount` and `hasMore` so a caller can tell it paged rather than saw everything. The shipped dashboard sends neither parameter and reads only `data`/`report`, so it sees the same full list it always has — paging exists for callers that want it, not because the default behavior changed. |
| `POST` | `/api/v1/evidence` | Form body `kind`, `id` (the subject id). Materializes the snapshot and persists. `201` with the stored item on success; `404 NOT_FOUND` if the subject can't be resolved; `409 ALREADY_FLAGGED` / `409 EVIDENCE_QUOTA_EXCEEDED`; `400 VALIDATION_FAILED` for a missing/unknown `kind`, a blank or over-long subject id, or an over-long materialized label. |
| `DELETE` | `/api/v1/evidence/{kind}/{id}` | Unflag one item by its `(session, kind, subject)` identity. A no-op (not an error) if it wasn't flagged. |
| `DELETE` | `/api/v1/evidence` | Clear every flagged item for the session. The report draft is untouched. |
| `PUT` | `/api/v1/evidence/report` | Form body `severity` (`BLOCKER`/`CRITICAL`/`MAJOR`/`MINOR`/`TRIVIAL`, defaults to `MAJOR` if omitted), `summary`, `expected`, `actual`. `400 VALIDATION_FAILED` for an unrecognised severity or an over-long text field. |

`GET /api/v1/evidence`'s item JSON carries `id`, `kind`, `subjectId`, `label`, `flaggedAtMs`,
`snapshot` (the raw stored `snapshotJson`, embedded — not re-serialized as a string), `attachmentId`
(nullable), and `redactionApplicability` — a **live lookup** against the attachment's currently stored
value, not a value cached on the evidence row itself, so a badge painted from this field can never go
stale relative to what `GET /api/v1/attachments/{id}` would report. See
[SECURITY_AND_REDACTION.md](SECURITY_AND_REDACTION.md#the-unredacted-badge) for what that field means
and why it exists.

The dashboard's report draft autosaves 750ms after the last keystroke and immediately on blur, and a
save reconciles the in-memory draft against exactly what the server persisted (including its
`updatedAtMs`) so the "Saved <time>" indicator always reflects a round trip, not an optimistic guess.

## The evidence bundle

Requested through the **existing** `POST /api/v1/exports` route with a `scope=EVIDENCE` form
parameter (and estimated via `GET /api/v1/exports/estimate?scope=EVIDENCE`) — nothing new was built
for transport, so the bundle inherits CSRF protection, the export size limit, truncation reporting,
and stale-export-file pruning for free. On device, the same content is not yet wired to a dedicated
export button; the in-app exporter currently only produces HAR/Postman/session-ZIP (see
[DATA_INSPECTORS_AND_EXPORTS.md](DATA_INSPECTORS_AND_EXPORTS.md#exports)).

The estimate route shares the **export** rate limiter with `POST /api/v1/exports` itself — 5 requests
per 10 minutes per session — not the ordinary 120-per-minute read limiter every other `GET` route gets.
Assembling an estimate does the identical evidence-bundle-sizing work a real export does (materializing
every flagged item's metadata to compute a size), so it is priced like the expensive operation it is,
not like a cheap read.

A successful export downloads `devconsole-evidence.zip` containing:

```
report.md                    human-readable bug report (severity, summary, expected, actual,
                              environment, and an index of every flagged item)
report.json                  the same content, machine-readable — the persisted report row plus
                              every flagged item as stored (not re-queried)
network.har                  HAR 1.2, flagged NETWORK items only, best-effort against the live
                              NetworkTransactionStore (a flagged item still names its subject even
                              if the live transaction has since been pruned; it just can't
                              contribute a HAR entry for that one item)
postman_collection.json      Postman Collection v2.1, same NETWORK-items-only scope
session.json                 app id, version, build type, device model, API level, OS version
attachments/
  screenshots/<hash>.png     one file per flagged item with a SCREENSHOT-kind attachment
  bodies/<hash>.bin          one file per flagged item with any other attachment (e.g. a network
                              body)
manifest.json                every file above, its SHA-256, its byte size, and its
                              redactionApplicability (APPLIED or NOT_APPLICABLE) — see below
```

`report.md` and `report.json` are built entirely from the **persisted** report row and the
**snapshotted** items — never from a live re-query — so the bundle can never diverge from what the
tray showed when it was assembled. Every non-attachment text file in the bundle carries
`redactionApplicability: APPLIED` in the manifest unconditionally: each was built from already-redacted
snapshot data and redacted a second time on the way out, the same "redact again at export time, never
trust capture-time redaction alone" rule [SECURITY_AND_REDACTION.md](SECURITY_AND_REDACTION.md#exports)
describes. Attachment files carry whatever `RedactionApplicability` is authoritatively stored against
that attachment — a flagged screenshot's file in `attachments/screenshots/` is visibly
`NOT_APPLICABLE` in the manifest, not silently indistinguishable from a redacted request body sitting
next to it in `attachments/bodies/`. If the attachment's stored metadata can't be read (the row is
already gone, or the read failed transiently), the manifest reports `redactionApplicability: null` for
that entry — **except** for a screenshot-kind item, where `NOT_APPLICABLE` is still knowable without the
row at all, since pixels are never text-redacted by construction. `null` here means "no claim could be
established," not "assume redacted": an earlier version of this path filled the gap by guessing `APPLIED`
for anything that wasn't a screenshot, which could label unredacted content as safe. That inversion was
closed — absent now means unknown, never a fabricated `APPLIED`.

Size behavior matches every other export: exceeding the request's `maxBytes` returns
`413 EXPORT_TOO_LARGE` with an `estimatedBytes` figure and guidance to retry with a narrower scope
(there is no metadata-only or time-range mode for an evidence bundle specifically — the whole point is
that it's built from what's already flagged, which is already a bounded set by the 200-item cap
above). A bundle that had to drop content reports it the same way the whole-session export pipeline
does — a truncated artifact must say so, never look silently complete.

## Clipboard formats

Three formatters, all client-side in `dashboard.js`, with **no network egress** — copying a bug report
to the clipboard never leaves the device the browser is running on:

- **Markdown** — the original format, now reading from the persisted evidence state instead of the
  old in-memory `Map`.
- **Jira wiki markup** — `h2.` headings, `{code}` blocks, `||`-delimited table headers.
- **GitHub issue** — Markdown with collapsible `<details>` blocks around stack traces and payloads, so
  a long dump doesn't dominate the issue body by default.

Each formatter reads the same "what am I looking at right now" source: the currently-typed values in
the Summary/Expected/Actual inputs when present (so "copy now" reflects text not yet autosaved),
falling back to the last-persisted report draft otherwise — one shared source across all three
clipboard formatters, so they can never disagree with each other about what the current draft says.
The exported **bundle** is different: `report.md`/`report.json` are built strictly from the
**persisted** report row (see above), never from unsaved keystrokes still sitting in the inputs — an
export is a server-side operation with no access to what a particular browser tab happens to have
typed and not yet autosaved.

## Known limitations

- **The 4→5 Room migration and the evidence cascade-delete test have never been executed — and for most
  of this feature's development, they could not have been, on any device.** `Migration4To5InstrumentedTest`
  and `EvidenceCascadeDeletionInstrumentedTest` are `androidTest`s that need `MigrationTestHelper` and a
  real Room database, and the earlier assumption was that they simply awaited an available emulator. The
  real cause was worse: `sdk:storage-room`'s entire instrumented APK could not be **dexed** at all,
  because one unrelated test in that module (`RoomAttachmentStoreInstrumentedTest`) used a backtick
  method name containing spaces — a name form that requires `minSdk 30` to dex, on a module whose
  `minSdk` is 24. That failure would have blocked every `androidTest` in `sdk:storage-room`, migration
  and cascade-delete tests included, even with a device or emulator attached. This is now fixed (the
  offending test was renamed to a plain identifier) and the `androidTest` APK assembles cleanly. The
  tests remain unexecuted here only because no emulator was available in this environment — but they
  have **never once run**, against any database, real or synthetic. Treat the migration path and the
  cascade-delete guarantee as reviewed-but-not-verified, and run them before publishing: this migration
  guards every real user's existing evidence and attachment data.
- **The in-app Compose inspector flags network transactions and crashes only.** Those two are backed
  by the same durable `EvidenceStore` the dashboard uses, writing the same snapshot shapes, so a flag
  set on the device appears in the browser tray and the reverse. Timeline, socket, push and
  screenshot items are not flaggable from the device — not because the store cannot hold them, but
  because the inspector has no flag affordance on those screens, and adding one was out of scope.
- **There is no standalone evidence-tray screen on Android.** Flagging is inline on the network and
  crash detail screens; the report draft (severity, summary, expected, actual), the bundle export and
  the clipboard formats exist only in the web dashboard. A device-only workflow can therefore collect
  evidence but not file it.
