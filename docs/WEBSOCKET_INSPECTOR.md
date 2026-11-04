# WebSocket inspector

## Wiring OkHttp capture

```kotlin
val webSocket = client.newWebSocket(
    request,
    DevConsoleOkHttpWebSocketListener(DevConsole.socketRecorder()),
)
```

`DevConsoleOkHttpWebSocketListener` wraps `okhttp3.WebSocketListener`, recording open/message/close
callbacks without changing socket control flow. If you need to also react to socket events
yourself, wrap `DevConsole.socketRecorder()`'s owning listener rather than replacing it — every
callback is fail-open (a recording error never propagates to your socket code).

**⚠️ Avoid reusing an instrumented OkHttpClient for WebSockets:** If the client has
`DevConsoleOkHttpInterceptor` installed (for network capture), `client.newWebSocket(...)` will
double-capture the upgrade handshake: once in the Network tab (as an HTTP request), and again in
the WebSocket tab (as a connection). Create a separate plain `OkHttpClient` for WebSocket traffic,
or use a different HTTP client for WebSockets if your main client is instrumented. See
[`samples/foundation-app`](../samples/foundation-app) for an example.

If you use a different transport, `SocketRecorder` is the generic manual entry point:
`onOpen(connectionId, url, reconnectAttempt)`, `onMessage(connectionId, direction, text,
contentType)`, `onBinaryMessage(connectionId, direction, length, contentType)`, and
`onClosed(connectionId, failed, error)`.

## What gets captured

Connection lifecycle (open/closed/failed, with reconnect attempt count) and per-message previews.
Text frames are redacted and bounded to 64 KiB; binary frames record only length and a truncation
flag (`length > 4 KiB`), never raw bytes. The full working example is
[`samples/foundation-app`](../samples/foundation-app/src/main/kotlin/io/devconsole/sample/MainActivity.kt).

## Dashboard

The WebSockets page splits into a connection list (URL, state, duration, sent/received counts,
errors) and a message stream for the selected connection, with direction and a redacted
text/binary preview.

## Sending a frame

Open a frame of a live connection and use the composer under it (dashboard) or on the Sockets tab
(inspector) to send a text frame over the app's own socket. The sent frame is captured like any
other. Sending is offered only for connections the adapter registered as writable — an OkHttp
WebSocket opened through `DevConsoleOkHttpWebSocketListener`/the wrapper, or a Paho client wrapped
by `DevConsolePahoMqtt` — and stops when the connection closes or fails.

It is gated by `EditingCapabilities.requestExecution` (the capability Composer and resend share), so
a host that turns that off also turns off socket send. Frames are capped at 64 KiB. The audit log
records the size and MQTT topic, never the frame text.

`POST /api/v1/websockets/connections/{id}/send` takes a form body `text=...` (plus `topic`, `qos`,
`retained` for MQTT) and answers `{"status":"sent"}`, or `NOT_CONNECTED` (409), `SEND_REJECTED` with a
`message` or `VALIDATION_FAILED` (400), `SOCKET_SEND_DISABLED` (403), `SEND_UNSUPPORTED` (503).
Connection objects carry `canSend` and `sendProtocol` (`websocket`, `mqtt`, or null).

## Redaction

Same engine, same default field names as network capture — see
[SECURITY_AND_REDACTION.md](SECURITY_AND_REDACTION.md). A text frame containing
`{"token": "Bearer secret"}` is stored and streamed with that value already replaced.
