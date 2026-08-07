package io.devconsole.server.ktor

import io.devconsole.api.EventEnvelope
import io.devconsole.api.EventSeverity
import io.devconsole.composer.ComposerExecutor
import io.devconsole.composer.ComposerResponse
import io.devconsole.composer.ComposerTransport
import io.devconsole.composer.InMemoryComposerCollectionStore
import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockRule
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkRequestMetadata
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkResponseMetadata
import io.devconsole.network.NetworkTimingPhases
import io.devconsole.network.NetworkTransaction
import io.devconsole.network.NetworkTransactionRecorder
import io.devconsole.push.InMemoryPushStore
import io.devconsole.push.PushEvent
import io.devconsole.push.PushLifecycle
import io.devconsole.push.PushRecorder
import io.devconsole.push.PushSimulationCallback
import io.devconsole.push.PushSimulator
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.BindingMode
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.Endpoint
import io.devconsole.server.api.InMemoryCommandAuditLog
import io.devconsole.server.api.SdkHealthSnapshot
import io.devconsole.server.api.ServerMetadata
import io.devconsole.server.api.ServerStartResult
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.devconsole.server.api.StartRequest
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.MqttFrameMetadata
import io.devconsole.socket.SocketConnection
import io.devconsole.socket.SocketDirection
import io.devconsole.socket.SocketFrameType
import io.devconsole.socket.SocketLifecycleEvent
import io.devconsole.socket.SocketLifecycleType
import io.devconsole.socket.SocketMessage
import io.devconsole.socket.SocketMessageMetadata
import io.devconsole.socket.SocketPayload
import io.devconsole.socket.SocketProtocol
import io.devconsole.socket.SocketTextFormat
import io.devconsole.state.FeatureFlag
import io.devconsole.state.SessionFeatureFlags
import io.devconsole.state.StateMutationResult
import io.devconsole.state.StateMutator
import io.devconsole.state.StateRegistry
import io.devconsole.state.StateSnapshot
import io.devconsole.state.StateValue
import io.devconsole.state.stateProvider
import io.devconsole.storage.api.EventStore
import io.devconsole.storage.api.EventStoreWriteResult
import io.devconsole.storage.api.RedactionApplicability
import io.devconsole.storage.api.RetainedCaptureQuery
import io.devconsole.storage.api.StoredAttachment
import io.devconsole.storage.api.StoredEvent
import io.devconsole.storage.api.StoredSession
import io.devconsole.storage.api.StoredSessionStatus
import io.devconsole.timeline.CursorCodec
import io.devconsole.timeline.InMemoryTimeline
import io.devconsole.timeline.InMemoryTimelineAnnotations
import io.devconsole.timeline.TimelinePage
import io.devconsole.timeline.TimelineQuery
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.plugin
import io.ktor.server.testing.runTestApplication
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.websocket.WebSockets as ServerWebSockets

