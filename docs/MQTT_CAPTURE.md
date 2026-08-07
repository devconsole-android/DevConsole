# MQTT capture

MQTT rides the same socket pipeline as WebSockets — one connection/message store, one Sockets
inspector — rather than a parallel one. It is a separate `CaptureCategory` from `SOCKET`
(`io.devconsole.api.CaptureCategory.MQTT`), so a host can capture one without the other:

```kotlin
DevConsole.initialize(
    application,
    DevConsoleConfig(/* … */).withCaptureCategories(CaptureCategory.SOCKET, CaptureCategory.MQTT),
)
```

The default (`DevConsoleConfig.default()`) captures every category, MQTT included — this only
matters for a host that wants to *narrow* it.

## Module coordinates

```kotlin
// build.gradle.kts
debugImplementation(project(":sdk:socket-paho"))
releaseImplementation(project(":sdk:socket-paho-noop"))
implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
```

Same debug/release pairing as every other capture adapter in this SDK: identical package and class
names on both sides, so a release build recompiles against the no-op module with no source change.
`sdk:socket-paho-noop` never inspects the client or records anything — see
[BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md](BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md).

## Wiring Paho capture

```kotlin
val client = MqttAsyncClient(
    "tcp://broker.hivemq.com:1883",
    MqttAsyncClient.generateClientId(),
    MemoryPersistence(),
)
val publisher = DevConsolePahoMqtt.install(client, DevConsole.socketRecorder())
client.connect(MqttConnectOptions().apply { isCleanSession = true })
client.subscribe("devconsole/demo/#", 1)
publisher.publish("devconsole/demo/hello", "hi from DevConsole".toByteArray(), 1, false)
```

`install(...)` wraps the client for sends and installs the recording callback in one call,
returning a `DevConsoleRecordingMqttPublisher` — use its `publish(...)` (both the `MqttMessage` and
raw-`ByteArray` overloads are recorded) and `disconnect()` instead of calling the delegate client
directly, so outbound frames are captured too. If you need your own `MqttCallback` alongside
capture, pass it as `install`'s third argument — every host callback still fires, even if recording
itself fails; recording is fail-open and never breaks message delivery.

## The topic/qos/retained convention

MQTT topic, QoS, and retained flag ride in the existing `SocketMessage.contentType` string, rather
than adding new fields to the socket model:

```
application/mqtt; topic=devconsole/demo/hello; qos=1; retained=false
```

`%`, `;`, and `=` in the topic are percent-encoded. Parse it back with `MqttFrameMetadata.topic(...)`,
`.qos(...)`, and `.retained(...)` (all in `io.devconsole.socket`) rather than hand-rolling the
format — the encoding is an implementation detail. Every connection recorded this way carries
`SocketConnection.protocol == SocketProtocol.MQTT`, which is how the dashboard and the in-app
inspector tell an MQTT connection apart from a WebSocket one on the same list.

## What gets captured

Connection lifecycle (created/open/closed/failed) and per-message previews, with the same
redaction, size caps, and text/binary handling as WebSocket capture — see
[WEBSOCKET_INSPECTOR.md](WEBSOCKET_INSPECTOR.md) and
[SECURITY_AND_REDACTION.md](SECURITY_AND_REDACTION.md). MQTT topics are also redacted per the same
policy. A received payload is stored as text only if it decodes as valid UTF-8; anything else is
stored as binary. Sends are recorded only after the delegate client itself returns a token,
mirroring the "record on the delegate's own success signal" rule the OkHttp WebSocket adapter uses.

See `samples/compose-app`'s "Open sample MQTT connection" action for a complete, crash-safe example
against a public test broker.
