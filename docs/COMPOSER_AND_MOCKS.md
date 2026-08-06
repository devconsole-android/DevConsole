# Composer and mocks

## Composer

The request composer runs entirely inside the SDK's own isolated HTTP client
(`UrlConnectionComposerTransport`) — it never reuses your application's authenticated OkHttp
client, so composer traffic can't accidentally carry your app's auth tokens or interceptors. There
is nothing to wire from host code: connect with a session code (any authenticated session can open
the Composer page — see [PROTOCOL_REFERENCE.md](PROTOCOL_REFERENCE.md#2-auth-handshake-session_code)),
and either build a request by hand or use "Copy redacted cURL to composer" from a captured Network
transaction (see [NETWORK_INSPECTOR.md](NETWORK_INSPECTOR.md)). The browser route
(`POST /api/v1/composer/execute`) is off by default — the host must set
`DevConsoleConfig.composerEnabled = true`, optionally with a `composerAllowedHosts` allowlist —
and is independent of the in-app inspector's own composer UI, which is instead gated by the host's
`EditingCapabilities.requestExecution` flag. See [THREAT_MODEL.md](THREAT_MODEL.md).

Composer executions appear as first-class network events tagged `source=composer`, so they show up
in the Timeline and Network pages like any other captured request. Imported cURL and saved
collections never persist secret variable values by default.

## Mocks

Mocks need one line of host wiring — an OkHttp interceptor that consults the same `MockEngine`
instance the dashboard controls:

```kotlin
val client = OkHttpClient.Builder()
    .installDevConsole(DevConsole.networkRecorder())                          // capture + timing first
    .addInterceptor(DevConsoleMockInterceptor(DevConsole.mockEngine()))        // then mock
    .build()
```

`installDevConsole` (see [NETWORK_ADAPTERS.md](NETWORK_ADAPTERS.md)) is the recommended way to add
the capture interceptor — it also wires the event listener needed for the Network inspector's
timing bar, which a bare `DevConsoleOkHttpInterceptor(recorder)` does not.

Order matters: put the capture interceptor before the mock interceptor, so a mocked response is
still captured and tagged like a normal response (matching the interception order in the spec).

Rules are created from the dashboard, not from host code — a rule matches on method/scheme/host/
path (regex) plus optional query/header/body predicates, and resolves to an action: a static
response, a templated response (`{{query.id}}`, `{{header.x-request-id}}`, `{{body}}`, ...), a
delay, a connection failure, a timeout, a status override, a body replacement, or passthrough.
Matching is deterministic: highest priority wins, ties broken by most-specific match, then by
creation order — the matched rule ID is always recorded in the response.

**Conflict detector** — the Mocks page lists rule pairs whose method/scheme/host/path could match
the same request (`GET /api/v1/mocks/conflicts`, backed by `MockEngine.conflicts()`). This is a
conservative heuristic — it flags identical paths or an unconstrained wildcard against a specific
one, not a general regex-overlap solver, so it can miss genuinely-overlapping regexes that aren't
textually identical.

**Kill switch** — "Disable all mocks" flips `MockEngine.isEnabled()` to `false` atomically; every
in-flight and future `decide()` call returns `Passthrough` until re-enabled. A rule evaluation error
(a bad template, a malformed regex) fails open to passthrough and records an error event — it never
crashes the host request.

## No production capture

`sdk:noop`'s `MockEngine` is constructed with `enabled = false`: `decide()` always returns
`Passthrough` before touching any rule, so `DevConsoleMockInterceptor` is safe to leave wired in a
release build's source code — no `if (BuildConfig.DEBUG)` branch needed.