class DevConsoleKtorModuleTest {
    @Test
    fun `unauthenticated health exposes only auth status protocol and display name`() =
        testApplication {
            application {
                devConsoleModule(SessionAuthority()) {
                    metadata =
                        ServerMetadata(
                            protocolVersion = 7,
                            appDisplayName = "Sample App",
                            appPackageName = "io.secret.package",
                            appVersionName = "private-version",
                            buildVariant = "internal",
                        )
                }
            }

            val response = client.get("/health") { header(HttpHeaders.Host, "localhost") }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "{\"status\":\"auth_required\",\"protocolVersion\":7,\"appDisplayName\":\"Sample App\"}",
                response.bodyAsText(),
            )
        }

    @Test
    fun `composer secrets are redacted before storage and read only responses`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            val collections = InMemoryComposerCollectionStore()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    commandAuditLog = audit
                    composerCollections = collections
                }
            }
            val writer = client.exchangeSession(sessions, sessionCodes, "Writer")
            val reader = client.exchangeSession(sessions, sessionCodes, "Reader")

            val imported =
                client.post("/api/v1/composer/import") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${writer.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", writer.csrfToken)
                    setBody("curl 'https://api.test/orders?access_token=route-canary'")
                }
            val saved =
                client.post("/api/v1/composer/collections") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${writer.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", writer.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody(
                        "name=secrets&curl=curl%20%27https%3A%2F%2Fapi.test%2Forders%3Faccess_token%3Droute-canary%27",
                    )
                }
            val auditResponse =
                client.get("/api/v1/plugins/audit") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${reader.token}")
                }
            val collectionResponse =
                client.get("/api/v1/composer/collections") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${reader.token}")
                }

            assertEquals(HttpStatusCode.OK, imported.status)
            assertEquals(HttpStatusCode.Created, saved.status)
            assertEquals(HttpStatusCode.OK, auditResponse.status)
            assertEquals(HttpStatusCode.OK, collectionResponse.status)
            listOf(
                imported.bodyAsText(),
                saved.bodyAsText(),
                auditResponse.bodyAsText(),
                collectionResponse.bodyAsText(),
            ).forEach { body ->
                assertFalse("route secret leaked in $body", body.contains("route-canary"))
                assertTrue("redaction marker missing from $body", body.contains("<redacted>"))
            }
            assertFalse(audit.events().toString().contains("route-canary"))
            assertFalse(collections.collections().toString().contains("route-canary"))
        }

    @Test
    fun `health and dashboard are available without credentials`() =
        testApplication {
            application { devConsoleModule(SessionAuthority()) }

            assertEquals(HttpStatusCode.OK, client.get("/health") { header(HttpHeaders.Host, "localhost") }.status)
            val dashboardResponse = client.get("/") { header(HttpHeaders.Host, "localhost") }
            assertEquals("no-store", dashboardResponse.headers[HttpHeaders.CacheControl])
            val dashboard = dashboardResponse.bodyAsText()
            assertTrue(dashboard.contains("DevConsole"))
            assertTrue(dashboard.contains("WebSockets"))
            assertTrue(dashboard.contains("Composer"))
            assertTrue(dashboard.contains("Mocks"))
            assertTrue(dashboard.contains("State &amp; Flags"))
            assertTrue(dashboard.contains("id=\"viewState\""))
            assertTrue(dashboard.contains("id=\"stateView\""))
            assertTrue(dashboard.contains("id=\"viewPush\""))
            assertTrue(dashboard.contains("id=\"pushView\""))
            assertTrue(dashboard.contains("id=\"pushSimulate\""))
            assertTrue(dashboard.contains("id=\"viewComposer\""))
            assertTrue(dashboard.contains("id=\"composerView\""))
            // "Clone to composer" is a detail-pane action button rendered by dashboard.js from
            // live selection state (design refresh W2) rather than static markup with a fixed
            // id; the detail pane's container id is the stable anchor to assert on instead.
            assertTrue(dashboard.contains("id=\"networkDetailPane\""))
            assertTrue(dashboard.contains("id=\"composerCollectionName\""))
            assertTrue(dashboard.contains("id=\"composerCollectionSave\""))
            assertTrue(dashboard.contains("id=\"viewMocks\""))
            assertTrue(dashboard.contains("id=\"mockRuleId\""))
            assertTrue(dashboard.contains("id=\"mockRuleSave\""))
            // "Refresh rules" was a manual-refresh button superseded by the design-refresh W3
            // card view (Mocks reloads on every visit like every other view); mockRuleList is the
            // stable container id to assert on instead.
            assertTrue(dashboard.contains("id=\"mockRuleList\""))
            assertTrue(dashboard.contains("<link rel=\"stylesheet\" href=\"/assets/dashboard.css\">"))
            assertTrue(dashboard.contains("<script src=\"/assets/dashboard.js\"></script>"))
            assertFalse(dashboard.contains("<style>"))
            assertFalse(dashboard.contains("<script>"))
        }

    @Test
    fun `dashboard css and js assets are available without credentials and cache disabled`() =
        testApplication {
            application { devConsoleModule(SessionAuthority()) }

            val cssResponse = client.get("/assets/dashboard.css") { header(HttpHeaders.Host, "localhost") }
            assertEquals(HttpStatusCode.OK, cssResponse.status)
            assertEquals("no-store", cssResponse.headers[HttpHeaders.CacheControl])
            assertTrue(cssResponse.headers[HttpHeaders.ContentType]!!.contains("text/css"))
            val css = cssResponse.bodyAsText()
            assertTrue(css.contains(".rail"))

            val jsResponse = client.get("/assets/dashboard.js") { header(HttpHeaders.Host, "localhost") }
            assertEquals(HttpStatusCode.OK, jsResponse.status)
            assertEquals("no-store", jsResponse.headers[HttpHeaders.CacheControl])
            assertTrue(jsResponse.headers[HttpHeaders.ContentType]!!.contains("javascript"))
            val js = jsResponse.bodyAsText()
            assertTrue(js.contains("new WebSocket"))
            assertTrue(js.contains("pendingLiveEvents"))
            assertTrue(js.contains("new Map(events.map"))
            assertTrue(js.contains("SIMULATED"))

            // Both assets carry the same security headers as every other response, and neither
            // bakes in session-specific data -- they are static, so no auth header is sent above.
            assertEquals("nosniff", cssResponse.headers["X-Content-Type-Options"])
            assertEquals("nosniff", jsResponse.headers["X-Content-Type-Options"])
        }

    @Test
    fun `network HAR and Postman exports are rate limited tighter than other read queries`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = client.exchangeSession(sessions, sessionCodes)

            repeat(10) {
                assertEquals(
                    HttpStatusCode.OK,
                    client
                        .get("/api/v1/network/har") {
                            header(HttpHeaders.Host, "localhost")
                            header(HttpHeaders.Authorization, "Bearer ${session.token}")
                        }.status,
                )
            }
            val limited =
                client.get("/api/v1/network/postman") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertTrue(limited.bodyAsText().contains("RATE_LIMITED"))
        }

    @Test
    fun `authenticated read queries are rate limited per browser principal`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = client.exchangeSession(sessions, sessionCodes)

            repeat(120) {
                assertEquals(
                    HttpStatusCode.OK,
                    client
                        .get("/api/v1/events") {
                            header(HttpHeaders.Host, "localhost")
                            header(HttpHeaders.Authorization, "Bearer ${session.token}")
                        }.status,
                )
            }
            val limited =
                client.get("/api/v1/events") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertTrue(limited.bodyAsText().contains("RATE_LIMITED"))
        }

    @Test
    fun `authenticated session is polled once and control routes require csrf`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = client.exchangeSession(sessions, sessionCodes)

            val poll =
                client.get("/api/v1/session") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            assertEquals(HttpStatusCode.OK, poll.status)
            assertTrue(poll.bodyAsText().contains("expiresAtEpochMs"))

            val csrfDenied =
                client.post("/api/v1/session/stop") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                }
            assertEquals(HttpStatusCode.Forbidden, csrfDenied.status)

            val accepted =
                client.post("/api/v1/session/stop") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                }
            assertEquals(HttpStatusCode.Accepted, accepted.status)
        }

    @Test
    fun `authenticated browser can rotate credentials list principals and revoke a browser`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = client.exchangeSession(sessions, sessionCodes, "Chrome")

            val refresh =
                client.post("/api/v1/auth/refresh") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                }
            val refreshBody = refresh.bodyAsText()
            val token = Regex("\\\"accessToken\\\":\\\"([^\\\"]+)\\\"").find(refreshBody)!!.groupValues[1]
            val csrf = Regex("\\\"csrfToken\\\":\\\"([^\\\"]+)\\\"").find(refreshBody)!!.groupValues[1]
            val principals =
                client.get("/api/v1/auth/principals") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            val revoked =
                client.delete("/api/v1/auth/principals/${session.id}") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", csrf)
                }
            val after =
                client.get("/api/v1/session") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, refresh.status)
            assertEquals(HttpStatusCode.OK, principals.status)
            assertTrue(principals.bodyAsText().contains("Chrome"))
            assertFalse(principals.bodyAsText().contains(token))
            assertEquals(HttpStatusCode.NoContent, revoked.status)
            assertEquals(HttpStatusCode.Unauthorized, after.status)
        }

    @Test
    fun `authenticated browser reads protocol metadata and bounded sdk health`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = client.exchangeSession(sessions, sessionCodes)

            val unauthenticated = client.get("/api/v1/meta") { header(HttpHeaders.Host, "localhost") }
            val metadata =
                client.get("/api/v1/meta") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val health =
                client.get("/api/v1/sdk-health") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
            assertEquals(HttpStatusCode.OK, metadata.status)
            assertTrue(metadata.bodyAsText().contains("\"protocolVersion\":1"))
            assertTrue(metadata.bodyAsText().contains("\"app\""))
            assertTrue(metadata.bodyAsText().contains("\"build\""))
            assertTrue(metadata.bodyAsText().contains("\"capabilities\""))
            assertEquals(HttpStatusCode.OK, health.status)
            assertTrue(health.bodyAsText().contains("\"initializationCount\""))
            assertTrue(health.bodyAsText().contains("\"droppedEventCount\""))
        }

    @Test
    fun `meta reports null endpoint by default and the real bound endpoint and redaction policy when supplied`() {
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = client.exchangeSession(sessions, sessionCodes)

            val metadata =
                client.get("/api/v1/meta") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, metadata.status)
            val body = metadata.bodyAsText()
            assertTrue(body.contains("\"endpoint\":null"))
            assertTrue(body.contains("\"sensitiveFieldNames\""))
            assertTrue(RedactionPolicy.default().sensitiveFieldNames.any { body.contains("\"$it\"") })
        }
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    boundEndpoint = { Endpoint("127.0.0.1", 8080, BindingMode.LOOPBACK) }
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val metadata =
                client.get("/api/v1/meta") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, metadata.status)
            val body = metadata.bodyAsText()
            assertTrue(body.contains("\"host\":\"127.0.0.1\""))
            assertTrue(body.contains("\"port\":8080"))
            assertTrue(body.contains("\"bindingMode\":\"LOOPBACK\""))
        }
    }

    @Test
    fun `meta reports composer disabled and an empty allowlist by default`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = client.exchangeSession(sessions, sessionCodes)

            val metadata =
                client.get("/api/v1/meta") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, metadata.status)
            assertTrue(metadata.bodyAsText().contains("\"composer\":{\"enabled\":false,\"allowedHosts\":[]}"))
        }

    @Test
    fun `meta exposes the composer enabled flag and its sorted host allowlist`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("auth.example.test", "api.example.test")
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val metadata =
                client.get("/api/v1/meta") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, metadata.status)
            assertTrue(
                metadata.bodyAsText().contains(
                    "\"composer\":{\"enabled\":true,\"allowedHosts\":[\"api.example.test\",\"auth.example.test\"]}",
                ),
            )
        }

    @Test
    fun `stream upgrade with a foreign origin is rejected before authentication`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = client.exchangeSession(sessions, sessionCodes)
            val streamClient = createClient { install(WebSockets) }

            // A valid session cookie but an attacker-controlled Origin: the cross-site WebSocket
            // hijacking case the SameSite cookie already blocks, now refused explicitly too.
            var rejection: CloseReason? = null
            runCatching {
                streamClient.webSocket(
                    urlString = "/api/v1/stream",
                    request = {
                        header(HttpHeaders.Host, "localhost")
                        header(HttpHeaders.Origin, "http://evil.example")
                        header(HttpHeaders.Cookie, "DevConsoleStreamSession=${session.token}")
                    },
                ) {
                    rejection = closeReason.await()
                }
            }

            assertEquals("ORIGIN_REJECTED", rejection?.message)
        }

    @Test
    fun `authenticated stream requires hello then sends a welcome frame`() =
        // 3x the harness default 60s: this test opens a real WebSocket and flakes under heavy
        // parallel suite load (pre-existing; observed at merge-base) when the welcome frame
        // arrives late. testApplication exposes no timeout, so compose its two halves directly.
        runTest(timeout = 180.seconds) {
            runTestApplication {
                val sessions = SessionAuthority()
                val sessionCodes = SessionCodeAuthority(sessions)
                val testStreamHub = EventStreamHub()
                application {
                    devConsoleModule(sessions, sessionCodes) {
                        streamHub = testStreamHub
                    }
                }
                val session = client.exchangeSession(sessions, sessionCodes)
                val streamClient = createClient { install(WebSockets) }

                streamClient.webSocket(
                    urlString = "/api/v1/stream",
                    request = {
                        header(HttpHeaders.Host, "localhost")
                        header(HttpHeaders.Cookie, "DevConsoleStreamSession=${session.token}")
                    },
                ) {
                    send(Frame.Text("{\"type\":\"client.hello\",\"protocolVersion\":1}"))

                    val welcome = (incoming.receive() as Frame.Text).data.decodeToString()
                    assertTrue(welcome.contains("server.welcome"))

                    testStreamHub.publish(
                        EventEnvelope(
                            id = UUID.randomUUID(),
                            sessionId = UUID.randomUUID(),
                            pluginId = "network",
                            type = "network.request",
                            timestampEpochMs = 1,
                            monotonicNanos = 1,
                            sequence = 1,
                            severity = EventSeverity.INFO,
                            summary = "Bearer <redacted>",
                        ),
                    )
                    val event = (incoming.receive() as Frame.Text).data.decodeToString()
                    assertTrue(event.contains("event.appended"))
                    assertTrue(event.contains("<redacted>"))
                    assertTrue(event.contains("\"timestampEpochMs\":1"))
                    assertTrue(event.contains("\"severity\":1"))
                }
            }
        }

    @Test
    fun `authenticated stream closes promptly when its principal is revoked`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = client.exchangeSession(sessions, sessionCodes)
            val streamClient = createClient { install(WebSockets) }

            streamClient.webSocket(
                urlString = "/api/v1/stream",
                request = {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Cookie, "DevConsoleStreamSession=${session.token}")
                },
            ) {
                send(Frame.Text("{\"type\":\"client.hello\",\"protocolVersion\":1}"))
                assertTrue((incoming.receive() as Frame.Text).data.decodeToString().contains("server.welcome"))

                sessions.revokeIfPresent(session.id)

                withTimeout(2_000) { incoming.receiveCatching().getOrNull() }
                assertEquals("AUTH_REVOKED", withTimeout(2_000) { closeReason.await() }?.message)
            }
        }

    @Test
    fun `authenticated browser can page timeline events`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val testTimeline =
                InMemoryTimeline(
                    listOf(
                        StoredEvent("event-1", "session", 1, "system", "system.event", 1, 1, 1, "ready"),
                        StoredEvent("event-2", "session", 2, "network", "network.request", 2, 2, 1, "request"),
                    ),
                    CursorCodec("timeline-cursor-key".encodeToByteArray()),
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    timeline = testTimeline
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/events?limit=1") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("event-1"))
            assertTrue(response.bodyAsText().contains("nextCursor"))
        }

    @Test
    fun `authenticated browser can annotate an event with csrf protection`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val testTimeline =
                InMemoryTimeline(
                    listOf(StoredEvent("event-1", "session", 1, "system", "event", 1, 1, 1, "ready")),
                    CursorCodec("timeline-cursor-key".encodeToByteArray()),
                )
            val testAnnotations = InMemoryTimelineAnnotations()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    timeline = testTimeline
                    annotations = testAnnotations
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/events/event-1/bookmark") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(testAnnotations.get("event-1").bookmarked)
        }

    @Test
    fun `authenticated browser can inspect redacted network transactions and exports`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            store.record(
                NetworkTransaction(
                    id = "transaction-1",
                    startedAtEpochMs = 1,
                    completedAtEpochMs = 2,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                            NetworkRequestInput(
                                "GET",
                                "https://api.test/orders?access_token=raw-secret",
                                headers =
                                    mapOf(
                                        "Authorization" to "Bearer header-secret",
                                    ),
                                contentType = "application/json",
                            ).withMetadata(NetworkRequestMetadata(tags = mapOf("source" to "composer"))),
                            NetworkResponseInput(503, contentType = "application/json", error = "timeout"),
                        ),
                ),
            )
            store.record(
                NetworkTransaction(
                    id = "transaction-2",
                    startedAtEpochMs = 3,
                    completedAtEpochMs = 4,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                            NetworkRequestInput("GET", "https://api.test/not-selected"),
                            NetworkResponseInput(200),
                        ),
                ),
            )
            NetworkTransactionRecorder(
                factory = NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())),
                store = store,
                idProvider = { "transaction-attachment" },
                executor = java.util.concurrent.Executor(Runnable::run),
            ).withAttachmentSink { "attachment-response" }
                .record(
                    NetworkRequestInput("GET", "https://api.test/binary"),
                    NetworkResponseInput(
                        200,
                        body = byteArrayOf(1, 2, 3),
                        contentType = "application/octet-stream",
                    ),
                    startedAtEpochMs = 5,
                    completedAtEpochMs = 6,
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = store
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val transactions =
                client.get(
                    "/api/v1/network/transactions?from=1&to=2&method=GET&statusFrom=500&statusTo=599" +
                        "&host=api.test&path=%2Forders&contentType=application%2Fjson&minDurationMs=1" +
                        "&maxDurationMs=1&error=true&tag=source%3Dcomposer&query=timeout",
                ) {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val curl =
                client.get("/api/v1/network/transactions/transaction-1/curl") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val har =
                client.get("/api/v1/network/har") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val selectedHar =
                client.get("/api/v1/network/har?id=transaction-1") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val postman =
                client.get("/api/v1/network/postman") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val selectedPostman =
                client.get("/api/v1/network/postman?id=transaction-1") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val detail =
                client.get("/api/v1/network/transactions/transaction-attachment") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, transactions.status)
            assertTrue(transactions.bodyAsText().contains("transaction-1"))
            assertTrue(curl.bodyAsText().contains("<redacted>"))
            assertTrue(!curl.bodyAsText().contains("header-secret"))
            assertTrue(har.bodyAsText().contains("\"version\":\"1.2\""))
            assertTrue(har.bodyAsText().contains("\"startedDateTime\":\"1970-01-01T00:00:00.001Z\""))
            assertTrue(har.bodyAsText().contains("\"time\":1"))
            assertTrue(selectedHar.bodyAsText().contains("/orders"))
            assertFalse(selectedHar.bodyAsText().contains("/not-selected"))
            val postmanSchema = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
            assertTrue(postman.bodyAsText().contains(postmanSchema))
            assertTrue(postman.bodyAsText().contains("/orders"))
            assertTrue(postman.bodyAsText().contains("/not-selected"))
            assertFalse(postman.bodyAsText().contains("header-secret"))
            assertFalse(postman.bodyAsText().contains("raw-secret"))
            assertTrue(selectedPostman.bodyAsText().contains("/orders"))
            assertFalse(selectedPostman.bodyAsText().contains("/not-selected"))
            assertTrue(detail.bodyAsText().contains("\"attachmentId\":\"attachment-response\""))
            assertTrue(detail.bodyAsText().contains("\"type\":\"binary\""))
        }

    @Test
    fun `network export reports truncation via headers instead of silently dropping rows`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            repeat(3) { index ->
                store.record(
                    NetworkTransaction(
                        id = "transaction-$index",
                        startedAtEpochMs = index.toLong(),
                        completedAtEpochMs = index.toLong() + 1,
                        capture =
                            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                                NetworkRequestInput("GET", "https://api.test/item/$index"),
                                NetworkResponseInput(200),
                            ),
                    ),
                )
            }
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = store
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            // Filter-based selection ("everything matching the current filter"): a `limit` narrower
            // than the 3 matching rows must report truncation, and the untruncated case must say so too.
            val truncatedFilter =
                client.get("/api/v1/network/har?limit=2") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val untruncatedFilter =
                client.get("/api/v1/network/har?limit=3") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            // Id-based selection: one requested id does not exist, so it is dropped (never an error)
            // but must still be reported as truncated, per [ExportSelection.Ids]'s "silently dropped"
            // contract paired with the export route's own truncation header.
            val truncatedIds =
                client.get("/api/v1/network/postman?id=transaction-0&id=does-not-exist") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val untruncatedIds =
                client.get("/api/v1/network/postman?id=transaction-0&id=transaction-1") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals("true", truncatedFilter.headers["X-DevConsole-Export-Truncated"])
            assertEquals("2", truncatedFilter.headers["X-DevConsole-Export-Count"])
            assertEquals("500", truncatedFilter.headers["X-DevConsole-Export-Limit"])
            assertEquals("false", untruncatedFilter.headers["X-DevConsole-Export-Truncated"])
            assertEquals("3", untruncatedFilter.headers["X-DevConsole-Export-Count"])

            assertEquals("true", truncatedIds.headers["X-DevConsole-Export-Truncated"])
            assertEquals("1", truncatedIds.headers["X-DevConsole-Export-Count"])
            assertEquals("false", untruncatedIds.headers["X-DevConsole-Export-Truncated"])
            assertEquals("2", untruncatedIds.headers["X-DevConsole-Export-Count"])
        }

    @Suppress("LongMethod") // One linear E2E: seed two transactions, exercise auth/csrf/success on both POST routes.
    @Test
    fun `network export POST accepts a bulk id selection behind auth and csrf`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            store.record(
                NetworkTransaction(
                    id = "transaction-1",
                    startedAtEpochMs = 1,
                    completedAtEpochMs = 2,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                            NetworkRequestInput(
                                "GET",
                                "https://api.test/orders",
                                headers = mapOf("Authorization" to "Bearer header-secret"),
                            ),
                            NetworkResponseInput(200),
                        ),
                ),
            )
            store.record(
                NetworkTransaction(
                    id = "transaction-2",
                    startedAtEpochMs = 3,
                    completedAtEpochMs = 4,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                            NetworkRequestInput("GET", "https://api.test/not-selected"),
                            NetworkResponseInput(200),
                        ),
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = store
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val unauthorized =
                client.post("/api/v1/network/har") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("id=transaction-1")
                }
            val csrfDenied =
                client.post("/api/v1/network/har") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("id=transaction-1")
                }
            val har =
                client.post("/api/v1/network/har") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("id=transaction-1")
                }
            val postman =
                client.post("/api/v1/network/postman") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("id=transaction-1")
                }

            assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
            assertEquals(HttpStatusCode.Forbidden, csrfDenied.status)
            assertEquals(HttpStatusCode.OK, har.status)
            assertEquals("false", har.headers["X-DevConsole-Export-Truncated"])
            assertTrue(har.bodyAsText().contains("/orders"))
            assertFalse(har.bodyAsText().contains("/not-selected"))
            assertFalse(har.bodyAsText().contains("header-secret"))
            assertEquals(HttpStatusCode.OK, postman.status)
            assertTrue(postman.bodyAsText().contains("/orders"))
            assertFalse(postman.bodyAsText().contains("/not-selected"))
            assertFalse(postman.bodyAsText().contains("header-secret"))
        }

    @Test
    fun `network postman export requires auth and deduplicates identical repeated requests`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            repeat(3) { index ->
                store.record(
                    NetworkTransaction(
                        id = "poll-$index",
                        startedAtEpochMs = index.toLong(),
                        completedAtEpochMs = index.toLong() + 1,
                        capture =
                            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                                NetworkRequestInput("GET", "https://api.test/health"),
                                NetworkResponseInput(200),
                            ),
                    ),
                )
            }
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = store
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val unauthorized = client.get("/api/v1/network/postman") { header(HttpHeaders.Host, "localhost") }
            val postman =
                client.get("/api/v1/network/postman") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
            assertEquals(HttpStatusCode.OK, postman.status)
            assertEquals(1, postman.bodyAsText().occurrencesOf("\"name\":\"GET https://api.test/health\""))
        }

    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    @Test
    fun `network transaction links correlated and time-window timeline events`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val network = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            network.record(
                NetworkTransaction(
                    id = "transaction-related",
                    startedAtEpochMs = 100,
                    completedAtEpochMs = 120,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                            NetworkRequestInput("GET", "https://api.test/orders", correlationId = "corr-1"),
                            NetworkResponseInput(200),
                        ),
                ),
            )
            val timeline =
                InMemoryTimeline(
                    listOf(
                        StoredEvent("nearby", "session", 1, "system", "nearby", 105, 1, 1, "nearby"),
                        StoredEvent(
                            "correlated",
                            "session",
                            2,
                            "system",
                            "correlated",
                            5_000,
                            2,
                            1,
                            "correlated",
                            correlationId = "corr-1",
                        ),
                        StoredEvent("unrelated", "session", 3, "system", "unrelated", 5_000, 3, 1, "unrelated"),
                    ),
                    CursorCodec("timeline-cursor-key".encodeToByteArray()),
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = network
                    this.timeline = timeline
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/network/transactions/transaction-related/related-events?windowMs=50") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"nearby\""))
            assertTrue(response.bodyAsText().contains("\"correlated\""))
            assertFalse(response.bodyAsText().contains("\"unrelated\""))
        }

    @Test
    fun `authenticated browser reads an overview combining app mocks health and network status distribution`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            store.record(
                NetworkTransaction(
                    id = "transaction-1",
                    startedAtEpochMs = 1,
                    completedAtEpochMs = 2,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                            NetworkRequestInput("GET", "https://api.test/orders"),
                            NetworkResponseInput(200),
                        ),
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = store
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val unauthenticated = client.get("/api/v1/overview") { header(HttpHeaders.Host, "localhost") }
            val overview =
                client.get("/api/v1/overview") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
            assertEquals(HttpStatusCode.OK, overview.status)
            val body = overview.bodyAsText()
            assertTrue(body.contains("\"app\""))
            assertTrue(body.contains("\"mocks\""))
            assertTrue(body.contains("\"sdkHealth\""))
            assertTrue(body.contains("\"networkStatusDistribution\":{\"2xx\":1}"))
        }

    @Test
    fun `authenticated browser can inspect websocket connections and messages`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val sockets = InMemorySocketStore()
            sockets.open(SocketConnection("socket-1", "wss://api.test/socket", 1))
            sockets.appendLifecycle(SocketLifecycleEvent("socket-1", SocketLifecycleType.OPENED, 1))
            sockets.append(
                SocketMessage("socket-1", SocketDirection.RECEIVED, 2, SocketPayload.Text("<redacted>"))
                    .withMetadata(SocketMessageMetadata(SocketFrameType.TEXT, SocketTextFormat.JSON)),
            )
            sockets.append(SocketMessage("socket-1", SocketDirection.SENT, 20, SocketPayload.Text("ignore")))
            sockets.transition(
                "socket-1",
                io.devconsole.socket.SocketConnectionState.FAILED,
                30,
                error = "boom",
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    socketStore = sockets
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val connections =
                client.get("/api/v1/websockets/connections") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val detail =
                client.get("/api/v1/websockets/connections/socket-1") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val messages =
                client.get(
                    "/api/v1/websockets/messages?connectionId=socket-1&direction=RECEIVED&frameType=TEXT" +
                        "&from=1&to=3&query=redacted&error=true",
                ) {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, connections.status)
            assertTrue(connections.bodyAsText().contains("socket-1"))
            assertEquals(HttpStatusCode.OK, detail.status)
            assertTrue(detail.bodyAsText().contains("wss://api.test/socket"))
            assertTrue(detail.bodyAsText().contains("\"type\":\"OPENED\""))
            assertEquals(HttpStatusCode.OK, messages.status)
            assertTrue(messages.bodyAsText().contains("<redacted>"))
            assertFalse(messages.bodyAsText().contains("ignore"))
            assertTrue(messages.bodyAsText().contains("\"frameType\":\"TEXT\""))
            assertTrue(messages.bodyAsText().contains("\"textFormat\":\"JSON\""))
        }

    @Test
    fun `meta reports every capture category enabled by default`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = client.exchangeSession(sessions, sessionCodes)

            val metadata =
                client.get("/api/v1/meta") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, metadata.status)
            assertTrue(
                metadata.bodyAsText().contains(
                    "\"captureCategories\":[\"network\",\"socket\",\"mqtt\",\"push\",\"logs\",\"crashes\"," +
                        "\"state\",\"inspection\",\"mocks\"]",
                ),
            )
        }

    @Test
    fun `a capture category absent from metadata gates its routes with 403 while other categories stay reachable`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    metadata = ServerMetadata(captureCategories = listOf("socket", "mqtt"))
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val network =
                client.get("/api/v1/network/transactions") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val sockets =
                client.get("/api/v1/websockets/connections") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.Forbidden, network.status)
            assertTrue(network.bodyAsText().contains("\"code\":\"CATEGORY_DISABLED\""))
            assertEquals(HttpStatusCode.OK, sockets.status)
        }

    @Test
    fun `a stored mqtt connection and message serialize protocol topic and qos`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val sockets = InMemorySocketStore()
            sockets.open(
                SocketConnection(
                    id = "mqtt-1",
                    url = "tcp://broker.test:1883",
                    openedAtEpochMs = 1,
                    protocol = SocketProtocol.MQTT,
                ),
            )
            sockets.append(
                SocketMessage(
                    connectionId = "mqtt-1",
                    direction = SocketDirection.RECEIVED,
                    timestampEpochMs = 2,
                    payload = SocketPayload.Text("hello"),
                    contentType = MqttFrameMetadata.format("devconsole/demo", qos = 1, retained = false),
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    socketStore = sockets
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val connections =
                client.get("/api/v1/websockets/connections") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val messages =
                client.get("/api/v1/websockets/messages?connectionId=mqtt-1") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, connections.status)
            assertTrue(connections.bodyAsText().contains("\"protocol\":\"mqtt\""))
            assertEquals(HttpStatusCode.OK, messages.status)
            assertTrue(messages.bodyAsText().contains("\"topic\":\"devconsole/demo\""))
            assertTrue(messages.bodyAsText().contains("\"qos\":1"))
        }

    @Test
    fun `protocol query param filters websocket connections list to exclude mqtt`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val sockets = InMemorySocketStore()
            sockets.open(SocketConnection("ws-1", "wss://api.test/socket", 1))
            sockets.open(
                SocketConnection(
                    id = "mqtt-1",
                    url = "tcp://broker.test:1883",
                    openedAtEpochMs = 2,
                    protocol = SocketProtocol.MQTT,
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    socketStore = sockets
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val filtered =
                client.get("/api/v1/websockets/connections?protocol=websocket") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, filtered.status)
            val body = filtered.bodyAsText()
            assertTrue(body.contains("\"id\":\"ws-1\""))
            assertFalse(body.contains("\"id\":\"mqtt-1\""))
        }

    @Test
    fun `mock kill switch requires control csrf and exposes status`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val mocks = MockEngine(listOf(MockRule("orders", 1, path = "/orders")))
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    mockEngine = mocks
                    commandAuditLog = audit
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val before =
                client.get("/api/v1/mocks") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val disable =
                client.post("/api/v1/mocks/disable-all") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                }
            assertTrue(before.bodyAsText().contains("\"enabled\":true"))
            assertEquals(HttpStatusCode.OK, disable.status)
            assertTrue(!mocks.isEnabled())
            assertEquals("mock.disable_all", audit.events().single().commandType)
        }

    @Test
    fun `mock rules support create list and delete when editable`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    mockEngine = MockEngine(emptyList())
                    mocksEditable = true
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val created =
                client.post("/api/v1/mocks/rules") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("id=orders&priority=2&path=%2Forders&status=201&body=mocked")
                }
            val listed =
                client.get("/api/v1/mocks/rules") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val deleted =
                client.delete("/api/v1/mocks/rules/orders") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                }

            assertEquals(HttpStatusCode.Created, created.status)
            assertTrue(listed.bodyAsText().contains("\"id\":\"orders\""))
            assertEquals(HttpStatusCode.OK, deleted.status)
        }

    @Test
    fun `mock rule create round-trips method scheme host headers delay and hit stats losslessly`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val mocks = MockEngine(emptyList())
            application {
                devConsoleModule(sessions, sessionCodes) {
                    mockEngine = mocks
                    mocksEditable = true
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val created =
                client.post("/api/v1/mocks/rules") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    val headersValue =
                        java.net.URLEncoder.encode("X-Trace: abc\nContent-Type: application/json", "UTF-8")
                    val sourceSnapshotValue = java.net.URLEncoder.encode("{\"amount\":10}", "UTF-8")
                    setBody(
                        "id=orders&priority=2&method=post&scheme=HTTPS&host=api.example.test&path=%2Forders&" +
                            "status=201&body=mocked&delayMs=250&headers=$headersValue&" +
                            "sourceBodySnapshot=$sourceSnapshotValue",
                    )
                }
            assertEquals(HttpStatusCode.Created, created.status)

            mocks.decide(io.devconsole.mocks.MockRequest("POST", "https", "api.example.test", "/orders"))

            val listed =
                client.get("/api/v1/mocks/rules") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val body = listed.bodyAsText()

            assertTrue(body.contains("\"method\":\"POST\""))
            assertTrue(body.contains("\"scheme\":\"https\""))
            assertTrue(body.contains("\"host\":\"api.example.test\""))
            assertTrue(body.contains("\"body\":\"mocked\""))
            assertTrue(body.contains("\"bodyTruncated\":false"))
            assertTrue(body.contains("\"X-Trace\":\"abc\""))
            assertTrue(body.contains("\"Content-Type\":\"application/json\""))
            assertTrue(body.contains("\"delayMs\":250"))
            assertTrue(body.contains("\"hitCount\":1"))
            assertFalse(body.contains("\"lastHitEpochMs\":null"))
            assertTrue(body.contains("\"sourceBodySnapshot\":\"{\\\"amount\\\":10}\""))
            assertTrue(body.contains("\"sourceBodySnapshotTruncated\":false"))
        }

    @Test
    fun `mock rule create without sourceBodySnapshot serializes it as null`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val mocks = MockEngine(emptyList())
            application {
                devConsoleModule(sessions, sessionCodes) {
                    mockEngine = mocks
                    mocksEditable = true
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val created =
                client.post("/api/v1/mocks/rules") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("id=orders&priority=2&status=201&body=mocked")
                }
            assertEquals(HttpStatusCode.Created, created.status)

            val listed =
                client.get("/api/v1/mocks/rules") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val body = listed.bodyAsText()

            assertTrue(body.contains("\"sourceBodySnapshot\":null"))
            assertTrue(body.contains("\"sourceBodySnapshotTruncated\":false"))
        }

    @Test
    fun `updating a rule without sourceBodySnapshot preserves its existing snapshot`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val mocks = MockEngine(emptyList())
            application {
                devConsoleModule(sessions, sessionCodes) {
                    mockEngine = mocks
                    mocksEditable = true
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            suspend fun post(body: String) =
                client.post("/api/v1/mocks/rules") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody(body)
                }

            val sourceSnapshotValue = java.net.URLEncoder.encode("{\"amount\":10}", "UTF-8")
            val created =
                post("id=orders&priority=2&status=200&body=mocked&sourceBodySnapshot=$sourceSnapshotValue")
            assertEquals(HttpStatusCode.Created, created.status)

            // Simulates the dashboard editing this rule (e.g. changing only the status code) after
            // its snapshot came back truncated -- it omits sourceBodySnapshot entirely rather than
            // round-tripping a truncated, unparseable prefix. The engine's full in-memory snapshot
            // must survive the update untouched.
            val updated = post("id=orders&priority=2&status=201&body=mocked")
            assertEquals(HttpStatusCode.Created, updated.status)

            val listed =
                client.get("/api/v1/mocks/rules") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val body = listed.bodyAsText()

            assertTrue(body.contains("\"statusCode\":201"))
            assertTrue(body.contains("\"sourceBodySnapshot\":\"{\\\"amount\\\":10}\""))
        }

    @Test
    fun `mock rule bodies over the 64KB cap serialize truncated with the flag set`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val bigBody = "x".repeat(64 * 1024 + 512)
            val mocks =
                MockEngine(
                    listOf(
                        io.devconsole.mocks.MockRule(
                            id = "big-body",
                            priority = 0,
                            action =
                                io.devconsole.mocks.MockAction
                                    .StaticResponse(200, bigBody),
                        ),
                    ),
                )
            application {
                devConsoleModule(sessions, sessionCodes) { mockEngine = mocks }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val listed =
                client.get("/api/v1/mocks/rules") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val body = listed.bodyAsText()

            assertTrue(body.contains("\"bodyTruncated\":true"))
            val serialized = Regex("\"body\":\"(x+)\"").find(body)
            assertTrue(serialized != null && serialized.groupValues[1].length <= 64 * 1024)
        }

    @Test
    fun `mock rule headers with control or non-ascii characters are rejected`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    mockEngine = MockEngine(emptyList())
                    mocksEditable = true
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            suspend fun postWithHeaders(raw: String) =
                client.post("/api/v1/mocks/rules") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody(
                        "id=bad-header&priority=0&path=%2Fx&status=200&headers=" +
                            java.net.URLEncoder.encode(raw, "UTF-8"),
                    )
                }

            // Embedded CR would crash OkHttp's Headers.Builder on the host app's request thread.
            assertEquals(HttpStatusCode.BadRequest, postWithHeaders("X-Trace: bad\rvalue").status)
            assertEquals(HttpStatusCode.BadRequest, postWithHeaders("X-Émoji: value").status)
            assertEquals(HttpStatusCode.Created, postWithHeaders("X-Ok: tab\tand printable").status)
        }

    @Test
    fun `mock rule create rejects an out-of-range delay and a malformed header line`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    mockEngine = MockEngine(emptyList())
                    mocksEditable = true
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val badDelay =
                client.post("/api/v1/mocks/rules") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("id=orders&priority=2&path=%2Forders&status=201&body=mocked&delayMs=99999")
                }
            val badHeader =
                client.post("/api/v1/mocks/rules") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("id=orders2&priority=2&path=%2Forders&status=201&body=mocked&headers=not-a-header-line")
                }

            assertEquals(HttpStatusCode.BadRequest, badDelay.status)
            assertEquals(HttpStatusCode.BadRequest, badHeader.status)
        }

    @Test
    fun `mock conflicts route reports overlapping rules to an authenticated browser`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val mocks =
                MockEngine(
                    listOf(
                        MockRule(
                            id = "orders-a",
                            priority = 0,
                            method = "GET",
                            host = "api.example.test",
                            path = "/orders",
                        ),
                        MockRule(
                            id = "orders-b",
                            priority = 1,
                            method = "GET",
                            host = "api.example.test",
                            path = "/orders",
                        ),
                    ),
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    mockEngine = mocks
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val conflicts =
                client.get("/api/v1/mocks/conflicts") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, conflicts.status)
            assertTrue(conflicts.bodyAsText().contains("orders-a"))
            assertTrue(conflicts.bodyAsText().contains("orders-b"))
        }

    @Test
    fun `feature flag override requires control csrf`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val flags =
                SessionFeatureFlags(
                    listOf(
                        FeatureFlag("new_ui", false, description = "Use the new checkout", source = "remote-config"),
                    ),
                )
            val audit = InMemoryCommandAuditLog()
            val testTimeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-test-key".encodeToByteArray()))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    featureFlags = flags
                    featureFlagsEditable = true
                    commandAuditLog = audit
                    timeline = testTimeline
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val update =
                client.post("/api/v1/flags/new_ui") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    setBody("true")
                }
            assertEquals(HttpStatusCode.OK, update.status)
            assertTrue(flags.booleanValue("new_ui"))
            val auditEvent = audit.events().single()
            assertEquals("flag.override", auditEvent.commandType)
            // A non-sensitively-named flag records its real prior/new value, not "<redacted>".
            assertEquals("false", auditEvent.parameters.getValue("before"))
            assertEquals("true", auditEvent.parameters.getValue("after"))
            val flagEvent = (testTimeline.page(TimelineQuery()).let { it as TimelinePage.Success }).events.single()
            assertEquals("flag.override", flagEvent.type)
            assertTrue(flagEvent.payloadJson.orEmpty().contains("\"before\":\"false\""))
            assertTrue(flagEvent.payloadJson.orEmpty().contains("\"after\":\"true\""))
            assertFalse(flagEvent.payloadJson.orEmpty().contains("<redacted>"))

            val listing =
                client.get("/api/v1/flags") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            assertTrue(listing.bodyAsText().contains("\"source\":\"remote-config\""))
            assertTrue(listing.bodyAsText().contains("\"type\":\"BOOLEAN\""))
        }

    @Test
    fun `authenticated browser can list and inspect typed lazy state`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val states =
                StateRegistry().apply {
                    register(
                        stateProvider("session") {
                            StateSnapshot(
                                mapOf(
                                    "signedIn" to StateValue.BooleanValue(true),
                                    "token" to StateValue.Redacted,
                                    "avatar" to StateValue.BinaryMetadata(128, "image/png"),
                                ),
                            )
                        },
                    )
                }
            application {
                devConsoleModule(sessions, sessionCodes) {
                    stateRegistry = states
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val listing =
                client.get("/api/v1/state") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val snapshot =
                client.get("/api/v1/state/session") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, listing.status)
            assertTrue(listing.bodyAsText().contains("session"))
            assertEquals(HttpStatusCode.OK, snapshot.status)
            assertTrue(snapshot.bodyAsText().contains("\"signedIn\":true"))
            assertTrue(snapshot.bodyAsText().contains("\"kind\":\"redacted\""))
            assertTrue(snapshot.bodyAsText().contains("\"byteLength\":128"))
        }

    @Test
    fun `state discovery surfaces each provider's mutator catalogue, empty for a read-only provider`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val states =
                StateRegistry().apply {
                    register(stateProvider("readonly") { StateSnapshot(emptyMap()) })
                    register(
                        object : io.devconsole.state.StateProvider {
                            override val id = "cache"

                            override fun snapshot() = StateSnapshot(emptyMap())

                            override val mutators =
                                listOf(
                                    StateMutator("clear", inputSchema = "{\"type\":\"object\",\"properties\":{}}") {
                                        StateMutationResult.Success(StateSnapshot(emptyMap()))
                                    },
                                )
                        },
                    )
                }
            application { devConsoleModule(sessions, sessionCodes) { stateRegistry = states } }
            val session = client.exchangeSession(sessions, sessionCodes)

            val listing =
                client.get("/api/v1/state") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val readonlyDetail =
                client.get("/api/v1/state/readonly") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val cacheDetail =
                client.get("/api/v1/state/cache") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, listing.status)
            assertTrue(listing.bodyAsText().contains("\"id\":\"readonly\",\"mutators\":[]"))
            assertTrue(listing.bodyAsText().contains("\"id\":\"cache\",\"mutators\":[{\"id\":\"clear\""))
            assertEquals(HttpStatusCode.OK, readonlyDetail.status)
            assertTrue(readonlyDetail.bodyAsText().contains("\"mutators\":[]"))
            assertEquals(HttpStatusCode.OK, cacheDetail.status)
            assertTrue(
                cacheDetail.bodyAsText().contains(
                    "\"mutators\":[{\"id\":\"clear\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]",
                ),
            )
        }

    @Test
    fun `state mutations require explicit host enablement and control csrf`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val states =
                StateRegistry().apply {
                    register(
                        object : io.devconsole.state.StateProvider {
                            override val id = "cache"

                            override fun snapshot() = StateSnapshot(emptyMap())

                            override val mutators =
                                listOf(
                                    StateMutator("clear") {
                                        StateMutationResult.Success(
                                            StateSnapshot(
                                                mapOf(
                                                    "cleared" to StateValue.BooleanValue(true),
                                                ),
                                            ),
                                        )
                                    },
                                )
                        },
                    )
                }
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    stateRegistry = states
                    stateMutationsEnabled = true
                    commandAuditLog = audit
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val result =
                client.post("/api/v1/state/cache/mutations/clear") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("{}")
                }

            assertEquals(HttpStatusCode.OK, result.status)
            assertTrue(result.bodyAsText().contains("\"cleared\":true"))
            assertEquals("state.mutation", audit.events().single().commandType)
        }

    @Test
    fun `composer execution requires control csrf and uses the isolated executor`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            var submittedUrl = ""
            val executor =
                ComposerExecutor(
                    ComposerTransport { request ->
                        submittedUrl = request.url
                        ComposerResponse(statusCode = 202, body = "accepted")
                    },
                )
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.example.test")
                    composerExecutor = executor
                    commandAuditLog = audit
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val result =
                client.post("/api/v1/composer/execute") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("method=GET&url=https%3A%2F%2Fapi.example.test%2Forders")
                }

            assertEquals(HttpStatusCode.OK, result.status)
            assertEquals("https://api.example.test/orders", submittedUrl)
            assertTrue(result.bodyAsText().contains("\"statusCode\":202"))
            assertEquals("composer.execute", audit.events().single().commandType)
        }

    @Test
    fun `composer parses full request model and emits correlated network and timeline records`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val network = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
            var submitted: io.devconsole.composer.ResolvedComposerRequest? = null
            val executor =
                ComposerExecutor(
                    ComposerTransport { request ->
                        submitted = request
                        ComposerResponse(
                            statusCode = 201,
                            headers = mapOf("Content-Type" to "application/json"),
                            body = "{\"created\":true}",
                            durationMs = 17,
                        )
                    },
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.example.test")
                    composerExecutor = executor
                    networkTransactions = network
                    this.timeline = timeline
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val result =
                client.post("/api/v1/composer/execute") {
                    controlRequest(session)
                    setBody(
                        "method=POST&url=https%3A%2F%2Fapi.example.test%2Forders" +
                            "&query=tenant%3D%24%7Btenant%7D&header=X-Trace%3A+trace-value" +
                            "&bodyType=JSON&body=%7B%22name%22%3A%22Ada%22%7D" +
                            "&timeoutMs=1234&followRedirects=false&secretVariable=tenant%3Dacme",
                    )
                }

            assertEquals(HttpStatusCode.OK, result.status)
            assertEquals("https://api.example.test/orders?tenant=acme", submitted!!.url)
            assertEquals("trace-value", submitted!!.headers["X-Trace"])
            assertEquals(io.devconsole.composer.ComposerBodyType.JSON, submitted!!.bodyType)
            assertEquals(1_234, submitted!!.timeoutMs)
            assertFalse(submitted!!.followRedirects)
            val captured = network.page(io.devconsole.network.NetworkTransactionQuery()).transactions.single()
            val event = (timeline.page(TimelineQuery()) as TimelinePage.Success).events.single()
            assertEquals("composer", captured.capture.request.pluginId)
            assertEquals("composer", captured.capture.request.metadata.tags["source"])
            assertFalse(
                captured.capture.request.url.display
                    .contains("acme"),
            )
            assertEquals(captured.capture.request.correlationId, event.correlationId)
            assertEquals("composer", event.pluginId)
            assertEquals("http.response.completed", event.type)
            assertTrue(result.bodyAsText().contains("\"correlationId\":\"${event.correlationId}\""))
        }

    @Test
    fun `composer rejects a redirect outside the allowlist before dispatching it`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            var dispatchCount = 0
            val executor =
                ComposerExecutor(
                    ComposerTransport {
                        dispatchCount += 1
                        ComposerResponse(
                            statusCode = 302,
                            headers = mapOf("Location" to "http://169.254.169.254/latest/meta-data"),
                        )
                    },
                )
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.example.test")
                    composerExecutor = executor
                    commandAuditLog = audit
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val result =
                client.post("/api/v1/composer/execute") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("method=GET&url=https%3A%2F%2Fapi.example.test%2Fredirect")
                }

            assertEquals(HttpStatusCode.Forbidden, result.status)
            assertTrue(result.bodyAsText().contains("COMPOSER_HOST_REJECTED"))
            assertEquals(1, dispatchCount)
            assertEquals("composer.execute", audit.events().single().commandType)
        }

    @Test
    fun `network resend requires a bearer session and a valid csrf token`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) { composerEnabled = true }
            }
            val noAuth =
                client.post("/api/v1/network/transactions/any/resend") {
                    header(HttpHeaders.Host, "localhost")
                }
            assertEquals(HttpStatusCode.Unauthorized, noAuth.status)
            assertTrue(noAuth.bodyAsText().contains("AUTH_REQUIRED"))

            val session = client.exchangeSession(sessions, sessionCodes)
            val badCsrf =
                client.post("/api/v1/network/transactions/any/resend") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", "not-the-csrf-token")
                }
            assertEquals(HttpStatusCode.Forbidden, badCsrf.status)
            assertTrue(badCsrf.bodyAsText().contains("CSRF_INVALID"))
        }

    @Test
    fun `network resend is gated by the same composer capability composer execute uses`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            store.record(
                NetworkTransaction(
                    id = "resend-1",
                    startedAtEpochMs = 1,
                    completedAtEpochMs = 2,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                            NetworkRequestInput("GET", "https://api.example.test/orders"),
                            NetworkResponseInput(200),
                        ),
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    composerEnabled = false
                    networkTransactions = store
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val result =
                client.post("/api/v1/network/transactions/resend-1/resend") {
                    controlRequest(session)
                }

            assertEquals(HttpStatusCode.NotFound, result.status)
            assertTrue(result.bodyAsText().contains("COMPOSER_DISABLED"))
        }

    @Test
    fun `network resend rejects a captured host outside the composer allowlist`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            store.record(
                NetworkTransaction(
                    id = "resend-2",
                    startedAtEpochMs = 1,
                    completedAtEpochMs = 2,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                            NetworkRequestInput("GET", "https://not-allowed.test/orders"),
                            NetworkResponseInput(200),
                        ),
                ),
            )
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.example.test")
                    networkTransactions = store
                    commandAuditLog = audit
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val result =
                client.post("/api/v1/network/transactions/resend-2/resend") {
                    controlRequest(session)
                }

            assertEquals(HttpStatusCode.Forbidden, result.status)
            assertTrue(result.bodyAsText().contains("COMPOSER_HOST_REJECTED"))
            assertEquals("network.resend", audit.events().single().commandType)
        }

    @Test
    @Suppress("LongMethod") // One linear E2E: seed capture, execute resend, assert transport effects.
    fun `network resend re-executes the captured request through the composer transport`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            store.record(
                NetworkTransaction(
                    id = "resend-3",
                    startedAtEpochMs = 1,
                    completedAtEpochMs = 2,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                            NetworkRequestInput(
                                "POST",
                                "https://api.example.test/orders",
                                headers = mapOf("X-Trace" to "trace-value"),
                                body = "{\"qty\":1}".encodeToByteArray(),
                                contentType = "application/json",
                            ),
                            NetworkResponseInput(200),
                        ),
                ),
            )
            var submitted: io.devconsole.composer.ResolvedComposerRequest? = null
            val executor =
                ComposerExecutor(
                    ComposerTransport { request ->
                        submitted = request
                        ComposerResponse(statusCode = 201, body = "{\"resent\":true}", durationMs = 5)
                    },
                )
            val timeline = InMemoryTimeline(emptyList(), CursorCodec("timeline-cursor-key".encodeToByteArray()))
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.example.test")
                    composerExecutor = executor
                    networkTransactions = store
                    this.timeline = timeline
                    commandAuditLog = audit
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val result =
                client.post("/api/v1/network/transactions/resend-3/resend") {
                    controlRequest(session)
                }

            assertEquals(HttpStatusCode.OK, result.status)
            assertEquals("https://api.example.test/orders", submitted!!.url)
            assertEquals("trace-value", submitted!!.headers["X-Trace"])
            assertTrue(result.bodyAsText().contains("\"statusCode\":201"))
            val resent =
                store
                    .page(io.devconsole.network.NetworkTransactionQuery())
                    .transactions
                    .single { it.id != "resend-3" }
            assertEquals("composer", resent.capture.request.pluginId)
            assertEquals("composer", resent.capture.request.metadata.tags["source"])
            assertEquals("network.resend", audit.events().single().commandType)
        }

    @Test
    fun `every authenticated failed control command creates one audit record`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    commandAuditLog = audit
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.example.test")
                    composerExecutor =
                        ComposerExecutor(
                            ComposerTransport { error("disposable transport failure") },
                        )
                    stateMutationsEnabled = true
                    mocksEditable = true
                    featureFlagsEditable = true
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes, "Chrome")

            val responses =
                listOf(
                    client.delete("/api/v1/auth/principals/missing") {
                        controlRequest(session)
                    },
                    client.delete("/api/v1/mocks/rules/missing") {
                        controlRequest(session)
                    },
                    client.post("/api/v1/flags/missing") {
                        controlRequest(session)
                        setBody("true")
                    },
                    client.post("/api/v1/composer/execute") {
                        controlRequest(session)
                        setBody("method=GET&url=https%3A%2F%2Fapi.example.test%2Ffailure")
                    },
                    client.delete("/api/v1/composer/collections/missing") {
                        controlRequest(session)
                    },
                    client.post("/api/v1/push/simulate") {
                        controlRequest(session)
                        setBody("provider=local")
                    },
                    client.post("/api/v1/state/missing/mutations/reset") {
                        controlRequest(session)
                        setBody("{}")
                    },
                )

            assertEquals(
                listOf(
                    HttpStatusCode.NotFound,
                    HttpStatusCode.NotFound,
                    HttpStatusCode.BadRequest,
                    HttpStatusCode.BadGateway,
                    HttpStatusCode.NotFound,
                    HttpStatusCode.Conflict,
                    HttpStatusCode.NotFound,
                ),
                responses.map { it.status },
            )
            assertEquals(
                listOf(
                    "session.revoke",
                    "mock.rule.delete",
                    "flag.override",
                    "composer.execute",
                    "composer.collection.delete",
                    "push.simulate",
                    "state.mutation",
                ),
                audit.events().map { it.commandType },
            )
            assertTrue(audit.events().all { it.result != io.devconsole.server.api.CommandAuditResult.SUCCESS })
        }

    @Test
    fun `csrf rejected control command creates one audit record`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            application { devConsoleModule(sessions, sessionCodes) { commandAuditLog = audit } }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/session/stop") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals("session.stop", audit.events().single().commandType)
            assertEquals(io.devconsole.server.api.CommandAuditResult.REJECTED, audit.events().single().result)
        }

    @Test
    fun `rate limited control command creates an audit record without auditing reads`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    commandAuditLog = audit
                    composerEnabled = true
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            repeat(20) {
                assertEquals(
                    HttpStatusCode.Forbidden,
                    client
                        .post("/api/v1/composer/execute") {
                            controlRequest(session)
                            setBody("method=GET&url=https%3A%2F%2Fapi.example.test")
                        }.status,
                )
            }
            val limited =
                client.post("/api/v1/composer/execute") {
                    controlRequest(session)
                    setBody("method=GET&url=https%3A%2F%2Fapi.example.test")
                }
            repeat(120) {
                assertEquals(
                    HttpStatusCode.OK,
                    client
                        .get("/api/v1/events") {
                            header(HttpHeaders.Host, "localhost")
                            header(HttpHeaders.Authorization, "Bearer ${session.token}")
                        }.status,
                )
            }
            val limitedRead =
                client.get("/api/v1/events") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals(HttpStatusCode.TooManyRequests, limitedRead.status)
            assertEquals(21, audit.events().size)
            assertTrue(audit.events().all { it.commandType == "composer.execute" })
            assertTrue(audit.events().all { it.result == io.devconsole.server.api.CommandAuditResult.REJECTED })
        }

    @Test
    fun `composer import and ephemeral collections require control csrf`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) { composerEnabled = true } }
            val session = client.exchangeSession(sessions, sessionCodes)

            val imported =
                client.post("/api/v1/composer/import") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    setBody("curl -X POST --data '{}' https://api.test/orders")
                }
            val saved =
                client.post("/api/v1/composer/collections") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("name=smoke&curl=curl%20https%3A%2F%2Fapi.test%2Forders")
                }
            val listed =
                client.get("/api/v1/composer/collections") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, imported.status)
            assertTrue(imported.bodyAsText().contains("\"method\":\"POST\""))
            assertEquals(HttpStatusCode.Created, saved.status)
            assertTrue(listed.bodyAsText().contains("\"name\":\"smoke\""))
        }

    @Test
    fun `authenticated browser can inspect redacted push lifecycle events`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val pushes =
                InMemoryPushStore().apply {
                    append(
                        PushEvent(
                            "fcm",
                            mapOf("access_token" to "<redacted>"),
                            messageId = "m-1",
                            lifecycle = PushLifecycle.OPENED,
                            simulated = true,
                        ),
                    )
                }
            application {
                devConsoleModule(sessions, sessionCodes) {
                    pushStore = pushes
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val result =
                client.get("/api/v1/push/events") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, result.status)
            assertTrue(result.bodyAsText().contains("\"messageId\":\"m-1\""))
            assertTrue(result.bodyAsText().contains("\"simulated\":true"))
            assertTrue(result.bodyAsText().contains("<redacted>"))
        }

    @Test
    fun `push simulation requires control csrf and is explicitly local`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = InMemoryPushStore()
            val simulator =
                PushSimulator(
                    PushSimulationCallback { PushLifecycle.DISPLAYED },
                    PushRecorder(RedactionEngine(RedactionPolicy.default()), store),
                )
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    pushSimulator = simulator
                    commandAuditLog = audit
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val result =
                client.post("/api/v1/push/simulate") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("provider=local&messageId=preview&data.order=42")
                }

            assertEquals(HttpStatusCode.OK, result.status)
            assertTrue(result.bodyAsText().contains("\"simulated\":true"))
            assertEquals(PushLifecycle.DISPLAYED, store.events().single().lifecycle)
            assertEquals("push.simulate", audit.events().single().commandType)
        }

    @Test
    fun `KtorLocalServerEngine prevents double start and handles lifecycle safely`() {
        val authority = SessionAuthority()
        val engine =
            KtorLocalServerEngine(
                sessionAuthority = authority,
                metadata = { ServerMetadata() },
                sdkHealth = { SdkHealthSnapshot() },
            )

        kotlinx.coroutines.runBlocking {
            val startResult = engine.start(StartRequest(BindingMode.LOOPBACK, 8400..8419))
            assertTrue(startResult is ServerStartResult.Started)

            val doubleStartResult = engine.start(StartRequest(BindingMode.LOOPBACK, 8400..8419))
            assertTrue(doubleStartResult is ServerStartResult.Failed)

            engine.stop()

            val restartResult = engine.start(StartRequest(BindingMode.LOOPBACK, 8400..8419))
            assertTrue(restartResult is ServerStartResult.Started)

            engine.stop()
        }
    }

    @Test
    fun `escapes json correctly when special characters, quotes, tabs, newlines, and control chars are present`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes)
            }
            val session =
                client.exchangeSession(
                    sessions,
                    sessionCodes,
                    "Browser with \"quotes\", \t tabs, \n newlines,  control & 🎉 unicode",
                )

            val result =
                client.get("/api/v1/auth/principals") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, result.status)
            val text = result.bodyAsText()
            assertTrue(text.contains("Browser with \\\"quotes\\\""))
            assertTrue(text.contains("\\t tabs"))
            assertTrue(text.contains("\\n newlines"))
            assertTrue(text.contains("\\u0007 control"))
        }

    @Test
    fun `origin rejection returns single 403 Forbidden without pipeline exception`() =
        testApplication {
            application {
                devConsoleModule(SessionAuthority()) {
                    allowedHosts = setOf("localhost")
                }
            }

            val result =
                client.get("/api/v1/meta") {
                    header(HttpHeaders.Host, "unauthorized-host.org")
                }

            assertEquals(HttpStatusCode.Forbidden, result.status)
            assertTrue(result.bodyAsText().contains("ORIGIN_REJECTED"))
        }

    @Test
    fun `emits security headers on dashboard and api responses`() =
        testApplication {
            application {
                devConsoleModule(SessionAuthority())
            }

            val result =
                client.get("/") {
                    header(HttpHeaders.Host, "localhost")
                }

            assertEquals(HttpStatusCode.OK, result.status)
            assertEquals("nosniff", result.headers["X-Content-Type-Options"])
            assertEquals("DENY", result.headers["X-Frame-Options"])
            assertEquals("0", result.headers["X-XSS-Protection"])
            val csp = result.headers["Content-Security-Policy"]!!
            assertTrue(csp.contains("default-src 'self'"))
            // The dashboard script now ships as an external /assets/dashboard.js file with zero
            // inline event handlers, so script-src was tightened to drop 'unsafe-inline'.
            assertTrue(csp.contains("script-src 'self';"))
            assertFalse(csp.contains("script-src 'self' 'unsafe-inline'"))
            // base-uri 'self' blocks an injected <base> tag from reparenting path-absolute
            // fetches/scripts to an attacker origin -- the channel the in-memory Bearer token would
            // otherwise leave through. frame-ancestors 'none' is the CSP3 replacement for the
            // X-Frame-Options: DENY already asserted above. form-action 'none' is safe: the dashboard
            // has no <form> elements and never submits one natively.
            assertTrue(csp.contains("base-uri 'self'"))
            assertTrue(csp.contains("frame-ancestors 'none'"))
            assertTrue(csp.contains("form-action 'none'"))
            assertEquals("no-store", result.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `content security policy connect-src matches the request's own Host header`() =
        testApplication {
            // connect-src must follow *this request's* Host header, not a fixed bind address --
            // KtorLocalServerEngine deliberately allows both "localhost" (the documented `adb
            // reverse` workflow) and the literal bind address as valid Hosts for the same running
            // server, and dashboard.js's `location.host` (what it actually dials over ws://) is
            // whichever one the browser tab is currently using. Pinning to a fixed bind address
            // instead of the request's Host would silently block the alias that wasn't picked.
            application { devConsoleModule(SessionAuthority()) { allowedHosts = setOf("localhost") } }

            val result = client.get("/") { header(HttpHeaders.Host, "localhost:4321") }

            val csp = result.headers["Content-Security-Policy"]!!
            assertTrue(csp.contains("connect-src 'self' ws://localhost:4321"))
            assertFalse("must not allow a websocket to any host/scheme", csp.contains("ws: wss:"))
        }

    @Test
    fun `content security policy connect-src falls back to self for a request whose host is rejected`() =
        testApplication {
            // A request whose Host fails the allowedHosts check is rejected outright (ORIGIN_REJECTED
            // below) -- its Host value must never be reflected into the CSP on the way there, or an
            // attacker-chosen Host header would land in a response header of the very request that's
            // supposed to be refused.
            application { devConsoleModule(SessionAuthority()) { allowedHosts = setOf("localhost") } }

            val result = client.get("/") { header(HttpHeaders.Host, "evil.example.com:1234") }

            assertEquals(HttpStatusCode.Forbidden, result.status)
            val csp = result.headers["Content-Security-Policy"]!!
            assertTrue(csp.contains("connect-src 'self'"))
            assertFalse("a rejected host must never be reflected into the CSP", csp.contains("evil.example.com"))
        }

    @Test
    fun `a Host header whose name is allowed but whose port suffix is invalid is never reflected into the CSP`() =
        testApplication {
            // Regression test for a CSP directive-injection bug: an earlier version of this check
            // validated only the substring before the first colon, then reflected the *entire* raw
            // Host header into connect-src. "localhost" alone passes that pre-colon check, but the
            // rest of this value is not a numeric port -- if it were reflected verbatim, it would
            // inject a whole extra `frame-ancestors` directive, overriding X-Frame-Options: DENY.
            // Host is a forbidden header for browser fetch/XHR, but this SDK's threat model includes
            // non-browser on-device HTTP clients (curl, etc.) that can set it freely. The response now
            // legitimately always carries its own `frame-ancestors 'none'` directive (see S7), so this
            // asserts the attacker's *value* ('self' https://evil.example.com) never appears rather
            // than asserting the directive name is absent altogether.
            application { devConsoleModule(SessionAuthority()) { allowedHosts = setOf("localhost") } }
            val maliciousHost = "localhost:4321; frame-ancestors 'self' https://evil.example.com"

            val result = client.get("/") { header(HttpHeaders.Host, maliciousHost) }

            val csp = result.headers["Content-Security-Policy"]!!
            assertTrue(
                "the app's own frame-ancestors directive must still be present",
                csp.contains("frame-ancestors 'none'"),
            )
            assertFalse(
                "must never inject the attacker's own frame-ancestors value via the Host header",
                csp.contains("frame-ancestors 'self'"),
            )
            assertFalse("must never reflect the attacker's suffix at all", csp.contains("evil.example.com"))
            assertTrue(
                "an unparseable Host must fall back to the same bare 'self' as any other rejected host",
                csp.contains("connect-src 'self'") && !csp.contains("connect-src 'self' ws:"),
            )
        }

    @Test
    fun `websocket plugin is configured with a ping period and timeout so dead connections are reaped`() =
        testApplication {
            application { devConsoleModule(SessionAuthority()) }
            // The plugin instance is only installed once the application has actually started;
            // issuing any request forces that startup.
            client.get("/health") { header(HttpHeaders.Host, "localhost") }

            val webSockets = application.plugin(ServerWebSockets)

            assertEquals(30_000L, webSockets.pingIntervalMillis)
            assertEquals(60_000L, webSockets.timeoutMillis)
        }

    @Test
    fun `composer routes are absent unless the host opts in`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/composer/execute") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    setBody("method=GET&url=https://api.test/orders")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `session integrity reports overrides and reset returns the session to pristine`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val flags = SessionFeatureFlags(listOf(FeatureFlag("checkout_v2", defaultValue = false)))
            val mocks = MockEngine(emptyList())
            application {
                devConsoleModule(sessions, sessionCodes) {
                    featureFlags = flags
                    mockEngine = mocks
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val clean =
                client.get("/api/v1/session/integrity") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val cleanBody = clean.bodyAsText()
            assertTrue("expected a pristine session, got $cleanBody", "\"pristine\":true" in cleanBody)

            flags.override("checkout_v2", "true")

            val dirty =
                client.get("/api/v1/session/integrity") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val dirtyBody = dirty.bodyAsText()
            assertTrue("expected a dirty session, got $dirtyBody", "\"pristine\":false" in dirtyBody)
            assertTrue("\"checkout_v2\":true" in dirtyBody)

            val reset =
                client.post("/api/v1/session/integrity/reset") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                }
            assertEquals(HttpStatusCode.OK, reset.status)
            assertTrue("\"pristine\":true" in reset.bodyAsText())
            assertFalse(flags.booleanValue("checkout_v2"))
        }

    @Test
    fun `a bug report bundles the timeline, network trail, app info, and active overrides`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val flags = SessionFeatureFlags(listOf(FeatureFlag("checkout_v2", defaultValue = false)))
            flags.override("checkout_v2", "true")
            application {
                devConsoleModule(sessions, sessionCodes) {
                    featureFlags = flags
                    metadata = ServerMetadata(appDisplayName = "Sample", appPackageName = "io.sample")
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/report") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("missing app info: $body", "io.sample" in body)
            assertTrue("missing timeline: $body", "\"timeline\":" in body)
            assertTrue("missing network trail: $body", "\"network\":" in body)
            // The whole point: a report filed with an override live says so.
            assertTrue("missing override set: $body", "\"checkout_v2\":true" in body)
            assertTrue("should not claim pristine: $body", "\"pristine\":false" in body)
        }

    @Test
    fun `scoped zip export requires csrf and returns a re-redacted integrity manifest`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val exportDirectory = Files.createTempDirectory("devconsole-server-export").toFile()
            val timeline =
                InMemoryTimeline(
                    listOf(
                        StoredEvent(
                            "event-1",
                            "runtime-session",
                            1,
                            "system",
                            "system.event",
                            1,
                            1,
                            1,
                            "first",
                        ),
                        StoredEvent(
                            "event-2",
                            "runtime-session",
                            2,
                            "network",
                            "network.request",
                            2,
                            2,
                            1,
                            "Bearer export-secret",
                            attachmentId = "attachment-2",
                        ),
                    ),
                    CursorCodec("export-cursor-secret".encodeToByteArray()),
                )
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    this.timeline = timeline
                    this.exportDirectory = exportDirectory
                    commandAuditLog = audit
                    attachmentReader = { id ->
                        if (id == "attachment-2") "token=attachment-secret".encodeToByteArray() else null
                    }
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val csrfDenied =
                client.post("/api/v1/exports") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("scope=EVENT_IDS&eventId=event-2")
                }
            val response =
                client.post("/api/v1/exports") {
                    controlRequest(session)
                    setBody("scope=EVENT_IDS&eventId=event-2")
                }
            val zipEntries = response.bodyAsBytes().zipEntries()

            assertEquals(HttpStatusCode.Forbidden, csrfDenied.status)
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentDisposition].orEmpty().contains("devconsole-export.zip"))
            assertTrue(zipEntries.getValue("timeline.jsonl").decodeToString().contains("\"id\":\"event-2\""))
            assertFalse(zipEntries.getValue("timeline.jsonl").decodeToString().contains("event-1"))
            assertFalse(zipEntries.getValue("timeline.jsonl").decodeToString().contains("export-secret"))
            assertTrue(zipEntries.getValue("manifest.json").decodeToString().contains("\"sha256\":"))
            val attachmentEntry =
                zipEntries.entries.single {
                    it.key.startsWith("attachments/") &&
                        it.key.endsWith(".bin")
                }
            assertFalse(attachmentEntry.value.decodeToString().contains("attachment-secret"))
            assertTrue(attachmentEntry.value.decodeToString().contains("<redacted>"))
            assertEquals(listOf("REJECTED", "SUCCESS"), audit.events().map { it.result.name })
            assertTrue(exportDirectory.listFiles().isNullOrEmpty())
            exportDirectory.deleteRecursively()
        }

    @Test
    fun `export size estimate requires authentication and validates request shape`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = client.exchangeSession(sessions, sessionCodes)

            val unauthenticated =
                client.get("/api/v1/exports/estimate") { header(HttpHeaders.Host, "localhost") }
            val invalidScope =
                client.get("/api/v1/exports/estimate?scope=NOT_A_SCOPE") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
            assertEquals(HttpStatusCode.BadRequest, invalidScope.status)
            assertTrue(invalidScope.bodyAsText().contains("VALIDATION_FAILED"))
        }

    @Test
    fun `export size estimate reports the same figure the write route trusts for its size gate`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val exportDirectory = Files.createTempDirectory("devconsole-server-export-estimate").toFile()
            val timeline =
                InMemoryTimeline(
                    listOf(
                        StoredEvent(
                            "event-1",
                            "runtime-session",
                            1,
                            "system",
                            "system.event",
                            1,
                            1,
                            1,
                            "hello world",
                        ),
                    ),
                    CursorCodec("estimate-cursor-secret".encodeToByteArray()),
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    this.timeline = timeline
                    this.exportDirectory = exportDirectory
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val estimate =
                client.get("/api/v1/exports/estimate?scope=WHOLE_SESSION") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, estimate.status)
            val estimatedBytes =
                Regex("\"estimatedBytes\":(\\d+)").find(estimate.bodyAsText())!!.groupValues[1].toLong()
            assertTrue(estimatedBytes > 0)
            // No file should ever be written by an estimate -- it must never touch disk.
            assertTrue(exportDirectory.listFiles().isNullOrEmpty())

            val tooSmall =
                client.post("/api/v1/exports") {
                    controlRequest(session)
                    setBody("scope=WHOLE_SESSION&maxBytes=1")
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, tooSmall.status)
            val body = tooSmall.bodyAsText()
            assertTrue(body.contains("\"code\":\"EXPORT_TOO_LARGE\""))
            assertTrue(body.contains("\"maxBytes\":1"))
            val reportedEstimate = Regex("\"estimatedBytes\":(\\d+)").find(body)!!.groupValues[1].toLong()
            assertEquals(estimatedBytes, reportedEstimate)
            exportDirectory.deleteRecursively()
        }

    @Test
    fun `exports estimate is rate limited as tightly as export itself, not the 120 per minute read limiter`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = client.exchangeSession(sessions, sessionCodes)

            // exportLimiter allows 5 per 10 minutes -- far tighter than readQueryLimiter's 120/min the
            // estimate route used to fall through to, despite doing the identical expensive bundle
            // assembly work as POST /api/v1/exports itself.
            repeat(5) {
                assertEquals(
                    HttpStatusCode.OK,
                    client
                        .get("/api/v1/exports/estimate?scope=WHOLE_SESSION") {
                            header(HttpHeaders.Host, "localhost")
                            header(HttpHeaders.Authorization, "Bearer ${session.token}")
                        }.status,
                )
            }
            val limited =
                client.get("/api/v1/exports/estimate?scope=WHOLE_SESSION") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertTrue(limited.bodyAsText().contains("RATE_LIMITED"))
        }

    @Test
    fun `attachment download requires authentication and answers not found for unknown or throwing reads`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    commandAuditLog = audit
                    attachmentReader = { id ->
                        when (id) {
                            "attachment-1" -> "raw-bytes".encodeToByteArray()
                            "attachment-throws" -> error("boom")
                            else -> null
                        }
                    }
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val unauthenticated =
                client.get("/api/v1/attachments/attachment-1") { header(HttpHeaders.Host, "localhost") }
            val unknown =
                client.get("/api/v1/attachments/missing") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val throwing =
                client.get("/api/v1/attachments/attachment-throws") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertTrue(unknown.bodyAsText().contains("NOT_FOUND"))
            assertEquals(HttpStatusCode.NotFound, throwing.status)
            assertTrue(audit.events().all { it.commandType == "attachments.download" })
            assertTrue(audit.events().any { it.result.name == "REJECTED" })
        }

    @Test
    fun `attachment download streams raw bytes with an attachment content disposition`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val bytes = byteArrayOf(1, 2, 3, 4)
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    commandAuditLog = audit
                    attachmentReader = { id -> if (id == "attachment-1") bytes else null }
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/attachments/attachment-1") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertArrayEquals(bytes, response.bodyAsBytes())
            assertTrue(response.headers[HttpHeaders.ContentDisposition].orEmpty().contains("attachment"))
            assertTrue(response.headers[HttpHeaders.ContentDisposition].orEmpty().contains("attachment-1"))
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/octet-stream"))
            assertEquals(
                "SUCCESS",
                audit
                    .events()
                    .single()
                    .result.name,
            )
        }

    // ========================================================================================
    // Attachment redaction applicability is reported from stored data, never inferred.
    // Both directions matter: NOT_APPLICABLE (screenshot) and APPLIED (everything else) must both
    // come back correctly, so a future non-screenshot NOT_APPLICABLE attachment is provably covered
    // by a real lookup rather than a "screenshot => NOT_APPLICABLE" rule that would miss it.
    // ========================================================================================

    @Test
    fun `attachment download reports stored redaction applicability for both directions`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val bytes = byteArrayOf(9)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    attachmentReader = { bytes }
                    attachmentMetadataReader = { id ->
                        when (id) {
                            "shot" -> testAttachment("shot", RedactionApplicability.NOT_APPLICABLE)
                            "body" -> testAttachment("body", RedactionApplicability.APPLIED)
                            else -> null
                        }
                    }
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val screenshot =
                client.get("/api/v1/attachments/shot") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val body =
                client.get("/api/v1/attachments/body") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals("NOT_APPLICABLE", screenshot.headers["X-DevConsole-Redaction-Applicability"])
            assertEquals("APPLIED", body.headers["X-DevConsole-Redaction-Applicability"])
        }

    @Test
    fun `attachment download omits the applicability header when the metadata reader is unwired`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    attachmentReader = { byteArrayOf(1) }
                    // attachmentMetadataReader left at its default ({ null }) -- absent, not defaulted.
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/attachments/attachment-1") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(null, response.headers["X-DevConsole-Redaction-Applicability"])
        }

    @Test
    @Suppress("LongMethod") // One linear E2E: seed three retained events, request the route, assert all three badges.
    fun `retained-events reports redaction applicability for events carrying an attachmentId`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val events =
                listOf(
                    retainedTestEvent("shot-event", 1, "screenshot", "Screenshot captured", "shot"),
                    retainedTestEvent("plain-event", 2, "network", "Body captured", "body"),
                    retainedTestEvent("no-attachment", 3, "logs", "No attachment"),
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    retainedCaptures = RetainedCaptureQuery({ FakeEventStore(events) }, { "current" })
                    attachmentMetadataReader = { id ->
                        when (id) {
                            "shot" -> testAttachment("shot", RedactionApplicability.NOT_APPLICABLE)
                            "body" -> testAttachment("body", RedactionApplicability.APPLIED)
                            else -> null
                        }
                    }
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/retained-events") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            // .*? (lazy) rather than [^}]* -- tagsJson's own value is the literal string "{}", and a
            // character class excluding '}' stops right there, short of redactionApplicability.
            assertTrue(
                "screenshot event must report NOT_APPLICABLE from stored data: $body",
                Regex("\"id\":\"shot-event\".*?\"redactionApplicability\":\"NOT_APPLICABLE\"").containsMatchIn(body),
            )
            assertTrue(
                "non-screenshot event with an attachment must report APPLIED: $body",
                Regex("\"id\":\"plain-event\".*?\"redactionApplicability\":\"APPLIED\"").containsMatchIn(body),
            )
            assertTrue(
                "an event with no attachmentId must report a null applicability, not a fabricated one: $body",
                Regex("\"id\":\"no-attachment\".*?\"redactionApplicability\":null").containsMatchIn(body),
            )
        }

    // ========================================================================================
    // pluginId filter on GET /api/v1/retained-events -- the actual bug: a crash written under a
    // *previous* session id (its process already died) must still be findable once a *different*
    // session is current, without dragging every other retained event along for the ride.
    // ========================================================================================

    @Test
    fun `retained-events pluginId filter finds a crash from a previous session with no sessionId given`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val events =
                listOf(
                    retainedTestEvent("crash-event", 1, "crash", "App crashed").copy(sessionId = "previous-run"),
                    retainedTestEvent("current-log", 2, "logs", "just a log"),
                )
            application {
                // currentSessionId returns "current" -- the app restarted after the crash and a
                // *new* session is live, exactly like the dashboard reconnecting post-crash. The
                // crash itself lives only under "previous-run", a session id nothing in this
                // request ever names.
                devConsoleModule(sessions, sessionCodes) {
                    retainedCaptures = RetainedCaptureQuery({ FakeEventStore(events) }, { "current" })
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/retained-events?pluginId=crash") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                "crash from a previous session must be returned: $body",
                Regex("\"id\":\"crash-event\".*?\"sessionId\":\"previous-run\"").containsMatchIn(body),
            )
            assertTrue(
                "the current session's own non-crash event must not leak in through the plugin filter: $body",
                !body.contains("current-log"),
            )
        }

    @Test
    fun `retained-events sessionId and pluginId together scope to that session's matching plugin only`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val events =
                listOf(
                    retainedTestEvent("crash-a", 1, "crash", "Crash in run A").copy(sessionId = "run-a"),
                    retainedTestEvent("log-a", 2, "logs", "Log in run A").copy(sessionId = "run-a"),
                    retainedTestEvent("crash-b", 3, "crash", "Crash in run B").copy(sessionId = "run-b"),
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    retainedCaptures = RetainedCaptureQuery({ FakeEventStore(events) }, { "run-a" })
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/retained-events?sessionId=run-a&pluginId=crash") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue("run A's crash must be present: $body", body.contains("crash-a"))
            assertTrue("run A's own non-crash event must be filtered out: $body", !body.contains("log-a"))
            assertTrue("run B's crash is out of scope once sessionId pins to run-a: $body", !body.contains("crash-b"))
        }

    @Test
    fun `retained-events unknown pluginId returns no rows while a blank pluginId is treated as absent`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val events = listOf(retainedTestEvent("log-event", 1, "logs", "just a log"))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    retainedCaptures = RetainedCaptureQuery({ FakeEventStore(events) }, { "current" })
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val unknownPluginResponse =
                client.get("/api/v1/retained-events?pluginId=does-not-exist") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            assertEquals(HttpStatusCode.OK, unknownPluginResponse.status)
            assertEquals(
                "{\"data\":[],\"limit\":${RetainedCaptureQuery.DEFAULT_LIMIT}}",
                unknownPluginResponse.bodyAsText(),
            )

            val blankPluginResponse =
                client.get("/api/v1/retained-events?pluginId=") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val blankBody = blankPluginResponse.bodyAsText()
            assertEquals(HttpStatusCode.OK, blankPluginResponse.status)
            assertTrue(
                "a blank pluginId must be ignored, falling back to the pre-existing current-session read: $blankBody",
                blankBody.contains("log-event"),
            )
        }

    // ========================================================================================
    // GET /api/v1/runs exposes retained app-run history (StoredSessionStatus) so the
    // dashboard can build the previous-run-crashed banner from real data instead of guessing from
    // this session's own crash events.
    // ========================================================================================

    @Test
    fun `runs requires authentication`() =
        testApplication {
            application { devConsoleModule(SessionAuthority()) }

            val response = client.get("/api/v1/runs") { header(HttpHeaders.Host, "localhost") }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("AUTH_REQUIRED"))
        }

    @Test
    fun `runs reports a crashed run's status newest-first and deterministically ordered`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val crashed =
                testSession(
                    id = "run-crashed",
                    status = StoredSessionStatus.CRASHED,
                    startedAtMs = 1_000,
                    endedAtMs = 1_500,
                )
            val older =
                testSession(
                    id = "run-older",
                    status = StoredSessionStatus.COMPLETED,
                    startedAtMs = 500,
                    endedAtMs = 900,
                )
            val active = testSession(id = "run-active", status = StoredSessionStatus.ACTIVE, startedAtMs = 2_000)
            application {
                // Deliberately out of order -- the route must sort, not trust the provider.
                devConsoleModule(sessions, sessionCodes) { sessionsProvider = { listOf(older, active, crashed) } }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/runs") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val body = response.bodyAsText()
            val ids = Regex("\"id\":\"(run-[a-z]+)\"").findAll(body).map { it.groupValues[1] }.toList()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("newest-first by startedAtEpochMs", listOf("run-active", "run-crashed", "run-older"), ids)
            assertTrue(
                "the crashed run's status must be reported so the banner can find it: $body",
                Regex("\"id\":\"run-crashed\"[^}]*\"status\":\"CRASHED\"").containsMatchIn(body),
            )
            assertTrue(body.contains("\"endedAtEpochMs\":1500"))
        }

    @Test
    fun `a bug report requires authentication`() =
        testApplication {
            application { devConsoleModule(SessionAuthority()) }

            val response = client.get("/api/v1/report") { header(HttpHeaders.Host, "localhost") }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `network transaction detail serializes all six timing phases`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            store.record(
                NetworkTransaction(
                    id = "transaction-timings-full",
                    startedAtEpochMs = 1,
                    completedAtEpochMs = 2,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                            NetworkRequestInput("GET", "https://api.test/full-timings"),
                            NetworkResponseInput(200).withMetadata(
                                NetworkResponseMetadata(
                                    timings =
                                        NetworkTimingPhases(
                                            dnsMs = 1,
                                            connectMs = 2,
                                            tlsMs = 3,
                                            sendMs = 4,
                                            waitMs = 5,
                                            receiveMs = 6,
                                        ),
                                ),
                            ),
                        ),
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = store
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val detail =
                client.get("/api/v1/network/transactions/transaction-timings-full") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, detail.status)
            assertTrue(
                detail.bodyAsText().contains(
                    "\"timings\":{\"dnsMs\":1,\"connectMs\":2,\"tlsMs\":3,\"sendMs\":4,\"waitMs\":5,\"receiveMs\":6}",
                ),
            )
        }

    @Test
    fun `network transaction detail emits null timing phases when no timings were recorded`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            store.record(
                NetworkTransaction(
                    id = "transaction-timings-absent",
                    startedAtEpochMs = 1,
                    completedAtEpochMs = 2,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                            NetworkRequestInput("GET", "https://api.test/no-timings"),
                            NetworkResponseInput(200),
                        ),
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = store
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val detail =
                client.get("/api/v1/network/transactions/transaction-timings-absent") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, detail.status)
            assertTrue(
                detail.bodyAsText().contains(
                    "\"timings\":{\"dnsMs\":null,\"connectMs\":null,\"tlsMs\":null," +
                        "\"sendMs\":null,\"waitMs\":null,\"receiveMs\":null}",
                ),
            )
        }

    @Test
    fun `network transaction detail leaves pooled-connection timing phases null instead of coercing to zero`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            store.record(
                NetworkTransaction(
                    id = "transaction-timings-pooled",
                    startedAtEpochMs = 1,
                    completedAtEpochMs = 2,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                            NetworkRequestInput("GET", "https://api.test/pooled-connection"),
                            // A reused pooled connection performs no DNS/connect/TLS handshake, so
                            // those phases are legitimately absent -- only wait/receive are measured.
                            NetworkResponseInput(200).withMetadata(
                                NetworkResponseMetadata(
                                    timings = NetworkTimingPhases(waitMs = 12, receiveMs = 7),
                                ),
                            ),
                        ),
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = store
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val detail =
                client.get("/api/v1/network/transactions/transaction-timings-pooled") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, detail.status)
            val body = detail.bodyAsText()
            assertTrue(
                body.contains(
                    "\"timings\":{\"dnsMs\":null,\"connectMs\":null,\"tlsMs\":null," +
                        "\"sendMs\":null,\"waitMs\":12,\"receiveMs\":7}",
                ),
            )
            assertFalse(body.contains("\"dnsMs\":0"))
            assertFalse(body.contains("\"connectMs\":0"))
            assertFalse(body.contains("\"tlsMs\":0"))
            assertFalse(body.contains("\"sendMs\":0"))
        }
}

