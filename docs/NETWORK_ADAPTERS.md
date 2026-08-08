# Network stack adapters

DevConsole's capture core is transport-agnostic. Two adapters ship with the SDK; anything else takes
about twenty lines against the same public recorder.

## What is covered out of the box

| Your stack | What to do |
|---|---|
| **OkHttp** | Add `DevConsoleOkHttpInterceptor` (below). |
| **Retrofit** | **Nothing.** Retrofit does no networking of its own — it delegates to an `OkHttpClient`. Add the interceptor to the client Retrofit is built with and every call is captured. |
| **Ktor client** | Install `DevConsoleKtorClientPlugin` (below). Engine-independent: CIO, OkHttp, Android, and Java all work, including full response-body capture. The OkHttp engine still has an edge for timing phases and mocks — see [Ktor on the OkHttp engine](#ktor-on-the-okhttp-engine). |
| **Apollo / GraphQL** | Runs on OkHttp, so the interceptor captures it. Bodies are shown as-is; there is no GraphQL-aware formatting yet. |
| **Cronet, HttpURLConnection, Volley, anything else** | Write a small adapter against `NetworkTransactionRecorder` — see [Custom stacks](#custom-stacks). |
| **gRPC** | Not supported. `grpc-okhttp` uses OkHttp's transport primitives directly and never passes through the `Interceptor` chain, so it needs a gRPC `ClientInterceptor`. Not yet written. |

Three things worth knowing:

- **`sdk:server-ktor` is not Ktor client support.** It is the embedded dashboard server. Ktor client
  capture lives in `sdk:network-ktor`.
- **Mocking, WebSocket capture, and OkHttp's DNS/TCP/TLS/send/wait/receive timing breakdown are
  OkHttp-only.** `DevConsoleMockInterceptor`, `DevConsoleOkHttpWebSocketListener`, and per-phase
  timing have no equivalent for other stacks yet, so a Ktor-CIO app gets full request/response
  capture but not mocks, WebSocket capture, or a timing breakdown finer than total call duration.
- **Both adapters capture response bodies now, each with its own bound.** OkHttp captures a
  declared-length body eagerly and an unknown-length (chunked, transparently-gzipped) one through a
  non-blocking tee, capped at 512 KiB — see
  [Chunked and streaming responses](#chunked-and-streaming-responses). The Ktor adapter captures a
  textual body up to 256 KiB on every engine — see [Ktor client](#ktor-client-sdknetwork-ktor)
  below. Both leave `text/event-stream` and known-binary bodies metadata-only.

## OkHttp (`sdk:network-okhttp`)

```kotlin
val client = OkHttpClient.Builder()
    .installDevConsole(DevConsole.networkRecorder())
    .build()
```

`installDevConsole` (Java: `DevConsoleOkHttp.install(builder, recorder)`) is the recommended entry
point — it sets `eventListenerFactory` and adds `DevConsoleOkHttpInterceptor` together, using the
same instance for both. That coupling is required: `DevConsoleOkHttpInterceptor` supplies the
semantic request/response data, while the DNS/TCP/TLS/send/wait/receive **timing** breakdown comes
from the event listener, and the interceptor only reads timings back out of the *exact* listener
instance installed on the client. Wiring them by hand instead — constructing
`DevConsoleOkHttpEventListenerFactory` and `DevConsoleOkHttpInterceptor` separately — is still
supported (see [NETWORK_INSPECTOR.md](NETWORK_INSPECTOR.md)) but easy to get subtly wrong: pass the
interceptor a `null` timings provider, or use two different factory instances, and every timing
phase silently reads `null` forever with no error.

If you already have your own `EventListener.Factory` (an APM/tracing tool, say), pass it as
`installDevConsole`'s second argument so DevConsole chains to it as a delegate instead of replacing
it.

Order matters if you also use mocks. Install DevConsole **first** so it wraps the mock interceptor
and records mocked responses too:

```kotlin
val client = OkHttpClient.Builder()
    .installDevConsole(DevConsole.networkRecorder())               // capture + timing first
    .addInterceptor(DevConsoleMockInterceptor(DevConsole.mockEngine())) // then mock
    .build()
```

This is an *application* interceptor, so it sees the call as your code issued it — one entry per
call, before redirects and retries. Use `addNetworkInterceptor` instead if you need per-hop detail.

Individual timing phases are legitimately `null` even when everything is wired correctly: a pooled
connection performs no DNS lookup or TCP handshake, a plaintext request has no TLS phase, and a
cached response that never touches the network has no phases at all. That reflects what actually
happened on the wire, not a wiring mistake.

**⚠️ Do not reuse an instrumented OkHttpClient for WebSocket upgrades:** If you wire this client for
network capture and then call `client.newWebSocket(...)` on the same instance, the upgrade handshake
will be double-captured — once in the Network tab as an HTTP request, and again in the WebSocket tab
as a connection. Create a separate plain `OkHttpClient` for WebSocket traffic instead.

### Chunked and streaming responses

A response with a declared `Content-Length` is captured eagerly, the same as always. A response
**without** one — `Transfer-Encoding: chunked`, a transparently gzip-recompressed body, most
long-poll and NDJSON feeds — is no longer skipped. Instead of the eager `peekBody(...)` used for
known-length responses, the interceptor wraps the body in a non-blocking **tee**: every byte the
host reads is copied into a bounded capture buffer as a side effect of that same read. The host's
own `Response.body` stream is completely unaffected — same bytes, same timing, no buffering
inserted on the calling thread.

The transaction is recorded once the body reaches EOF or is closed, whichever happens first,
against a 512 KiB cap:

- Fully delivered under the cap → recorded complete, `bodyOmittedReason = null`.
- Still flowing past the cap → the first 512 KiB is recorded, `bodyOmittedReason = "truncated"`,
  and `bodyLength` carries the true total the host received, not just the captured prefix.
- Closed by the host before EOF (an early `response.close()`, a cancelled call) → whatever was
  delivered by then is recorded, `bodyOmittedReason = "partial"`.

`text/event-stream` responses are the one exception: they're still recorded metadata-only
(`bodyOmittedReason = "streaming"`) immediately, without teeing — a real SSE feed is for all
practical purposes endless, and pinning a growing capture buffer against it would be pointless. A
response with a known non-textual content type is also left metadata-only, same as on the
known-length path.

A body the host neither reads nor closes still doesn't vanish: 500ms after the interceptor returns
the response, a watchdog checks whether the body is still open and, if so, records a provisional
`bodyOmittedReason = "streaming"` entry (`completedAtEpochMs = null`). An ordinary finite response
is read well inside that window and is unaffected — the provisional record never fires. A body
that's still open when the watchdog does fire — a genuinely long-lived stream, or one the host
abandoned outright — gets that provisional entry replaced in place if and when the body eventually
completes, or left standing forever if it never does. Either way, every response the interceptor
hands back is recorded, not only the ones whose body the host happens to consume.

## Ktor client (`sdk:network-ktor`)

```kotlin
val client = HttpClient {
    install(DevConsoleKtorClientPlugin) {
        recorder = DevConsole.networkRecorder()
    }
}
```

Captures full request/response metadata (method, URL, headers, status, content type, declared
length), the request body (textual, known-length, no larger than 256 KiB, from a Ktor
`OutgoingContent` that isn't a single-use stream), and the response body too — textual, up to
256 KiB, on **every** engine (CIO, OkHttp, Android, Java, ...).

Response capture happens at `client.receivePipeline.intercept(HttpReceivePipeline.After)`, the same
stage Ktor's own `ResponseObserver`/`Logging` plugins use: the raw response channel is duplicated
with `ByteReadChannel.split`, one half handed back to the host completely untouched — a saved
response's double `body<T>()`/`bodyAsText()` read, and a `DoubleReceiveException` on a non-saved
one, both behave exactly as they would without this plugin — while the other half is drained
asynchronously into the capture buffer. Recording happens once the capture half closes (body
captured whole) or hits the 256 KiB cap (`bodyOmittedReason = "too-large"`), whichever comes first;
a call torn down mid-body still leaves a metadata-only record rather than losing the transaction.

A response is left metadata-only without its channel ever being touched when its declared content
type is known non-textual (`bodyOmittedReason = "binary"`), its declared `Content-Length` already
exceeds the cap (`"too-large"`), or it's `text/event-stream` or a `101 Switching Protocols` upgrade
(`"streaming"` — splitting a live SSE/WebSocket channel would eat frames the host still needs). A
response with no declared content type is still captured, so the body-preview UTF-8 sniff can
decide from the actual bytes.

What the Ktor adapter still can't provide — on any engine — is OkHttp's DNS/TCP/TLS/send/wait/receive
timing breakdown and mock rules. See [Ktor on the OkHttp engine](#ktor-on-the-okhttp-engine) below.

### Ktor on the OkHttp engine

`DevConsoleKtorClientPlugin` already captures full request and response bodies on every engine, so
you no longer need the engine-level interceptor just to get bodies. What it still can't provide is
the **DNS/TCP/TLS/send/wait/receive timing breakdown** and **mock rules** — both come from OkHttp's
`EventListener`/interceptor chain, which the Ktor client pipeline has no equivalent for. If your
`HttpClient` uses the **OkHttp engine** and you need either of those, instrument the engine's
`OkHttpClient` directly instead of installing the plugin:

```kotlin
val client = HttpClient(OkHttp) {
    engine {
        config {
            installDevConsole(DevConsole.networkRecorder())
            addInterceptor(DevConsoleMockInterceptor(DevConsole.mockEngine())) // optional
        }
    }
}
```

The `devconsole-network-ktor` dependency is not needed in this mode — the OkHttp adapter ships
inside `devconsole` itself.

Two caveats:

- **Install one integration, not both.** The engine-level interceptor and the Ktor plugin each
  record independently; installing both records every call twice.
- Bodies captured this way follow the OkHttp adapter's own bounds — the
  [tee described above](#chunked-and-streaming-responses) and its 512 KiB cap, not the Ktor
  plugin's 256 KiB one.

Reach for this only when you need timing phases or mocks. For capture alone, the plugin now covers
CIO, OkHttp, Android, and Java equally well.

## Custom stacks

`DevConsole.networkRecorder()` returns a `NetworkTransactionRecorder`, which knows nothing about any
particular HTTP client. Call `record(...)` once a request completes:

```kotlin
val recorder = DevConsole.networkRecorder()
val startedAt = System.currentTimeMillis()

// ... perform the request with whatever client you use ...

recorder.record(
    request = NetworkRequestInput(
        method = "POST",
        url = "https://api.example.com/checkout?access_token=secret",
        headers = mapOf("Authorization" to "Bearer token", "Content-Type" to "application/json"),
        body = requestBody.toByteArray(),
        contentType = "application/json",
    ),
    response = NetworkResponseInput(
        statusCode = 200,
        headers = mapOf("Content-Type" to "application/json"),
        body = responseBody.toByteArray(),
        contentType = "application/json",
    ),
    startedAtEpochMs = startedAt,
    completedAtEpochMs = System.currentTimeMillis(),
)
```

`body` is a `ByteArray?`. Pass `null` when a body is unavailable or streaming — the entry is still
recorded with its metadata.

To report a failure, pass a response carrying the error instead:

```kotlin
recorder.record(
    request = requestInput,
    response = NetworkResponseInput(statusCode = 0, error = error.message),
    startedAtEpochMs = startedAt,
    completedAtEpochMs = System.currentTimeMillis(),
)
```

### Guarantees

`record` never throws and never blocks the calling thread. Work is handed to a bounded queue and
processed on a single background worker; if the queue fills, the oldest entries are dropped and
counted in `droppedCount()` rather than slowing your app down. Capture failures are swallowed so
they cannot affect host behaviour.

Redaction happens on that background thread, applying the active `RedactionPolicy` to headers, query
parameters, and JSON fields. See [Security and redaction](SECURITY_AND_REDACTION.md).

## WebSockets

`DevConsole.socketRecorder()` returns a `SocketRecorder` with the same shape, for stacks other than
OkHttp.
