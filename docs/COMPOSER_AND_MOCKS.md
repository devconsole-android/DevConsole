# Composer and mocks

## Composer

The request composer runs entirely inside the SDK's own isolated HTTP client
(`UrlConnectionComposerTransport`) — it never reuses your application's authenticated OkHttp
client, so composer traffic can't accidentally carry your app's auth tokens or interceptors. There
is nothing to wire from host code: connect to the dashboard (open mode is the default; any
authenticated session can open the Composer page in `SESSION_CODE` mode — see
[PROTOCOL_REFERENCE.md](PROTOCOL_REFERENCE.md#2-auth-handshake-session_code)), and either build a
request by hand or use "Copy redacted cURL to composer" from a captured Network
transaction (see [NETWORK_INSPECTOR.md](NETWORK_INSPECTOR.md)). The browser route
(`POST /api/v1/composer/execute`) is off by default — the host must set
`DevConsoleConfig.composerEnabled = true`, optionally with a `composerAllowedHosts` allowlist —
and is independent of the in-app inspector's own composer UI, which is instead gated by the host's
`EditingCapabilities.requestExecution` flag. See [THREAT_MODEL.md](THREAT_MODEL.md).

### Calling an API with an access token (and why there is no CORS)

The browser never calls your API. The dashboard posts the request to the DevConsole server on the
device, and the device (or a host computer, below) makes the real call, so the same-origin policy
that produces CORS errors in a browser is not involved. Put the token in a header and keep its value
in a secret variable so it is hidden in captures and never saved:

```
Headers:           Authorization: Bearer {{token}}
Secret variables:  token=eyJhbGciOi...
```

If a request is refused, the result panel says why:

| Code | Meaning | Fix |
|------|---------|-----|
| `COMPOSER_DISABLED` (404) | Composer is off in the app | `composerEnabled(true)` |
| `COMPOSER_HOST_REJECTED` (403) | Host not in `composerAllowedHosts`, **or** the phone refuses it because it resolves to a private/VPN/localhost address | Add the host to the allowlist; for private addresses use "Run on" a computer |
| `AGENT_UNAVAILABLE` (409) | The chosen computer disconnected | Restart `devconsole-agent`, press *Refresh Run On* |
| `AGENT_TIMEOUT` (504) | The computer accepted the request but never answered | Check the agent terminal |
| `EXECUTION_FAILED` (502) | The target could not be reached; `detail` has the reason | Check URL, DNS, TLS |

The result panel shows the status, timing, where it ran, the response headers and the response body
(secret values and redaction-policy fields are masked, exactly as in the Network page).

### Adding a host without rebuilding

`composerAllowedHosts` is fixed when the app is built. To try another API without a rebuild, opt in
with `composerHostsEditable(true)`. The Composer's **Host allowlist** card then has an *Allow host*
box (a pasted URL is reduced to its hostname), and a request rejected with `COMPOSER_HOST_REJECTED`
shows an **Allow \<host\>** button. Hosts added this way last until DevConsole stops, can be removed
from the same card, and are audited as `composer.host.add` / `composer.host.remove`. Hosts set by the
app cannot be removed from the dashboard, wildcards are not accepted, and at most 50 can be added.

The in-app Composer screen consults the same live list, so a host allowed from the dashboard also works
there while the server runs; with the server stopped it falls back to `composerAllowedHosts`. In the
samples, `compose-app` and `views-java-app` turn this on and `foundation-app` keeps its locked-down
defaults, where the card reads "The app fixes this list".

Leave it off for anything reachable by other people: every added host widens where the device will send
requests, and in the default open mode that is anyone who can open the dashboard.

### Run on a host computer (macOS, Linux, Windows)

Some APIs are reachable only from your computer: a backend on `localhost`, an internal host behind a
VPN, a private IP. The phone refuses private-network targets and cannot see your VPN, so run the
request on the computer instead. `devconsole-agent` is a small Java program (JDK 17+) that connects
**to** the phone, so nothing on the computer listens for connections.

```bash
./gradlew :tools:composer-agent:installDist
# macOS / Linux
tools/composer-agent/build/install/devconsole-agent/bin/devconsole-agent \
    --device http://localhost:8080 --code 123456
# Windows
tools\composer-agent\build\install\devconsole-agent\bin\devconsole-agent.bat --device http://localhost:8080 --code 123456
```

`--device` is whatever address you open the dashboard at: `http://localhost:<port>` after
`adb forward tcp:<port> tcp:<port>` (use `BindingMode.LOOPBACK`), or the phone's LAN address
(`BindingMode.LAN`/`AUTO`). `--code` is the one-time session code from the dashboard's Overview page;
it is not needed in the default open mode. Other options: `--name` (label in the menu),
`--allow-host` (only run requests to these hosts) and `--block-private` (refuse localhost/private
addresses).

The agent then appears under **Composer → Run on**. Everything else is unchanged: the host
allowlist, rate limit, audit log, redaction and the Network/Timeline entry (tagged
`executor=agent:<name>`) all stay on the device. The agent only performs the final HTTP call, and
the phone re-issues every redirect hop so credentials never follow a redirect to another origin.
The device allowlist (`composerAllowedHosts`) still applies to computer-run requests; the agent is a
second, local lock, not a bypass.

Composer executions appear as first-class network events tagged `source=composer`, so they show up
in the Timeline and Network pages like any other captured request. Imported cURL and saved
collections never persist secret variable values by default.

## Mocks

Mocks need no host wiring beyond the network installer you already have:

```kotlin
val client = OkHttpClient.Builder()
    .installDevConsole(DevConsole.networkRecorder())   // capture + timing + mocks
    .build()
```

`installDevConsole` (see [NETWORK_ADAPTERS.md](NETWORK_ADAPTERS.md)) adds the capture interceptor,
the event listener needed for the Network inspector's timing bar (which a bare
`DevConsoleOkHttpInterceptor(recorder)` does not), and — from the engine `DevConsole.initialize`
published to `MockEngineRegistry` — the mock interceptor, in that order. Order matters: the capture
interceptor goes first, so a mocked response is still captured and tagged like a normal response
(matching the interception order in the spec).

Two escape hatches. Pass `mockEngine = null` to install no mock interceptor at all, or pass an
engine of your own to mock against something other than the live one:

```kotlin
.installDevConsole(DevConsole.networkRecorder(), mockEngine = null)
```

If you are upgrading and already had `.addInterceptor(DevConsoleMockInterceptor(DevConsole.mockEngine()))`,
you can delete it. Leaving it in place is safe — the second interceptor sees the first one's tag and
stands down, so a `Delay` rule is never applied twice — but it no longer does anything.

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

The same dashboard button turns mocking back on (it reads its label from the engine's live state),
as does the Android inspector's Control screen. The two directions gate differently on purpose:
turning mocking *off* needs only an authenticated session, since falling back to real traffic is
always a safe thing to allow, while turning it back *on* also needs the host's `mocks` editing
capability, because it changes how the app behaves. `mocks` is the one editing capability that
defaults to *on* — a mock rule writes nothing of the host's, it only short-circuits DevConsole's own
interceptor — so this asymmetry bites only a host that opted back out. One that publishes rules
read-only (`EditingCapabilities(mocks = false)`, or `EditingCapabilities.readOnly()`) can therefore
disable mocking from the browser but not re-enable it — the engine is still switchable in-process
through `DevConsole.mockEngine()`.
Individual rules toggle independently of this switch; with the engine off, no rule applies
regardless of its own state.

## No production capture

`sdk:noop`'s `MockEngine` is constructed with `enabled = false`: `decide()` always returns
`Passthrough` before touching any rule, so `DevConsoleMockInterceptor` is safe to leave wired in a
release build's source code — no `if (BuildConfig.DEBUG)` branch needed.