/**
 * Issues a fresh session code and exchanges it over HTTP for a real [BrowserSession] -- the only way
 * to mint a session from this module's tests now that `SessionAuthority.createSession` is internal to
 * `sdk:server-api`. Looking the session back up on [sessions] (rather than hand-rolling a stand-in
 * type) gives every call site the real `id`/`token`/`csrfToken`/`expiresAtEpochMs`.
 */
private suspend fun HttpClient.exchangeSession(
    sessions: SessionAuthority,
    sessionCodes: SessionCodeAuthority,
    browserLabel: String = "Chrome",
): BrowserSession {
    val encodedLabel = java.net.URLEncoder.encode(browserLabel, "UTF-8")
    val response =
        post("/api/v1/auth/session-code/exchange") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            setBody("code=${sessionCodes.issueCode().code}&browserLabel=$encodedLabel")
        }
    val token = Regex("\"accessToken\":\"([^\"]+)\"").find(response.bodyAsText())!!.groupValues[1]
    return sessions.sessionForToken(token)!!
}

private fun HttpRequestBuilder.controlRequest(session: BrowserSession) {
    header(HttpHeaders.Host, "localhost")
    header(HttpHeaders.Authorization, "Bearer ${session.token}")
    header(HttpHeaders.Origin, "http://localhost")
    header("X-DevConsole-CSRF", session.csrfToken)
    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
}

