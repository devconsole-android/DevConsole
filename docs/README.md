# Documentation

**Start here**
- [Threat model and safe operation](THREAT_MODEL.md) — read before turning on LAN mode

**Getting started**
- [Compose](GETTING_STARTED_COMPOSE.md)
- [XML/Kotlin](GETTING_STARTED_XML_KOTLIN.md)
- [XML/Java](GETTING_STARTED_XML_JAVA.md)

**Operating the SDK**
- [Build variants and production safety](BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md)
- [LAN permission and troubleshooting](LAN_PERMISSION_AND_TROUBLESHOOTING.md) — includes the
  session-code connect flow
- [Background keep-alive](BACKGROUND_KEEPALIVE.md) — the opt-in foreground service that keeps the
  server alive while the host app is backgrounded
- [FAQ / troubleshooting](FAQ_TROUBLESHOOTING.md)
- [Event storage and retention](STORAGE.md)

**Inspectors**
- [Network inspector](NETWORK_INSPECTOR.md) — including capture-exclusion rules
- [Network stack adapters](NETWORK_ADAPTERS.md) — OkHttp, Retrofit, Ktor, and writing your own
- [WebSocket inspector](WEBSOCKET_INSPECTOR.md)
- [MQTT capture](MQTT_CAPTURE.md) — Eclipse Paho adapter and the `MQTT` capture category
- [Composer and mocks](COMPOSER_AND_MOCKS.md)
- [Push](PUSH.md)
- [State and feature flags](STATE_AND_FLAGS.md)
- [Crash and ANR capture](CRASH_AND_ANR.md) — all-thread dumps, breadcrumbs, `CrashPolicy`, and the
  Crashes surface on both web and Android
- [Data inspectors, exports, and the More screen](DATA_INSPECTORS_AND_EXPORTS.md) — preferences,
  database, files, the device's More screen, and HAR/Postman/session-ZIP/evidence-bundle exports
- [Evidence tray and bug reports](EVIDENCE_AND_BUG_REPORTS.md) — flagging, snapshot-at-flag, the
  evidence bundle, and the Markdown/Jira/GitHub clipboard formats

**Security**
- [Threat model and safe operation](THREAT_MODEL.md) — read the screenshot section before turning on
  `ScreenshotPolicy.enabled`
- [Security and redaction](SECURITY_AND_REDACTION.md)
- [Vulnerability reporting](../SECURITY.md)

**Reference**
- [Protocol reference](PROTOCOL_REFERENCE.md) — the embedded server's REST/WebSocket wire protocol
- API reference is generated from source via Dokka, not hand-written (hand-written prose would
  drift out of sync with the code). Run `./gradlew dokkaGenerate` and open
  `build/dokka/html/index.html`. Covers `sdk:api` — the module with a committed public API
  surface (see the root `apiValidation` block).

**Contributing / releasing**
- [Migration guide](MIGRATION.md)
- [Release checklist](RELEASE_CHECKLIST.md)
