# Location pings

Apps that report the device's position in the background (ride, delivery, fleet and tracking apps)
send a steady stream of "location ping" requests. When a route looks wrong on the server, the
question is almost always *what did the phone actually send, and when?* The location ping tracker
answers it: DevConsole records the ping requests you name to files on the device and replays them on
a map in the browser dashboard.

- Every ping is a dot on the map, coloured by what happened to it: **sent** (2xx), **failed**
  (any other HTTP status), **dropped on device** (the app filtered it out before sending) or
  **offline** (it never reached the server).
- The gap between pings, by device time and by location-fix time, can be shown next to each dot.
- Filter by status and by app state (foreground or background), list the pings in order, and open
  any one to see every field it carried.
- Files survive app restarts, so a tester can record a trip and you can look at it later.

Nothing is recorded until you switch a stream on, either from the dashboard or from the in-app
inspector's **More → Location pings** switches. The switch state is remembered across launches.

## Set it up

Tell DevConsole which endpoints are location pings and where the values live in their bodies:

```kotlin
DevConsole.initialize(
    application,
    DevConsoleConfig.default().withLocationPingTracking(
        LocationPingTracking(
            streams = listOf(
                LocationPingStream(name = "idle", path = "/v2/driver/locations"),
                LocationPingStream(name = "trip", path = "/v2/trips/locations"),
            ),
            schema = LocationPingSchema(
                pingListKey = "locations",
                latitudeKey = "lat",
                longitudeKey = "lng",
                deviceTimeKey = "recorded_at",
                fixTimeKey = "fix_time",
                groupIdKey = "trip_id",
                appStateKey = "app_state",
                groupLabel = "Trip",
            ),
        ),
    ),
)
```

From Java, use `DevConsoleConfig.builder().locationPingTracking(...)`.

Requests that go through the DevConsole OkHttp interceptor are recorded automatically, whatever the
response. Pings that never reach the network have to be reported by the app:

```kotlin
DevConsole.recordLocationPing(url, body, LocationPingOutcome.DroppedOnDevice)
DevConsole.recordLocationPing(url, body, LocationPingOutcome.NoConnection)
```

`DevConsole.isLocationPingTrackingEnabled("trip")` tells you whether a stream is being recorded.
Both calls are no-ops in the release (`devconsole-noop`) build.

### Streams

| Field | Meaning |
|---|---|
| `name` | Folder name and dashboard label. 1–64 letters, digits, `.`, `_` or `-`. |
| `path` | Matched against the request path only. The host and query string are ignored, so staging, production and local hosts are all captured. A request whose path *ends with* the stream path also matches (`/api/v2/trips/locations` matches `/v2/trips/locations`); the longest matching path wins. |

### Schema

Every key is looked up on the ping object first and on the request body second. A value shared by a
whole batch, such as a group id or the app state, can sit at the top level while the coordinates sit
on each ping.

| Field | Default | Meaning |
|---|---|---|
| `pingListKey` | `null` | Array of pings in the body. `null` means the body itself is one ping. |
| `latitudeKey`, `longitudeKey` | `latitude`, `longitude` | Numbers or numeric strings. Pings without valid coordinates are skipped. |
| `deviceTimeKey` | `timestamp` | When the device created the ping. Epoch milliseconds, or epoch seconds (detected automatically). |
| `fixTimeKey` | `null` | When the location fix was taken, for the "fix time gap". |
| `groupIdKey` | `null` | Splits a stream into one track per value, such as an order or trip id. A string, a number, or an array (the first entry is used). |
| `appStateKey` | `null` | Enables the foreground/background filter. Values containing `fore` or `back` are recognised. |
| `groupLabel` | `Group` | What the dashboard calls a group, such as "Order" or "Trip". |

## Storage

Pings are appended as JSON lines to the app's private files directory:

```
files/devconsole/location-pings/<stream>/<group>-<yyyy-MM-dd>.jsonl
```

Each line holds the outcome, the time it was recorded and the request body exactly as sent. A ping
without a group id is filed under the stream name. No storage permission is needed and no other app
can read the files; pull them with `adb shell run-as <your.package> ...` if you need the raw data.

Recording stops at `maxStorageBytes` (64 MB by default). The dashboard then shows why, and deleting
tracks frees the space. Location pings are not subject to the timeline's retention policy; they stay
until you delete them.

## The dashboard

**Location pings** sits under *Traffic* in the dashboard's rail. It shows:

- **Recording** switches, one per configured stream.
- Stream and group pickers. Tracks are listed newest first with their date and size.
- Status and app-state filters, the device and fix time gap labels, and an auto-refresh toggle.
- A route summary: pings shown, delivery rate, the breakdown of undelivered pings, distance and
  duration.
- The map, with the first and last ping ringed, and a ping list beside it. Selecting a row centres
  the map on that ping; selecting it again opens its details.
- **Delete track** and **Delete all**, both confirmed first.

The map tiles come from OpenStreetMap, so the computer viewing the dashboard needs internet access to
draw streets. The route and pings still draw without it. Leaflet (BSD-2-Clause, licence in
`sdk/server-ktor/src/main/resources/devconsole-web/leaflet-LICENSE.txt`) ships inside the dashboard,
and `https://tile.openstreetmap.org` is the only external origin the dashboard's Content Security
Policy allows, and only for images.

## Security

- Coordinates are shown as recorded; that is the point of the feature. Every other field passes
  through the same `RedactionPolicy` as network captures before it reaches the dashboard, so a
  `token` or `password` field in a ping body is masked.
- Every location ping route needs a browser session. Toggling a stream and deleting data also need
  the CSRF token, and are written to the command audit log.
- Everything is gated by the `NETWORK` capture category. With it off, nothing is recorded and every
  route answers `403 CATEGORY_DISABLED`.
