# Background keep-alive

## What it does

The Dev Console server runs inside the host app's own process. When the host app is
backgrounded, the OS is free to kill that process at any time, which kills the server and drops
any connected dashboard session with it. The keep-alive feature is an opt-in foreground service
that pins the host process alive for as long as the server is running, so a backgrounded app keeps
serving the dashboard instead of silently dying.

The service is a thin shell — it does not run the server itself (that already lives in the host
process); it exists only so the OS treats the process as foreground-priority, and to own a status
notification. While it's active you get an ongoing, low-priority notification titled "Dev Console
server running" whose body is the current endpoint URL, with a "Stop server" action that tears
both the server and the service down.

## Opt-in

DevConsole declares **zero** `uses-permission` entries for this feature anywhere in its own
manifests — not even in `sdk:full`. The SDK's full manifest carries only the `<service>`
component registration itself:

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

A component registration carries no permission footprint and no Play policy weight by itself, and
because `sdk:full` is consumed via `debugImplementation`, it never reaches a release build anyway.
The feature only turns on when the **host app** opts in, by declaring permissions in its own debug
manifest (`src/debug/AndroidManifest.xml`, the same file `ACCESS_LOCAL_NETWORK` already lives in
for LAN mode):

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<!-- Optional: makes the keep-alive notification visible on Android 13+. -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

What each permission is for:

- **`FOREGROUND_SERVICE`** — a normal, auto-granted permission required on API 28+ to start any
  foreground service at all.
- **`FOREGROUND_SERVICE_SPECIAL_USE`** — required in addition on API 34+, because the service
  declares `foregroundServiceType="specialUse"`. `specialUse` is the correct type for a
  developer-tool server: it has no runtime prerequisites and, unlike `dataSync` on Android 15,
  no six-hour cap.
- **`POST_NOTIFICATIONS`** — optional. It only controls whether the status notification is
  *visible*; it has no bearing on whether the service, and therefore the server, keeps running.
  See the next section.

A host that adds none of this gets exactly the pre-feature behavior: no service starts, no
notification appears, nothing changes. There is no code-side toggle to flip — the manifest is the
only opt-in surface.

At runtime, `KeepAliveGate` (in `sdk:full`) reads the **merged** manifest —
`PackageManager.getPackageInfo(packageName, GET_PERMISSIONS).requestedPermissions` — to decide
whether it's safe to start the service, banded by API level:

| API level | Requirement to start the foreground service |
| --- | --- |
| < 28 | None — no permission exists to check, the gate passes unconditionally. |
| 28–33 | Host manifest must declare `FOREGROUND_SERVICE`. |
| 34+ | Host manifest must declare both `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`. |

This check runs every time the server starts, using whatever the host's merged manifest actually
contains — there's nothing to configure beyond the manifest entries above.

## Notification visibility vs. keep-alive

These are two independent things, and it's easy to conflate them:

- **Keep-alive** (the process staying alive) is controlled entirely by `FOREGROUND_SERVICE` /
  `FOREGROUND_SERVICE_SPECIAL_USE`, checked once at server start.
- **Notification visibility** is controlled by `POST_NOTIFICATIONS`, a separate *runtime*
  permission on Android 13+ (API 33+).

On API 33+, if the host declared `POST_NOTIFICATIONS` but the user hasn't granted it (or has
denied it), the foreground service still starts and the process is still kept alive exactly as
before — the OS simply hides the notification. A denied notification permission never blocks or
stops the service; it only affects whether the user can see it's running.

Because of that, DevConsole never asks for `POST_NOTIFICATIONS` on its own initiative. Instead:

- The Compose Control surface (`InspectorControlScreen`) shows a dismissible snackbar offering the
  grant.
- The views launcher (`DevConsolePanelView`) shows an equivalent notice row with "Allow" and
  "Dismiss" actions.

Both are offered **only** when all of the following hold: the server is running, the host opted
into the foreground service at all, the host's manifest declares `POST_NOTIFICATIONS`, and the
permission isn't granted yet. If the host never declared `POST_NOTIFICATIONS`, neither surface
ever prompts for it — requesting a permission the manifest doesn't declare is a silent no-op on
Android, so offering it would just mislead the user. Dismissing the prompt silences it for the
rest of the process's lifetime; it reappears on the next app run.

## Behavior matrix

How the service behaves across every declaration and permission state:

| Scenario | Outcome |
| --- | --- |
| Host declared nothing | Gate fails, no FGS, no snackbar, behavior identical to today |
| FGS permissions declared, notifications denied (API 33+) | Service runs, process kept alive, notification hidden by OS; snackbar offers the grant if declared |
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
