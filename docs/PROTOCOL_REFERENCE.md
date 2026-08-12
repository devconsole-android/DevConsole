# Protocol reference

The embedded server (`sdk:server-ktor`, `DevConsoleKtorModule.kt`) exposes a local HTTP + WebSocket
API consumed by the browser dashboard. This document is extracted directly from that
implementation — it describes the wire protocol as it actually behaves, including a few quirks
that are easy to miss when reading the routes in isolation. There is no JSON library involved
anywhere in this server: every response is a hand-built string, so field order and nullability
below are exactly what ships.

If this document and the code ever disagree, the code wins — treat a mismatch as a doc bug, not a
protocol change.

## Conventions

- **CSRF header:** `X-DevConsole-CSRF`.
- **CSRF/Origin check** (used by most mutating routes):
  ```
  expectedOrigin = "http://" + Host header
  reject (403 CSRF_INVALID) unless Origin header == expectedOrigin AND X-DevConsole-CSRF == session.csrfToken
  ```
  The expected origin is hardcoded to the `http://` scheme regardless of how the client actually
  connected.
- **Bearer auth:** `Authorization: Bearer <token>`. Missing, invalid, or expired all collapse to the
  same `{"code":"AUTH_REQUIRED"}` 401 — the server never distinguishes these cases at the HTTP layer.
- **Access levels:** none. Every authenticated session is equivalent (`auth` in the tables below
  means "any live session"); mutating routes additionally gate on the host's `EditingCapabilities`
  flags (`mocks`, `captureRules`, `preferences`, `database`, `files`), not on a session-level role.
- **Request-body content types.** Routes that read a form body (`POST /api/v1/push/simulate`, the
  `POST` halves of `/api/v1/network/har` and `/api/v1/network/postman`, `POST /api/v1/exports`,
  `POST /api/v1/preferences/{file}`, …) need
  `Content-Type: application/x-www-form-urlencoded`; routes that read a raw scalar
  (`POST /api/v1/flags/{key}`, the `/enabled` toggles) read the body as text. A form route sent the
  wrong media type — JSON, or no `Content-Type` at all — answers `400 VALIDATION_FAILED` (Ktor
  reports it as a failed content transformation); the rarer typed media-type rejection answers `415
  UNSUPPORTED_MEDIA_TYPE`. Neither is reported as a `500`, so an `INTERNAL_ERROR` from this server
  always means a genuine server-side fault worth reporting.

## 1. Host allowlist and rate limiting

Runs ahead of every request (including the WebSocket upgrade), before routing.

**Host allowlist:** the `Host` header (port stripped, lowercased) must be in `allowedHosts`
(default `{"localhost", "127.0.0.1"}`; `KtorLocalServerEngine` sets it to `{"localhost", <bound
host>}` at start-up). Otherwise: `{"code":"ORIGIN_REJECTED"}` 403.

**Rate limiting** only applies to requests carrying a valid bearer token, keyed by session id:

| Path / method | Limiter | Max events | Window |
|---|---|---|---|
| `POST /api/v1/composer/execute` | composer | 20 | 1 min |
| `/api/v1/mocks*`, non-GET | mocks mutation | 30 | 1 min |
| paths containing `/mutations/` | state mutation | 30 | 1 min |
| `/api/v1/preferences*`, non-GET | preferences mutation | 30 | 1 min |
| `POST` under `/api/v1/database` | database SQL | 30 | 1 min |
| `/api/v1/files*`, non-GET | files mutation | 30 | 1 min |
| `POST /api/v1/push/simulate` | push simulation | 20 | 1 min |
| `GET`/`POST /api/v1/network/har`, `GET`/`POST /api/v1/network/postman` | network export | 10 | 1 min |
| any other GET under `/api/v1/` | read query | 120 | 1 min |

`POST /api/v1/auth/session-code/exchange` has its own limiter (5/min), checked inline and keyed by
**source IP** rather than session, since it runs before a session exists.

