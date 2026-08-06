# Network stack adapters

DevConsole's capture core is transport-agnostic. Two adapters ship with the SDK; anything else takes
about twenty lines against the same public recorder.

## What is covered out of the box

| Your stack | What to do |
|---|---|
| **OkHttp** | Add `DevConsoleOkHttpInterceptor` (below). |
| **Retrofit** | **Nothing.** Retrofit does no networking of its own — it delegates to an `OkHttpClient`. Add the interceptor to the client Retrofit is built with and every call is captured. |
| **Ktor client** | Install `DevConsoleKtorClientPlugin` (below). Engine-independent: CIO, OkHttp, Android, and Java all work. |
| **Apollo / GraphQL** | Runs on OkHttp, so the interceptor captures it. Bodies are shown as-is; there is no GraphQL-aware formatting yet. |
| **Cronet, HttpURLConnection, Volley, anything else** | Write a small adapter against `NetworkTransactionRecorder` — see [Custom stacks](#custom-stacks). |
| **gRPC** | Not supported. `grpc-okhttp` uses OkHttp's transport primitives directly and never passes through the `Interceptor` chain, so it needs a gRPC `ClientInterceptor`. Not yet written. |

Three things worth knowing:

- **`sdk:server-ktor` is not Ktor client support.** It is the embedded dashboard server. Ktor client
  capture lives in `sdk:network-ktor`.
- **Mocking and WebSocket capture are OkHttp-only.** `DevConsoleMockInterceptor` and
  `DevConsoleOkHttpWebSocketListener` have no equivalent for other stacks yet, so a Ktor-CIO app gets
  network capture but not mocks.
- **The Ktor adapter never captures response bodies.** At the pipeline stage `DevConsoleKtorClientPlugin`
  hooks (`onResponse`, before `HttpResponsePipeline.Transform`) Ktor exposes no non-consuming
  ("peek") read of the response body the way OkHttp's `peekBody(...)` does — reading it there would
  steal bytes the host's own `response.body<T>()`/`bodyAsText()` call still needs. Response
  transactions from the Ktor adapter are always metadata-only (status, headers, content type,
  declared length); request bodies are still captured, subject to the same textual/bounded rules as
  below.

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

## Ktor client (`sdk:network-ktor`)

```kotlin
val client = HttpClient {
    install(DevConsoleKtorClientPlugin) {
        recorder = DevConsole.networkRecorder()
    }
}
```

Captures full request/response metadata (method, URL, headers, status, content type, declared
length) and — for a textual, known-length body no larger than 256 KiB, from a Ktor `OutgoingContent`
that isn't a single-use stream — the request body too. **Response bodies are never captured** for
this adapter: Ktor's client pipeline offers no non-consuming read of the response body at the stage
this plugin observes it, unlike OkHttp's `peekBody(...)`, so every recorded transaction's response is
metadata-only regardless of content type or size. This is the main capability gap versus the OkHttp
adapter — see [Two things worth knowing](#what-is-covered-out-of-the-box) above.

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
