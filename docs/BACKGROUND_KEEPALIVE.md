# Background keep-alive

## What it does

The Dev Console server runs inside the host app's own process. When the host app is
backgrounded, the OS is free to kill that process at any time, which kills the server and drops
any connected dashboard session with it. The keep-alive feature is enabled by default in the full
debug runtime: it pins the host process alive for as long as the server is running, so a
backgrounded app keeps serving the dashboard instead of silently dying.

The service is a thin shell — it does not run the server itself (that already lives in the host
process); it exists only so the OS treats the process as foreground-priority, and to own a status
notification. While it's active you get an ongoing, low-priority notification titled "Dev Console
server running" whose body is the current endpoint URL, with a "Stop server" action that tears
both the server and the service down.

## Default behavior

`sdk:full` declares the foreground-service and notification permissions in its own manifest. No
host-side permission block is required for the default debug setup:

```xml
<service
    android:name="io.devconsole.DevConsoleForegroundService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="devconsole-local-debug-server" />
</service>
```

The full runtime also declares `ACCESS_LOCAL_NETWORK` and `NEARBY_WIFI_DEVICES` for the dashboard's
local-network binding. Because `sdk:full` is consumed via `debugImplementation`, these declarations
never reach a release build; `sdk:noop` has no server, service, or permissions.

The equivalent declarations are:

```xml
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
<uses-permission
    android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

A host may still remove these declarations with an explicit manifest-merger rule. `KeepAliveGate`
checks the merged manifest defensively and skips the service if its required foreground permission
is unavailable; the server itself is never blocked by a keep-alive failure.

What each permission is for:

- **`FOREGROUND_SERVICE`** — a normal, auto-granted permission required on API 28+ to start any
  foreground service at all.
- **`FOREGROUND_SERVICE_SPECIAL_USE`** — required in addition on API 34+, because the service
  declares `foregroundServiceType="specialUse"`. `specialUse` is the correct type for a
  developer-tool server: it has no runtime prerequisites and, unlike `dataSync` on Android 15,
  no six-hour cap.
- **`POST_NOTIFICATIONS`** — declared by the full runtime, but still a runtime grant on Android
  13+. It only controls whether the status notification is *visible*; it has no bearing on whether
  the service, and therefore the server, keeps running.
  See the next section.
- **`ACCESS_LOCAL_NETWORK`** — a dangerous permission enforced for direct local-network traffic on
  Android 17 (API 37+) and part of Android's Nearby devices group.
- **`NEARBY_WIFI_DEVICES`** — the Nearby devices permission used by Android 13–16's local-network
  compatibility path. DevConsole does not use Wi-Fi information to derive physical location.

The foreground service is started whenever the full runtime successfully starts the dashboard
server, and it is stopped whenever the server stops. There is no separate keep-alive toggle.

At runtime, `KeepAliveGate` (in `sdk:full`) reads the **merged** manifest —
`PackageManager.getPackageInfo(packageName, GET_PERMISSIONS).requestedPermissions` — to decide
whether it's safe to start the service, banded by API level:

| API level | Requirement to start the foreground service |
| --- | --- |
| < 28 | None — no permission exists to check, the gate passes unconditionally. |
| 28–33 | The merged manifest must declare `FOREGROUND_SERVICE`; `sdk:full` supplies it by default. |
| 34+ | The merged manifest must declare both `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`; `sdk:full` supplies both by default. |

This check runs every time the server starts, using whatever the app's merged manifest actually
contains. In the normal full-runtime setup, the default declarations above make the service start
automatically.

## Notification visibility vs. keep-alive

These are two independent things, and it's easy to conflate them:

- **Keep-alive** (the process staying alive) is controlled entirely by `FOREGROUND_SERVICE` /
  `FOREGROUND_SERVICE_SPECIAL_USE`, checked once at server start.
- **Notification visibility** is controlled by `POST_NOTIFICATIONS`, a separate *runtime*
  permission on Android 13+ (API 33+).

On API 33+, if the full runtime declared `POST_NOTIFICATIONS` but the user hasn't granted it (or
has denied it), the foreground service still starts and the process is still kept alive exactly as
before for host/API starts — the OS simply hides the notification. A denied notification permission
never blocks or stops a host/API-started service; it only affects whether the user can see it's
running. Notification access is optional for the SDK-owned More-screen start too: the server starts
first, then the UI offers the grant so the notification and its Stop action become discoverable.

The permission flows are:

- The Compose **Control and More** surfaces show a dismissible snackbar offering the grant after a
  start that began without it. Granting re-posts the notification; denial or dismissal leaves the
  server running and does not immediately repeat the prompt in that process. More matters as much as
  Control: it is where someone who just started one goes looking for the notification.
- The views launcher (`DevConsolePanelView`) shows an equivalent notice row with "Allow" and
  "Dismiss" actions.

Granting also **re-posts** the notification. Android suppresses a foreground service's notification
when the service starts without `POST_NOTIFICATIONS` and does not post it retroactively once the
permission is granted — the service keeps running with a notification attached that the shade never
shows. Accepting the prompt therefore re-issues the keep-alive start, so `startForeground` runs
again and the notification finally appears. (Granting from Settings instead, while the server is
already running, does not go through that path: stop and start the server, or reopen the inspector
and let it re-issue, to make the notification appear.)

The post-start snackbar is offered only when all of the following hold: the server is running, the
merged foreground-service permissions are present, `POST_NOTIFICATIONS` is declared, and the
runtime grant is missing. The full runtime supplies that declaration by default. If a host removes
it, no surface prompts for it — requesting a permission the manifest doesn't declare is a silent
no-op on Android, so offering it would just mislead the user. Dismissing the post-start prompt
silences it for the rest of the process's lifetime; it reappears on the next app run.

## Behavior matrix

How the service behaves across every declaration and permission state:

| Scenario | Outcome |
| --- | --- |
| Full runtime defaults (FGS permissions present), notifications denied (API 33+) | SDK-owned More Start waits for the grant; a host/API start runs with the notification hidden and the post-start snackbar offers the grant |
| Host removes the FGS permissions | Gate skips the service, while the server continues running; no keep-alive snackbar |
| FGS start throws (background start, OEM quirk) | Caught, logged; server unaffected; retried naturally on next server start |
| Server stops for any reason | Service stopped in `stopLocked` |
| Task swipe (active keep-alive) | Process is **not** killed -- an active foreground service survives task removal (`stopWithTask` defaults `false`, `onTaskRemoved` isn't overridden); the notification's "Stop server" action (or the dashboard) is what stops it |
| Process death (actual kill) | `START_NOT_STICKY` prevents the service from restarting; existing crash-row handling untouched |
| API < 28 | No permissions exist to check; gate passes; FGS runs |
| API < 33 | No notification runtime permission; notification always visible; snackbar never needed |

## Play Store note

`specialUse` foreground service types require a declaration in Play Console when your app ships a
build that carries this service. The supported configuration for DevConsole is
`debugImplementation("io.devconsole:sdk-full:...")` — release builds pull in `sdk:noop` instead,
which has no server, no foreground service, and none of this feature. As long as you follow that
configuration, no release build of your app ever carries `DevConsoleForegroundService` and there's
nothing to declare.

If you deliberately ship `sdk:full` in a release build (outside the supported configuration), you
own the Play Console special-use FGS declaration for it — DevConsole does not, and cannot, do that
on your behalf.