One rate limiter is wired into the dispatcher's `when` for
`.../websockets/connections/{id}/send` without a matching route — dead capacity in the current
build. (`POST /api/v1/exports` and `GET /api/v1/report` do have routes — checked inline rather
than through the table above — sharing one 5-per-10-minute export limiter; see §3.)

## 2. Auth handshake (SESSION_CODE)

SESSION_CODE is the only browser-access flow. There is no approval step: possessing the code within
its TTL *is* the authorization decision.

1. **Issue.** `SessionCodeAuthority.issueCode()` generates an 8-character code from an unambiguous
   alphabet (`23456789ABCDEFGHJKMNPQRSTWXYZ` — excludes `0`/`O`, `1`/`I`/`L`, `U`/`V`), 5-minute TTL
   by default (`sessionCodeTtlMs` on `StartRequest`), surfaced to the host app as a fragment URL
   (`.../#code=<code>`) out-of-band. Only one code is live at a time — issuing a new one immediately
   invalidates the previous one, consumed or not.
2. **`POST /api/v1/auth/session-code/exchange`** — no bearer auth. Rate-limited 5/min by source IP.
   Form body: `code` (required), `browserLabel` (free text). On success, `200
   {"accessToken":"...","csrfToken":"..."}` — the session is created immediately, with no separate
   poll/approval round trip, and installs the `DevConsoleStreamSession` cookie. The code is
   **single-use**: a second exchange of the same code returns `SESSION_CODE_INVALID` even before it
   expires.
   Errors: `SESSION_CODE_INVALID` 401 (unknown/wrong/already-used code, or none ever issued),
   `SESSION_CODE_EXPIRED` 401 (**no-fallback expiry** — the host must call `issueCode()` again; there
   is no automatic regeneration and no fallback), `SESSION_CODE_SESSION_LIMIT` 409 (concurrent-session
   cap, `SessionPolicy.maxAuthenticatedSessions`, default 10), `RATE_LIMITED` 429,
   `VALIDATION_FAILED` 400 (missing `code`). **A capacity 409 still consumes the code** — burned by
   the exchange attempt rather than by success, same rationale as a bootstrap secret would be.
   Revoking the blocking session and retrying the same code yields `SESSION_CODE_INVALID`; the host
   must issue a fresh code.
3. Subsequent calls use `Authorization: Bearer <accessToken>` plus, for mutations,
   `X-DevConsole-CSRF: <csrfToken>` and a matching `Origin`. Sessions expire after 30 minutes;
   `POST /api/v1/auth/refresh` rotates both tokens and resets the TTL. The session's 30-minute
   refreshable TTL is independent of the code's TTL — the code bounds acquisition, not session
   lifetime.

The 29-character alphabet at length 8 gives ≈38.9 bits of entropy; the 5/min-per-IP limiter is what
makes that sufficient against online guessing (≈25 attempts per IP within a 5-minute code lifetime).

