# Push

## Recording a push event

```kotlin
val event = DevConsole.recordPush(
    PushInput(
        provider = "fcm",
        data = remoteMessage.data,
        messageId = remoteMessage.messageId,
    ),
)
```

Call this directly from your own push receiver (e.g. `FirebaseMessagingService.onMessageReceived`).
`recordPush` redacts `data`/`rawMetadata` the same way as network capture and returns the stored
`PushEvent` — it never attempts to deliver or re-dispatch the message, only records it.

## Firebase adapter

If you use Firebase Cloud Messaging, `FirebaseRemoteMessageAdapter` converts a
`RemoteMessage` to `PushInput` without a compile-time Firebase dependency (it uses reflection
internally, so Firebase stays absent from consumers that don't use this adapter):

```kotlin
val input = FirebaseRemoteMessageAdapter().toPushInput(remoteMessage)
DevConsole.recordPush(input)
```

## Lifecycle

`PushInput.lifecycle` (default `RECEIVED`) also supports `DISPLAYED`, `SUPPRESSED`, `OPENED`,
`ACTION_CLICKED`, `DEEP_LINK_RESOLVED`, and `HANDLING_FAILED` — record each transition as your own
handling code reaches it, so the dashboard shows the full chain for one message, not just arrival.

## Simulation

Any authenticated dashboard session can simulate a local push via the Push page's simulation form
(`POST /api/v1/push/simulate`) — this always sets `simulated = true` and is clearly labeled in the
dashboard as a local simulation, never described as real FCM delivery. It returns `409
PUSH_SIMULATION_UNAVAILABLE` unless the host app supplied a `PushSimulator`. Simulated events go
through the same redaction path as captured ones.

## Dashboard

The Push page lists received and simulated events together, distinguishing them via the
`simulated` flag, with notification/data tabs and the lifecycle chain for each message.
