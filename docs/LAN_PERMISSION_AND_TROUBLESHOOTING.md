# LAN permission and troubleshooting

## LAN binding

```kotlin
DevConsole.startBrowser(StartRequest(bindingMode = BindingMode.LAN, portRange = 8080..8099))
```

`KtorLocalServerEngine` enumerates the device's network interfaces and binds to the first active,
non-loopback IPv4 address it finds on an interface that is up and not virtual or point-to-point
(this excludes loopback, VPN/tun interfaces, and cellular PPP-style links; it does not try to
distinguish Wi-Fi from Ethernet beyond that). **It never binds `0.0.0.0`** — the server is
reachable only via that one specific address, e.g. `192.168.0.15`, not from every route the device
happens to have. Any other device on the same network can then reach the dashboard at
`http://192.168.0.15:<port>/`, and the connect URL returned in `StartResult.Started.access`
already embeds the correct host and port for that address — share it (or its QR code) with anyone
you want to connect.

If the device has no eligible interface (e.g. airplane mode, or only a VPN/cellular link is up),
`startBrowser` returns `StartResult.NoEligibleNetwork(details)` instead of binding anything.

**Why not switch HTTP libraries (e.g. NanoHTTPD) for this?** The embedded server already runs on
Ktor's CIO engine, which binds to whatever host you hand it — loopback and a real network
interface are both just a `host` parameter. Reachability was never a library limitation; the gap
was that `KtorLocalServerEngine` had a hardcoded stub that refused to even attempt a LAN bind
(returning `LocalNetworkPermissionRequired` unconditionally). Swapping the whole HTTP/WebSocket
stack — session auth, CSRF, rate limiting, the dashboard — for a different library
would be a large rewrite to solve a problem that was actually one `if` statement.

### Android's local-network runtime permission

On a device where `Build.VERSION.SDK_INT >= 37` **and** the host app's `targetSdkVersion >= 37`,
starting in LAN mode requires the `android.permission.ACCESS_LOCAL_NETWORK` runtime permission.
`PlatformFacadeProvider.startBrowser()` checks this via `LocalNetworkPermissionGate` *before* attempting
any bind; if the permission isn't granted, `startBrowser` returns `StartResult.PermissionRequired(...)`
without touching the network. On any device/app combination below that floor (which is every
device and app in normal use today), LAN mode works with no extra permission at all.

**This SDK does not request the permission for you.** If you target API 37+ and want LAN mode,
your app needs its own runtime-permission request flow (e.g. `ActivityResultContracts.RequestPermission`)
before calling `startBrowser` with `BindingMode.LAN` — check `StartResult.PermissionRequired.permission`
and prompt the user if you get it back.

## Loopback / ADB reverse mode

Still the safer default when you don't need cross-device access:

```kotlin
DevConsole.startBrowser(StartRequest(bindingMode = BindingMode.LOOPBACK, portRange = 8080..8099))
```

The server binds `127.0.0.1` on the first free port in `8080..8099`. On a physical device or an
emulator that isn't already loopback-reachable from your machine, run:

```bash
adb reverse tcp:8080 tcp:8080
```

then open the connect URL from `StartResult.Started.access.connectUrl` in a browser on the host
machine (adjust the port number in the command to whatever port the SDK actually bound, shown in
that same result).

## Troubleshooting

**`StartResult.PortUnavailable`** — every port in the configured range is already bound. Either
free one of the ports or pass a wider/different `portRange`.

**`StartResult.NoEligibleNetwork`** — LAN mode was requested but no active, non-loopback interface
was found. Confirm Wi-Fi (or another LAN connection) is actually connected, not just associated;
airplane mode, a VPN-only connection, or a cellular-only connection will all trigger this.

**Connect URL doesn't load** — for LAN mode, confirm the other device is actually on the same
subnet/network (a guest Wi-Fi network with client isolation enabled will block this even though
both devices show "connected"). For loopback mode, confirm `adb reverse` targets the same port the
SDK actually bound (`StartResult.Started.endpoint.port`), not a hardcoded `8080` — the SDK picks
the first free port in the range, which may not be the first one.

**Dashboard opens but every tab stays empty / status bar says "OFFLINE / PAUSED" forever** —
you opened the bare `http://<host>:<port>/` instead of the actual connect URL. The dashboard's
`Authorization` token only exists after the session-code exchange, and that exchange only runs if
the URL's fragment contains `#code=<session code>` — the plain host:port URL has no fragment, so
the page just sits there unauthenticated and every API call silently 401s. Always open (or scan
the QR code for) `StartResult.Started.access.connectUrl` specifically, not
`StartResult.Started.endpoint.host`/`.port` alone — the two are easy to conflate since the
endpoint is also directly answerable from the same `StartResult`, but only the full connect URL
actually authenticates the session.

**`StartResult.DisabledForBuild`** — you're running against `devconsole-noop` (production/protected
variant). See [BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md](BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md).

**Session expired / browser suddenly logged out** — sessions expire after inactivity and are
revoked immediately on server stop; issue a fresh session code from the device and reconnect.

## Session codes ("SESSION_CODE_EXPIRED" / "SESSION_CODE_INVALID")

There is no device-approval step: presenting a live session code to the exchange endpoint creates
the session immediately. The code is single-use, one is live at a time, and it expires after five
minutes with **no automatic regeneration** — if the browser reports an expired or invalid code,
issue a fresh one from the device (More screen) and reconnect with the new URL/QR.