Other auth routes: `POST /api/v1/auth/logout` (204), `GET /api/v1/auth/principals` (auth — lists
live sessions), `DELETE /api/v1/auth/principals/{id}` (auth — revokes any session, not just the
caller's own), `GET /api/v1/session` (auth — "who am I").

## 3. REST routes

`auth` = any authenticated session, `none` = unauthenticated.

### Root, meta, overview

| Route | Auth | Notes |
|---|---|---|
| `GET /` | none | Dashboard HTML, `Cache-Control: no-store` |
| `GET /health` | none | `{"status":"auth_required","protocolVersion":...,"appDisplayName":"..."}` (static status field) |
| `GET /api/v1/meta` | auth | App/build/capabilities/bound-endpoint/redaction policy |
| `GET /api/v1/sdk-health` | auth | `SdkHealthSnapshot`, `activePrincipalCount` recomputed live |
| `GET /api/v1/overview` | auth | App info + mock engine state + SDK health + network status histogram |

### Timeline / events — `GET /api/v1/events`

Cursor-paginated (`limit` 1–500, default 100; `sort` `ASC`|`DESC`; filters `pluginId`, `type`,
`severity`, `correlationId`, `query` are repeatable/optional). Response:
`{"data":[{"id","sequence","wallTimeMs","severity","pluginId","type","summary"}...],"page":{"nextCursor","hasMore"}}`
— a projection of the stored event; `sessionId`, `correlationId`, tags, and payload are not
included in this list response.

Annotation routes — `POST`/`DELETE /api/v1/events/{id}/bookmark`, `PUT /api/v1/events/{id}/note`
(note ≤ 4096 chars) — require an authenticated session, same as everything else. **Quirk:** these
three routes share a helper that folds the auth check and the CSRF check into one condition, so a
request with no `Authorization` header at all against an existing event id is reported as
`{"code":"CSRF_INVALID"}` 403, never `AUTH_REQUIRED`.

### Network inspector

`GET /api/v1/network/transactions` (cursor-paginated, offset-based cursor under the hood — see
§4.2), `GET /api/v1/network/transactions/{id}` (adds request/response headers, no bodies),
`GET /api/v1/network/transactions/{id}/curl` (shell-escaped cURL reproduction), `GET`/`POST
/api/v1/network/har` (HAR 1.2; timing fields are placeholders — `time`/`send`/`wait`/`receive`
always `0`, `headersSize`/`bodySize` always `-1`), `GET`/`POST /api/v1/network/postman` (Postman
Collection v2.1, request/response headers and text bodies included). All four share one selection
resolver:

- An explicit id list is an exact match (unknown ids are silently dropped, never an error): as
  `?id=` query params (repeatable) on the `GET` routes, or as repeated `id` form fields in the body
  on the `POST` routes. The `POST` routes exist only so a large checkbox-driven selection (up to the
  500-row bound below) doesn't have to fit in a GET request line; they are otherwise identical to
  their `GET` counterparts and require the same auth. Unlike the `GET` routes, `POST` additionally
  requires the standard CSRF/Origin check (see "Conventions" above) — the same gating as `POST
  /api/v1/exports` — since a `POST` is what a same-origin-restricted browser would otherwise
  auto-CSRF; explain a `CSRF_INVALID` response on `POST /api/v1/network/har`/`postman` by that, not
  by anything selection-specific.
- With no explicit ids, every transaction matching the same filter query params `GET
  /api/v1/network/transactions` accepts (read from the query string on both verbs — filters never
  travel in the `POST` body, only ids do), i.e. "export everything matching the current filter."

**Bound and truncation.** Both the id list and the filter match are capped at
`NetworkTransactionQuery.MAX_PAGE_LIMIT` (500) rows — the same bound the list endpoint's `limit`
param is capped at; the export routes do not raise it, since rendering more than 500 full
transaction bodies into one response is the same memory cost the list endpoint was bounded against.
Rather than silently dropping the overflow, every response (in both formats, both verbs) carries
three headers describing what was actually included:

| Header | Meaning |
|---|---|
| `X-DevConsole-Export-Count` | Number of transactions actually rendered into the body |
| `X-DevConsole-Export-Truncated` | `true` if more transactions matched than were included — either some requested ids didn't resolve, or a filter match had more than 500 rows |
| `X-DevConsole-Export-Limit` | The bound applied (currently always `500`) |

These are headers, not body fields, so the exported file itself stays a byte-for-byte valid HAR/
Postman document regardless of truncation.

Both export routes are additionally rate-limited separately from other routes (shared by `GET` and
`POST`) — see §1. All `auth` (plus CSRF on `POST`, as above).

**Postman dedup rule.** `GET`/`POST /api/v1/network/postman` (and the Android in-app Postman
export) collapse requests that are identical in method, URL, headers, and request body **and**
response status code and error presence down to one item — the newest one. Two runs of the same
request that got different responses (a 200 and a later 401, say) always produce two items; only a
request repeated with the *same* outcome collapses. The response body is never part of the key,
since bodies routinely carry timestamps or request-scoped ids that would otherwise defeat dedup
entirely. One consequence: an explicit id selection can still collapse rows the caller asked for by
id, if the selected transactions happen to share a dedup key; it can also make
`X-DevConsole-Export-Count` (measured before dedup) read higher than the number of `item`s that
actually end up in the Postman collection body.

### Mocks

`GET /api/v1/mocks` (enabled flag), `POST /api/v1/mocks/disable-all` (auth), `POST
/api/v1/mocks/enabled` (auth + `mocks` capability — raw-text `"true"`/`"false"` body, returns the
resulting `{"enabled":…}`), `GET
/api/v1/mocks/rules`, `GET /api/v1/mocks/conflicts`, `POST /api/v1/mocks/rules` (auth — id must
match `[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}`, status `100..599`), `DELETE
/api/v1/mocks/rules/{id}` (auth). **Quirk:** `POST /api/v1/mocks/rules` can only ever create a
`StaticResponse` action; the `MockEngine` supports other action kinds (delay, timeout, status
override, template response, connection failure, passthrough) but they're only reachable by a host
app registering rules directly against the engine, not over HTTP. `GET .../rules`'s `statusCode`
field is `null` for any rule that isn't a `StaticResponse`.

`disable-all` and `enabled` differ in more than direction: turning mocking *off* never needs the
`mocks` editing capability (falling back to real traffic is always allowed), while turning it back
*on* does, since it changes how the host app behaves. A host that publishes rules read-only can
therefore disable mocking from the browser but not re-enable it.

### Feature flags

`GET /api/v1/flags` (adds `type` — `BOOLEAN` or `STRING` — and `allowedValues` per flag), `POST
/api/v1/flags/{key}` (auth — raw-text body; a bare value works, `"true"` and `true` are both
accepted since surrounding quotes are trimmed). `FeatureFlagType` has two members: `BOOLEAN`
(`FeatureFlag.ofBoolean`, allowed values `{"true","false"}`) and `STRING` (`FeatureFlag.ofOptions`,
allowed values the declared option set) — see [STATE_AND_FLAGS.md](STATE_AND_FLAGS.md#flags-with-named-options-environment-switching)
for the named-options use case (environment/account-tier switching).

### Composer

`POST /api/v1/composer/execute` (auth — one-off request; `method` restricted to
GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS, `url` must be an absolute http(s) URL), `GET
/api/v1/composer/collections`, `POST /api/v1/composer/import` (parses a raw `curl ...` command
from the request body), `POST /api/v1/composer/collections` (saves a curl import under a name),
`DELETE /api/v1/composer/collections/{id}`. Variable values flagged `secret` are never included in
any response, including the metadata for the request that was just executed.

### State

`GET /api/v1/state` (provider ids), `GET /api/v1/state/{id}` (snapshot), `POST
/api/v1/state/{id}/mutations/{command}` (auth). **The entire mutation route is gated by a global
`stateMutationsEnabled` flag that defaults to `false`** — until a host app opts in, every mutation
call returns `{"code":"STATE_MUTATION_DISABLED"}` 403 before auth is even checked. Body capped at
64K characters.

### Data inspectors (preferences / database / files)

`GET /api/v1/preferences` (auth — every `shared_prefs` file with entries; string values pass through
redaction and carry a per-entry `redacted` flag), `POST /api/v1/preferences/{file}` (auth +
`preferences` capability — form body `key`/`value`/`type`; **writes to a key whose current entry is
redacted are refused** so a browser can never overwrite a real secret with the `<redacted>`
placeholder it was shown), `DELETE /api/v1/preferences/{file}?key=` (auth + capability).

`GET /api/v1/database` (auth — database names), `GET /api/v1/database/{name}` (auth — tables + row
counts; internal `sqlite_%` tables excluded), `GET /api/v1/database/{name}/tables/{table}` (auth —
first 200 rows, column-name-based redaction applied; `sqlite_%` tables refused), `POST
/api/v1/database/{name}/sql` (auth + **`database` capability for all statements, including
SELECT** — a caller who controls the SQL can alias any column past name-based redaction, so the
whole console is treated as raw database access; body capped at 8K characters, mutating statements
additionally classified by leading keyword and run read-only otherwise).

`GET /api/v1/files` (auth — sandbox root names), `GET /api/v1/files/{root}?path=` (auth — directory
listing; canonical-path confinement defeats `..`/symlink escapes), `GET
/api/v1/files/{root}/preview?path=` (auth — first 64K, text redacted, binary sniffed), `DELETE
/api/v1/files/{root}?path=` (auth + `files` capability — regular files only, never directories),
`PUT /api/v1/files/{root}` (auth + capability — create, `409` if the file exists), `POST
/api/v1/files/{root}` (auth + capability — replace an existing file's content, `404` if missing),
`POST /api/v1/files/{root}/rename` (auth + capability — same root only, never clobbers), `GET
/api/v1/files/{root}/download?path=` (auth + **capability, even though it is a GET** — the
attachment streams raw, unredacted bytes, so it carries the same requirement as every other route
that reaches unredacted data; capped at 10 MiB). Write bodies are capped at 256K characters; every
operation, download included, is recorded in the command audit log.

### Push

`GET /api/v1/push/events`, `POST /api/v1/push/simulate` (auth — returns `409
PUSH_SIMULATION_UNAVAILABLE` unless the host app supplied a `PushSimulator`; `source` is hardcoded
to `"local-simulation"` regardless of client input).

### WebSocket inspector (REST introspection, not the live stream)

`GET /api/v1/websockets/connections`, `GET /api/v1/websockets/connections/{id}`, `GET
/api/v1/websockets/messages`. All `auth`, all wrapped in `{"schemaVersion":1,"data":[...]}`. **Not
cursor-paginated** — these return the entire in-memory set, bounded only by fixed caps (200
connections, 2000 messages/connection) that evict oldest entries rather than paging.

### Capture rules (network capture exclusion)

`GET /api/v1/capture-rules` (auth — `{"editable":<bool>,"data":[...]}`; `editable` mirrors the
host's `EditingCapabilities.captureRules`), `POST /api/v1/capture-rules` (auth + `captureRules`
capability — form body `id`/`host`/`method`?/`pathPrefix`?/`enabled`?, `201` with the created
rule), `POST /api/v1/capture-rules/{id}/enabled` (auth + capability — raw-text
`"true"`/`"false"` body), `DELETE /api/v1/capture-rules/{id}` (auth + capability). A rule excludes
a matching request (exact host, optional method, optional path prefix) from capture entirely —
before redaction, storage, or export — so an excluded request never produces a captured payload at
all, unlike a mock (which still records the mocked exchange). See
[`CaptureRule`](../sdk/api/src/main/kotlin/io/devconsole/api/CaptureRule.kt) for the exact matching
rules (up to 500 rules).

### Session integrity, bug report, and exports

`GET /api/v1/session/integrity` (auth — active mock-enabled state, feature-flag overrides, and
recent command-audit entries as one snapshot; also embedded in `GET /api/v1/overview`), `POST
/api/v1/session/integrity/reset` (auth — disables mocks, clears session-scoped mock rules, and
resets feature-flag overrides to their declared defaults; returns the same snapshot shape). Neither
route touches stored timeline history — see [STORAGE.md](STORAGE.md).

`GET /api/v1/report` (auth, the same 5-per-10-minute export limiter as `POST /api/v1/exports`
below — distinct from the 10-per-minute limiter on the HAR/Postman routes) — a downloadable
`devconsole-report.json` bundling the recent timeline, network trail, app/build metadata, SDK
health, and the session-integrity snapshot as one bug-report artifact.

`POST /api/v1/exports` (auth + CSRF/Origin, its own 5-per-10-minute limiter) — the Android session
ZIP export, browser-triggered. Form body: `scope` (`WHOLE_SESSION` default, `TIME_RANGE` with
`fromEpochMs`/`toEpochMs`, `EVENT_IDS` with repeated `eventId`, or `EVIDENCE` for the evidence bundle
— see [EVIDENCE_AND_BUG_REPORTS.md](EVIDENCE_AND_BUG_REPORTS.md#the-evidence-bundle)), `metadataOnly`
(`true` omits attachments), `maxBytes` (defaults to and capped at `DEFAULT_EXPORT_LIMIT_BYTES`),
optional `sessionId` to scope to a specific retained app run instead of the current one. Returns a
`devconsole-export.zip` attachment (timeline events, annotations, and attachments merged from the
live timeline and durable storage) or `413 EXPORT_TOO_LARGE` /
`503 EXPORT_UNAVAILABLE`. This is the same `ExportRequest`/`EventExportWriter` machinery the
in-app "Export session ZIP" action on the device's More screen drives locally — see
[DATA_INSPECTORS_AND_EXPORTS.md](DATA_INSPECTORS_AND_EXPORTS.md) for the device-side exports
(HAR, Postman, session ZIP) and how they relate to these routes.

`GET /api/v1/exports/estimate` (auth, the same `scope`/`fromEpochMs`/`toEpochMs`/`eventId`/
`metadataOnly`/`maxBytes`/`sessionId` query parameters `POST /api/v1/exports` reads as form fields,
including `scope=EVIDENCE`) — returns `{"estimatedBytes": N}` without writing a file. This route shares
the **export** rate limiter above (5 per 10 minutes) rather than the ordinary read limiter every other
`GET` gets, because sizing an estimate does the same assembly work a real export does — for
`scope=EVIDENCE` specifically, that means materializing every flagged item's metadata, not a cheap
lookup. Note `scope=EVIDENCE` always sizes the *active* session's tray on both routes; `sessionId` only
has an effect for the other scopes.

`GET /api/v1/retained-events` (auth — `?sessionId=&limit=`, defaults to the current session,
`RetainedCaptureQuery.DEFAULT_LIMIT`/`MAX_LIMIT` bound the page) reads durably-stored events for a
past app run, independent of the live in-memory timeline — the durable-storage counterpart to `GET
/api/v1/events`.

### Command audit

`GET /api/v1/plugins/audit` (auth) — despite the path, this has nothing to do with the (removed)
plugin framework: it is the command audit log, listing every mutating command any session has
issued (`timestampEpochMs`, `browserSessionId`, `commandType`, `target`, `result`, `parameters`).
The path predates the plugin framework's removal and existing browser clients already consume it
there, so it was kept rather than renamed for no functional gain — see §7.

### Session control

`POST /api/v1/session/stop` (auth) — the one route that separates the Origin check
(`ORIGIN_REJECTED`) from the CSRF-token check (`CSRF_INVALID`) instead of folding them into one
code. Returns `202 {"status":"stop-requested"}`; actual shutdown is left to the host app observing
the audit log.

## 4. WebSocket stream — `/api/v1/stream`

Max frame size 64 KiB. No Ktor-level ping/pong keep-alive is configured for this route.

1. Pre-upgrade GET passes through the host allowlist and (if a bearer token is present) the read
   rate limiter.
2. Auth: `Authorization: Bearer <token>` with a live session required. Failure closes the
   socket with WebSocket close code 1008 and reason `"AUTH_REQUIRED"` (not an HTTP JSON body).
3. **Client hello:** the server does a literal substring check — the first frame must be text
   whose body contains `"type":"client.hello"` somewhere in it (not a JSON parse). Anything else
   closes with code 1003, reason `"HELLO_REQUIRED"`. Send:
   ```json
   {"type": "client.hello"}
   ```
4. **Server welcome**, sent once the hello is accepted:
   ```json
   {"type": "server.welcome", "protocolVersion": 1, "currentSequence": 0, "heartbeatSeconds": 20}
   ```
   `currentSequence` is the highest `EventEnvelope.sequence` published so far. `heartbeatSeconds`
   is advisory only — nothing in this server actually sends periodic pings.
5. **Streaming (server → client only).** The server never reads `incoming` again after the hello.
   Each appended event arrives as:
   ```json
   {"type": "event.appended", "sequence": 42, "event": {"id": "...", "pluginId": "...", "type": "...", "summary": "...", "schemaVersion": 1}}
   ```
   This carries only 5 fields — notably **no severity, timestamp, or correlation id** — so a
   dashboard needs a follow-up REST call to get the rest of an event's data.

The stream has **no replay and no delivery guarantee**: a newly-connected client sees only events
published after it starts collecting (no backlog), and the underlying buffer drops the oldest
event on overflow (2000-event buffer) rather than blocking. Reconcile gaps via `GET
/api/v1/events` with a cursor, not by trusting the stream alone.

## 5. Event schemas

Two distinct types exist and are not directly interchangeable in the wire protocol:

- **`EventEnvelope`** (`sdk:api`) — the live, pre-persistence event (13 fields: id, sessionId,
  pluginId, type, timestampEpochMs, monotonicNanos, sequence, severity enum, summary,
  correlationId, tags, payloadRef, schemaVersion). Only 5 fields reach the WebSocket stream (§4).
- **`StoredEvent`** (`sdk:storage-api`) — the persisted/queryable event used by `GET
  /api/v1/events` (14 fields, `severity` is a plain `Int` here rather than the enum, tags/payload
  are pre-serialized JSON strings). Only 7 fields are returned by that route.

No conversion function between the two was found in the server/timeline modules — treat "the
stream and the REST timeline describe the same logical event" as a reasonable inference from
field-name overlap, not a verified mapping.

## 6. Pagination mechanics

| Endpoint | Cursor style | Notes |
|---|---|---|
| `GET /api/v1/events` | Keyset (`sequence` + id), HMAC-signed | Bound to the exact filter set it was minted under; replaying with different filters invalidates it. No drift under concurrent writes. |
| `GET /api/v1/network/transactions`, `/har` | Offset, HMAC-signed | Also bound to its filter set. Subject to classic offset-pagination drift (skip/duplicate) if data changes between pages; invalidated if the data set shrinks below the offset. |
| `GET /api/v1/websockets/*` | None | Returns the full in-memory set; bounded by fixed eviction caps, not pagination. |

An invalid/expired cursor on either paginated family returns the generic `{"code":"VALIDATION_FAILED"}`
400 — there is no dedicated invalid-cursor error code.

## 7. Known dead paths / quirks worth remembering

- `.../websockets/connections/{id}/send` has a rate limiter wired but no matching route — reserved
  capacity, not a bug to chase. (`POST /api/v1/exports` looks like the same situation at a glance
  but is not: it has a real route, documented in §3, on its own 5-per-10-minute limiter.)
- `GET /api/v1/plugins/audit` is the command audit log, not a leftover plugin-framework route — the
  name is historical (see §3, Command audit).
- Bookmark/note routes report `CSRF_INVALID` instead of `AUTH_REQUIRED` for a fully unauthenticated
  request against a real event id (see §3, Timeline).
- `POST /api/v1/session/stop` is the only route with separate `ORIGIN_REJECTED`/`CSRF_INVALID`
  outcomes; every other mutating route folds both checks into one `CSRF_INVALID`.