private fun ByteArray.zipEntries(): Map<String, ByteArray> =
    buildMap {
        ZipInputStream(ByteArrayInputStream(this@zipEntries)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                put(entry.name, zip.readBytes())
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

/**
 * Minimal [StoredEvent] fixture for the retained-events tests -- session is always "current" and
 * `type` is never asserted on, so it is derived from [pluginId] rather than taking a separate
 * parameter. The cross-session pluginId-filter tests need events planted under a session other
 * than "current"; they get there with `.copy(sessionId = ...)` on the result rather than a seventh
 * constructor parameter here.
 */
private fun retainedTestEvent(
    id: String,
    sequence: Long,
    pluginId: String,
    summary: String,
    attachmentId: String? = null,
): StoredEvent =
    StoredEvent(
        id = id,
        sessionId = "current",
        sequence = sequence,
        pluginId = pluginId,
        type = "$pluginId.event",
        wallTimeMs = sequence,
        monoTimeNs = sequence,
        severity = 1,
        summary = summary,
        attachmentId = attachmentId,
    )

/** Minimal [StoredAttachment] fixture -- only [id] and [redactionApplicability] vary across tests. */
private fun testAttachment(
    id: String,
    redactionApplicability: RedactionApplicability,
): StoredAttachment =
    StoredAttachment(
        id = id,
        eventId = "event-$id",
        sessionId = "current",
        mimeType = "application/octet-stream",
        originalLength = 1,
        storedLength = 1,
        truncated = false,
        sha256 = "hash-$id",
        isRedacted = redactionApplicability == RedactionApplicability.APPLIED,
        relativePath = "attachments/$id",
        redactionApplicability = redactionApplicability,
    )

/** Minimal [StoredSession] fixture for the GET /api/v1/runs tests -- only the banner-relevant fields vary. */
private fun testSession(
    id: String = "run-1",
    status: StoredSessionStatus = StoredSessionStatus.COMPLETED,
    startedAtMs: Long = 1,
    endedAtMs: Long? = null,
): StoredSession =
    StoredSession(
        id = id,
        status = status,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        deviceModel = "Pixel Test",
    )

/** In-memory [EventStore] backing [RetainedCaptureQuery] in the retained-events tests. */
private class FakeEventStore(
    private val events: List<StoredEvent>,
) : EventStore {
    override suspend fun insert(events: List<StoredEvent>): EventStoreWriteResult = EventStoreWriteResult.Unavailable

    override suspend fun eventsForSession(sessionId: String): List<StoredEvent> =
        events.filter { it.sessionId == sessionId }

    // Overridden (rather than left at the interface default, which returns emptyList()) so the
    // cross-session pluginId tests below have something real to exercise through the route --
    // RoomEventStore's own SQL-pushdown equivalent is proven separately in :sdk:storage-room.
    override suspend fun recentEventsForPlugins(
        pluginIds: Set<String>,
        limit: Int,
    ): List<StoredEvent> {
        require(limit > 0) { "limit must be positive" }
        require(pluginIds.isNotEmpty()) { "pluginIds must not be empty" }
        return events.filter { it.pluginId in pluginIds }.takeLast(limit)
    }

    override suspend fun deleteSession(sessionId: String) = Unit

    override suspend fun eventCount(): Long = events.size.toLong()
}
