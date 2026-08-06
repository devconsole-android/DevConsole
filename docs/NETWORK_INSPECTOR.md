# Network inspector

## Wiring OkHttp capture

```kotlin
val client = OkHttpClient.Builder()
    .installDevConsole(DevConsole.networkRecorder())
    .build()
```

`installDevConsole` is an extension on `OkHttpClient.Builder` (Java: `DevConsoleOkHttp.install(builder,
recorder)`) and is the recommended way to wire OkHttp: it adds `DevConsoleOkHttpInterceptor` as an
**application** interceptor — not a network interceptor — and also sets `eventListenerFactory` so
the timing bar below is populated. Application-interceptor position matters regardless of timing:
redirects and retries produce multiple network exchanges for one logical request, and that position
is what lets DevConsole model them as a single transaction. `DevConsoleOkHttpInterceptor` only peeks
a bounded 512 KiB copy of the response body via `peekBody(...)` (the host's own stream is never
consumed), and captures a bounded copy of the request body under the conditions below.

### Request body capture

The interceptor buffers a request body into a fresh `Buffer` — the original `RequestBody` is left
untouched, so OkHttp's real network write still sees the same bytes — only when **all** of the
following hold:

- Not one-shot (`RequestBody.isOneShot() == false`) — a one-shot body can only be read once, and that
  read must be reserved for the real network write.
- Not duplex (`RequestBody.isDuplex() == false`) — a duplex body streams bidirectionally and has no
  safe point to buffer from.
- The declared length is known (`contentLength() >= 0`) and no larger than 256 KiB.
- The content type is textual, or absent (an absent type is still read so the capture pipeline's own
  UTF-8 sniff can decide; a *known* non-textual type is skipped).

Any request that fails one of these checks is still recorded with its metadata (method, URL,
headers, declared length) — only the body itself is omitted, and
`NetworkRequestMetadata.bodyOmittedReason` records why: `"one-shot"`, `"duplex"`, `"unknown-length"`,
`"too-large"`, or `"binary"`.

Captured request and response bodies are redacted the same way as headers before they ever reach the
store or the live dashboard stream — see [Redaction](#redaction) below.

### Manual wiring

`installDevConsole` is two coordinated pieces of state under one call. Wiring them separately —
useful if you want to manage their lifecycles independently — means doing all three of the
following, since `DevConsoleOkHttpInterceptor` reads timing data back out of the *same*
`DevConsoleOkHttpEventListenerFactory` instance installed as the listener:

```kotlin
val listenerFactory = DevConsoleOkHttpEventListenerFactory()
val client = OkHttpClient.Builder()
    .eventListenerFactory(listenerFactory)
    .addInterceptor(DevConsoleOkHttpInterceptor(DevConsole.networkRecorder(), listenerFactory))
    .build()
```

Skip the `eventListenerFactory(...)` call, pass a different factory instance to the interceptor, or
leave the interceptor's second argument at its `null` default, and DNS/connect/TLS/send/wait/receive
all stay `null` forever — no error, just an empty timing bar. Prefer `installDevConsole` unless you
have a specific reason not to.

If you use a different HTTP stack, `NetworkTransactionRecorder.record(request, response, started,
completed)` is the generic manual-recording entry point — build `NetworkRequestInput`/
`NetworkResponseInput` yourself and call it directly.

### Timing phases

The Network page's detail pane renders a DNS/TCP/TLS/TTFB(wait)/download breakdown per transaction,
sourced from OkHttp's `EventListener` callbacks. Any individual phase can legitimately be `null`
even with everything wired correctly:

- **DNS / TCP (connect)** — `null` on a pooled/reused connection, which performs neither.
- **TLS** — `null` on a plaintext (`http://`) request, which never performs a handshake.
- **All phases** — `null` on a response served entirely from OkHttp's cache, which never touches the
  network.

Treat those as accurate reporting of what happened, not as a sign the adapter is misconfigured.

## What gets captured

Method, URL (scheme/host/path/query), headers, content type, a body preview, and timing. Query
parameters and header values are redacted the same way regardless of transport. Default limits:
256 KiB request body preview, 512 KiB response body preview, 100 headers max, 16 KiB per header
value — exceeding a limit truncates the capture, it never blocks or fails the host request.

## Redaction

Applied before the event ever reaches the store or the live dashboard stream — see
[SECURITY_AND_REDACTION.md](SECURITY_AND_REDACTION.md) for the exact default field-name list
(`authorization`, `cookie`, `access_token`, ...). A captured `Authorization` header shows up in the
dashboard as `<redacted>`, never as the raw value.

## Dashboard

The Network page lists transactions (start time, method, host, path, status, duration) with a
detail pane showing redacted headers and body. From the detail pane you can:

- **Copy redacted cURL to composer** — `GET /api/v1/network/transactions/{id}/curl`, pre-fills the
  Composer page with a runnable, already-redacted command.
- **Download HAR** — `GET /api/v1/network/har?limit=...`, a real HAR 1.2 document
  (`{"log": {"version": "1.2", ..., "entries": [...]}}`) built by `NetworkExport.toHar`, redacted
  the same way as live capture. Requires an authenticated session, like every read surface.
- **Download Postman collection** — `GET /api/v1/network/postman?limit=...`, a Postman Collection
  v2.1 document with headers and text bodies included (not just metadata), deduplicated by
  method/URL/headers/body **and** response outcome — see
  [PROTOCOL_REFERENCE.md](PROTOCOL_REFERENCE.md#3-rest-routes) for the exact dedup rule. The same
  export is available as an in-app action on the device — see
  [DATA_INSPECTORS_AND_EXPORTS.md](DATA_INSPECTORS_AND_EXPORTS.md).

The Overview page's network-status-distribution widget (`GET /api/v1/overview`) buckets recent
transactions by `2xx`/`3xx`/`4xx`/`5xx`/`pending` via `NetworkTransactionStore.statusDistribution()`.

## Capture exclusion rules

Independent of mocks, a **capture rule** (host, optional method, optional path prefix) excludes
matching requests from capture entirely — the request still goes out and the response still comes
back, but no transaction is recorded, redacted, or exported for it. This is for noisy or
irrelevant traffic (analytics beacons, polling health checks) you never want cluttering the
Network page, as opposed to a mock, which still records the (mocked) exchange. Rules are
authored from the dashboard's Network page and gated by the host's `EditingCapabilities.captureRules`
flag — `GET /api/v1/capture-rules` always returns the current rule set with an `editable` flag;
without the capability, mutating routes reject with `403`. See
[PROTOCOL_REFERENCE.md](PROTOCOL_REFERENCE.md#3-rest-routes) for the wire format and
[`CaptureRule`](../sdk/api/src/main/kotlin/io/devconsole/api/CaptureRule.kt) for the exact matching
semantics (up to 500 rules, evaluated on the capture hot path against a lock-free snapshot).

## Correlation

Set `NetworkRequestInput.correlationId` if you want a request to link to other timeline events
(pushes, state changes) that happened around the same time — the SDK never mutates outgoing
headers to inject this itself unless you configure it to.
