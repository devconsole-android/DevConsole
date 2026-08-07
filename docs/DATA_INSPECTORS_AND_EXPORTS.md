# Data inspectors, exports, and the More screen

This page covers the three pieces of the dashboard that read and act on the app sandbox rather
than network/socket/push traffic: the **Data** inspectors (preferences, database, files), the
device's **More** screen, and the **export** formats (HAR, Postman, and the Android session ZIP).
All of it sits behind the same SESSION_CODE auth as everything else — see
[PROTOCOL_REFERENCE.md](PROTOCOL_REFERENCE.md#2-auth-handshake-session_code) — and every mutating
action additionally gates on the host's `EditingCapabilities`, which default to all-`false`
(`EditingCapabilities.readOnly()`). A host opts specific features in:

```kotlin
DevConsole.initialize(
    application,
    DevConsoleConfig(
        editingCapabilities = EditingCapabilities(
            preferences = true,
            database = true,
            files = true,
            mocks = true,
            captureRules = true,
            featureFlags = true,
            requestExecution = true,
        ),
    ),
)
```

Every capability is independent — a host can, for example, allow editing feature flags without
allowing raw SQL against its database. See
[THREAT_MODEL.md](THREAT_MODEL.md#what-is-protected-and-against-what) for why this replaces a session-level
role: every authenticated session is equivalent, and these flags bound *what the console can do*,
not *who* can do it.

## Preferences inspector

Lists every `shared_prefs` XML file and its entries (`GET /api/v1/preferences`). String values pass
through redaction, and any entry whose name matches the redaction policy is shown as
`<redacted>` and carries a `redacted` flag in the response. With the `preferences` capability, a
session can write (`POST /api/v1/preferences/{file}`) or delete (`DELETE
/api/v1/preferences/{file}?key=`) an entry — writes to a currently-redacted key are refused, so a
browser session can never overwrite a real secret with the placeholder it was shown in place of the
value. See [PROTOCOL_REFERENCE.md](PROTOCOL_REFERENCE.md#3-rest-routes) for the exact wire shape.

## Database inspector

Lists the app's SQLite databases and, per database, its tables with row counts (internal
`sqlite_%` tables are excluded/refused throughout). `GET
/api/v1/database/{name}/tables/{table}` returns the first 200 rows with column-name-based
redaction applied. The `database` capability is required for **every** statement against `POST
/api/v1/database/{name}/sql`, including `SELECT` — a caller who controls the SQL text can alias
any column past name-based redaction (`SELECT password AS p ...`), so enabling this capability is
enabling raw, unredacted database access over the session, not just "editing." Non-`SELECT`
statements are classified by leading keyword; everything else runs read-only.

## Files inspector

Exposes exactly four app-sandbox roots — `files`, `cache`, `external-files` (when external storage
is mounted), and `no-backup` — nothing outside them is ever reachable, and canonical-path
confinement defeats `..` and symlink escapes. Read access (listing, a redacted/binary-sniffed
64 KiB preview) needs only an authenticated session; the `files` capability additionally gates
**create**, **replace** (an existing file's content), **rename** (same root only, never
clobbers), **delete** (regular files only, never directories), and **download** (raw, unredacted
bytes, capped at 10 MiB — gated even though it's a `GET`, since it's the one route that returns
data with no redaction pass at all). Every file operation, download included, lands in the command
audit log (`GET /api/v1/plugins/audit` — see
[PROTOCOL_REFERENCE.md](PROTOCOL_REFERENCE.md#command-audit)).

**Treat `files` as code-execution-adjacent, not just data access.** If your app loads anything
executable or executable-adjacent from its own storage — a JS bundle, a dynamic config, a native
library staged at runtime — a session with `files` enabled can overwrite it. See
[THREAT_MODEL.md](THREAT_MODEL.md) before enabling it outside a fully trusted debug session.

On-device, the same file surface backs a native **Share** action (Android's share sheet) via a
non-exported `FileProvider` (`<applicationId>.devconsole.files`) that every build depending on
`sdk:full` registers through manifest merge — grants are single-URI and temporary.

## The More screen

The device-side "More" destination (`sdk:ui-compose`'s `InspectorMoreScreen`, reachable from
`DevConsole.open(context)`, or via the opt-in shake / floating-button triggers in
`DevConsoleConfig.openTriggers`) is the operator console for the running server, independent of any
connected browser:

- **Session-code / connect** — shows the live `#code=` connect URL as text and as a **QR code**
  (rendered on-device with an embedded QR encoder, no third-party dependency or network call), so a
  second device can scan its way in without retyping an 8-character code. Regenerating shows the
  new code/QR immediately; the previous code is invalidated the instant a new one is issued.
- **Browser sessions** — every connected browser (label, source IP, expiry) with a per-session
  **Revoke** action. Revocation is symmetric: any session, including one revoking itself, can end
  any other (`DELETE /api/v1/auth/principals/{id}`) — see
  [THREAT_MODEL.md](THREAT_MODEL.md#what-is-protected-and-against-what).
- **SDK health** — the same snapshot as `GET /api/v1/sdk-health`: initialization/publish/drop
  counters, runtime state, and live active-session count.
- **Retention usage** — how much of the event-store and attachment quotas (see
  [STORAGE.md](STORAGE.md)) the current install has used, plus retained past app-run sessions you
  can select to inspect or export.
- **LAN warning** — a persistent banner while the server is bound in `BindingMode.LAN`, since that
  binding mode means plaintext HTTP is reachable by anything else on the network (see
  [THREAT_MODEL.md](THREAT_MODEL.md)).
- **Export controls** — HAR, Postman, and "Export session ZIP" buttons; see below.

## Exports

Four formats, two trigger points (three of the four reachable from the browser; the fourth is
browser-only for now), one shared selection/redaction path.

| Format | Browser route | On-device trigger | Contents |
|---|---|---|---|
| HAR 1.2 | `GET`/`POST /api/v1/network/har` | `InspectorExporter.exportHar(selection)` (More screen "HAR" button) | Network transactions only |
| Postman Collection v2.1 | `GET`/`POST /api/v1/network/postman` | `InspectorExporter.exportPostman(selection)` (More screen "Postman" button) | Network transactions, headers + text bodies, deduplicated (see [NETWORK_INSPECTOR.md](NETWORK_INSPECTOR.md)) |
| Session ZIP | `POST /api/v1/exports` | `InspectorExporter.exportSessionZip()` (More screen "Export session ZIP" button) | Timeline events (with bookmarks/notes), network transactions as HAR *and* Postman, attachments, and app metadata, bundled flat in one ZIP |
| Evidence bundle | `POST /api/v1/exports` with `scope=EVIDENCE` (estimate: `GET /api/v1/exports/estimate?scope=EVIDENCE`) | Not yet wired to an on-device button | `report.md`/`report.json`, `network.har`/`postman_collection.json` (flagged `NETWORK` items only), `session.json`, `attachments/{screenshots,bodies}/`, and `manifest.json` — see [EVIDENCE_AND_BUG_REPORTS.md](EVIDENCE_AND_BUG_REPORTS.md#the-evidence-bundle) for the full contents and why it's built from persisted evidence-tray state rather than a live re-query |

The evidence bundle's `manifest.json` is the one export manifest that records
**per-file redaction applicability**, not just size and checksum — every entry carries a
`redactionApplicability` of `APPLIED`, `NOT_APPLICABLE`, or `null` (when the attachment's stored
applicability genuinely could not be determined — never guessed as `APPLIED`), so a flagged screenshot
sitting in `attachments/screenshots/` is visibly marked unredacted rather than looking identical to a
redacted request body next to it in `attachments/bodies/`. See
[SECURITY_AND_REDACTION.md](SECURITY_AND_REDACTION.md#redactionapplicability-and-the-storage-boundary)
for what that field means, and
[EVIDENCE_AND_BUG_REPORTS.md](EVIDENCE_AND_BUG_REPORTS.md#the-evidence-bundle) for exactly when `null`
applies.

Both the browser routes and the on-device exporter select transactions through the same
`ExportSelection` type (`All` / `Ids` / `TimeRange`) so a browser export and an in-app export of
"the same thing" can never diverge on which rows are included, and both re-run redaction with the
*current* policy at export time — an export can never bypass or predate capture-time redaction. See
[SECURITY_AND_REDACTION.md](SECURITY_AND_REDACTION.md#exports) and
[PROTOCOL_REFERENCE.md](PROTOCOL_REFERENCE.md#session-integrity-bug-report-and-exports) for the
exact request/response shapes and rate limits.

### Selective export (HAR/Postman only — the Session ZIP always exports the whole session)

Both HAR and Postman support exporting an explicit subset of network transactions instead of
everything captured:

- **Web dashboard.** The Network view's transaction list has a checkbox per row and a header
  checkbox that selects/deselects every row currently visible. Checking any row shows a selection
  bar with the selected count, a **Select all matching filter** action (widens the selection to
  every transaction matching the current filters, not just the currently-loaded page, up to the
  500-row bound below), and **HAR**/**Postman** buttons that export just the selection. The
  toolbar's own HAR/Postman buttons still work as before and export everything when nothing is
  checked.
- **Compose in-app inspector.** The Observe traffic tab supports the same selection idiom as the
  dashboard: long-press a row to enter selection mode (checkboxes appear on every row), tap to
  extend the selection, and either back-press or the selection bar's **Clear** button exits it and
  empties the selection. The bar itself shows the live count, a **Select all matching filter**
  action (unions every row currently matching the tab's search/chip filter into the selection —
  bounded to what this module has already loaded, since it filters entirely client-side and has no
  server round-trip to widen further, unlike the dashboard's version of the same action), and
  **HAR**/**Postman** buttons. `InspectorState.selectedTransactionIds` is threaded through
  `InspectorAction.ExportHar`/`ExportPostman` to `InspectorDataSource.exportHar(ids)`/
  `exportPostman(ids)` — an empty selection exports everything, matching the browser's "nothing
  checked = export all" convention, and the More screen's HAR/Postman rows independently read and
  display the same selection (so leaving the Traffic tab with a selection still active doesn't lose
  it). `InspectorAction.SelectTransactions`/`ClearTransactionSelection` back the bulk-select/clear
  actions; `InspectorAction.ToggleTransactionSelection` backs the per-row long-press/tap.
- **Server.** Both selection forms — an explicit id list, or "everything matching the current
  filter" — are the existing `ExportSelection.Ids`/`ExportSelection.All` resolved against the same
  filter query params `GET /api/v1/network/transactions` accepts, so "what you'd see if you applied
  this filter to the list" and "what an export with this filter selects" can never disagree. A large
  id-based selection (checkbox-driven, potentially hundreds of ids) is submitted via the `POST`
  variant of the HAR/Postman routes (ids in the form body) rather than as `?id=` query params, to
  avoid the request-line-length limits a GET query string runs into well before the 500-id bound
  does; `POST` requires the same CSRF/Origin header as every other mutation-shaped route.

**Bound and truncation.** Selective export reuses the export routes' existing 500-row page bound —
raising it would mean rendering more than 500 full transaction bodies into one response, the same
memory risk the list endpoint is bounded against, so this SDK deliberately keeps it rather than
raising it. Instead of silently dropping rows past the bound, every export response carries
`X-DevConsole-Export-Count`, `X-DevConsole-Export-Truncated`, and `X-DevConsole-Export-Limit`
headers (see [PROTOCOL_REFERENCE.md](PROTOCOL_REFERENCE.md#network-inspector) for the exact
contract); the web dashboard surfaces a truncation as a toast naming how many transactions actually
made it into the download.

The Compose in-app exporter (`InspectorExporter.exportHar`/`exportPostman`, called directly —
not over HTTP, so there are no response headers to read) applies the identical bound: it resolves
through the same `ExportSelection`/`NetworkTransactionStore.resolveExportSelection` the browser
routes use, capped at the same `NetworkTransactionQuery.MAX_PAGE_LIMIT` (500). An id-based selection
built from the Traffic tab (whether from individual taps or **Select all matching filter**) can
never reach that bound in the first place — the tab never loads more than 200 transactions into
`InspectorState.transactions` to begin with — so the only export shape that can actually hit the
500-row cap is the empty-selection "export everything" one (the More screen's HAR/Postman rows with
no active selection). Since `InspectorExporter`'s result carries a written file path and size, not a
row count, the Compose layer cannot compute a dynamic "N of M exported" message for that case the
way the dashboard's response-header toast does; instead, the More screen's HAR/Postman rows state
the 500-row cap directly in their subtitle whenever the export scope is "everything" ("Redacted ·
all captured traffic (capped at the 500 most recent)"), so a capped export is never presented as
if it were complete.

**In-app exports are full-fidelity, redacted diagnostic artifacts, not throwaway files.** They
persist under `filesDir/devconsole-exports` (pruned to the five most recent), which inherits your
app's Android auto-backup settings unless you add your own backup-exclusion rule — see
[THREAT_MODEL.md](THREAT_MODEL.md) for what that implies and why the SDK doesn't add the exclusion
for you.
