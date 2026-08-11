package io.devconsole.server.ktor

import io.devconsole.api.EventEnvelope
import io.devconsole.api.EventSeverity
import io.devconsole.composer.ComposerExecutor
import io.devconsole.composer.ComposerRequest
import io.devconsole.composer.ComposerResponse
import io.devconsole.composer.ComposerTransport
import io.devconsole.composer.InMemoryComposerCollectionStore
import io.devconsole.push.InMemoryPushStore
import io.devconsole.push.PushLifecycle
import io.devconsole.push.PushRecorder
import io.devconsole.push.PushSimulationCallback
import io.devconsole.push.PushSimulator
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.BindingMode
import io.devconsole.server.api.Endpoint
import io.devconsole.server.api.InMemoryCommandAuditLog
import io.devconsole.server.api.ServerStartResult
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.StartRequest
import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.CursorCodec
import io.devconsole.timeline.InMemoryTimeline
import io.devconsole.timeline.InMemoryTimelineAnnotations
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections
import java.util.UUID

class KtorLocalServerEngineTest {
    @Test
    fun `falls forward through a requested loopback port range`() =
        runBlocking {
            ServerSocket(0).use { occupied ->
                val engine = KtorLocalServerEngine()
                try {
                    val result =
                        engine.start(
                            StartRequest(
                                BindingMode.LOOPBACK,
                                occupied.localPort..(occupied.localPort + 10),
                            ),
                        )

                    assertTrue(result is ServerStartResult.Started && result.endpoint.port != occupied.localPort)
                } finally {
                    engine.stop()
                }
            }
        }

    @Test
    fun `content security policy connect-src pins to the bound endpoint's ws origin`() =
        runBlocking {
            // dashboard.js always dials back to `location.host` over ws:// (this embedded server
            // never terminates TLS), so the CSP's connect-src must name that exact host:port rather
            // than the old blanket "ws: wss:" (any host, any scheme) -- see the CSP-building comment
            // in devConsoleModule for the Safari same-origin-ws compatibility reasoning.
            val engine = KtorLocalServerEngine()
            val started = engine.start(StartRequest(BindingMode.LOOPBACK, 8520..8539))
            assertTrue(started is ServerStartResult.Started)
            started as ServerStartResult.Started
            val client = HttpClient(CIO)

            try {
                val response = client.get(started.endpoint.url("/"))

                val csp = response.headers["Content-Security-Policy"]!!
                assertTrue(csp.contains("connect-src 'self' ws://${started.endpoint.host}:${started.endpoint.port}"))
                assertFalse("must not allow a websocket to any host", csp.contains("connect-src 'self' ws: wss:"))
            } finally {
                client.close()
                engine.stop()
            }
        }

    @Test
    fun `content security policy connect-src follows the localhost alias under the adb forward workflow`() =
        runBlocking {
            // `adb forward` forwards the host machine's http://localhost:<port> to the device's
            // LOOPBACK bind address -- KtorLocalServerEngine wires `allowedHosts = setOf("localhost",
            // endpoint.host)` specifically to support that documented workflow. A browser sitting at
            // http://localhost:<port> has `location.host == "localhost:<port>"`, which is what
            // dashboard.js's WebSocket actually dials -- connect-src must follow that alias, not the
            // server's own bind address ("127.0.0.1"), or the ws:// upgrade is silently blocked
            // (worse in Safari, which doesn't reliably treat bare 'self' as covering it either).
            val engine = KtorLocalServerEngine()
            val started = engine.start(StartRequest(BindingMode.LOOPBACK, 8580..8599))
            assertTrue(started is ServerStartResult.Started)
            started as ServerStartResult.Started
            val client = HttpClient(CIO)

            try {
                val response = client.get("http://localhost:${started.endpoint.port}/")

                assertEquals(HttpStatusCode.OK, response.status)
                val csp = response.headers["Content-Security-Policy"]!!
                assertTrue(csp.contains("connect-src 'self' ws://localhost:${started.endpoint.port}"))
                assertFalse(
                    "must not pin to the bind address when the browser used the localhost alias",
                    csp.contains("ws://${started.endpoint.host}:"),
                )
            } finally {
                client.close()
                engine.stop()
            }
        }

    @Test
    fun `stop clears engine state so a subsequent start on the same engine succeeds`() =
        runBlocking {
            // Regression coverage: a throwing engine.stop() used to
            // skip the `engine = null` reset entirely (no try/finally), permanently wedging the
            // server -- every later start() would answer "Server already running" until process
            // death. This can't fault-inject a throwing embedded engine without a bigger refactor
            // (it's constructed inline inside start()), but it does prove the happy path actually
            // clears every piece of state stop() is responsible for, twice over.
            val engine = KtorLocalServerEngine()

            val first = engine.start(StartRequest(BindingMode.LOOPBACK, 8540..8559))
            assertTrue(first is ServerStartResult.Started)
            engine.stop()

            val second = engine.start(StartRequest(BindingMode.LOOPBACK, 8540..8559))
            assertTrue(
                "a second start after stop() must not answer 'already running'",
                second is ServerStartResult.Started,
            )
            engine.stop()
        }

    @Test
    fun `stop is a safe no-op when the engine was never started`() =
        runBlocking {
            val engine = KtorLocalServerEngine()

            // Must not throw even though `engine` (the private EmbeddedServer field) is null.
            engine.stop()
            engine.stop()
        }

    @Test
    fun `LAN mode binds to a real non-loopback address`() =
        runBlocking {
            val engine = KtorLocalServerEngine()
            try {
                val result = engine.start(StartRequest(BindingMode.LAN, 8180..8199))

                assertTrue(result is ServerStartResult.Started)
                result as ServerStartResult.Started
                assertEquals(BindingMode.LAN, result.endpoint.bindingMode)
                assertNotEquals("127.0.0.1", result.endpoint.host)
                assertFalse(result.endpoint.host == "0.0.0.0")
            } finally {
                engine.stop()
            }
        }

    @Test
    fun `LAN mode reports no eligible network when no interface qualifies`() =
        runBlocking {
            val engine = KtorLocalServerEngine(lanInterfaces = { emptyList() })
            try {
                val result = engine.start(StartRequest(BindingMode.LAN, 8180..8199))

                assertTrue(result is ServerStartResult.NoEligibleNetwork)
            } finally {
                engine.stop()
            }
        }

    @Test
    fun `selectLanAddress finds a real interface address and returns null for an empty list`() {
        assertEquals(null, selectLanAddress(emptyList()))

        val realInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        assertNotEquals(null, selectLanAddress(realInterfaces))
    }

    // Exercises the decision function itself, not the NetworkInterface plumbing selectLanAddress already
    // covers above -- see bindAddressChanged's own kdoc for why it's split out this way.
    @Test
    fun `bindAddressChanged is true only for a LAN bind whose live address actually differs`() {
        val bound = Endpoint("192.168.1.10", 8080, BindingMode.LAN)

        assertTrue(bindAddressChanged(bound, "192.168.1.20"))
        assertFalse("unchanged address is not a change", bindAddressChanged(bound, "192.168.1.10"))
        assertFalse("no live address is inconclusive, not a change", bindAddressChanged(bound, null))
        assertFalse("never started", bindAddressChanged(null, "192.168.1.20"))
        assertFalse(
            "loopback binding never rebinds",
            bindAddressChanged(bound.copy(bindingMode = BindingMode.LOOPBACK), "192.168.1.20"),
        )
    }

    @Test
    fun `a fresh LAN engine reports no bind-address change against its own real interfaces`() =
        runBlocking {
            val engine = KtorLocalServerEngine()
            try {
                engine.start(StartRequest(BindingMode.LAN, 8200..8219))

                assertFalse(engine.bindAddressChanged())
            } finally {
                engine.stop()
            }
        }

    @Test
    fun `composer and state mutation opt-ins reach the module through the engine`() =
        runBlocking {
            val engine =
                KtorLocalServerEngine(
                    composerEnabled = true,
                    stateMutationsEnabled = true,
                )
            val started = engine.start(StartRequest(BindingMode.LOOPBACK, 8460..8479))
            assertTrue(started is ServerStartResult.Started)
            started as ServerStartResult.Started
            val client = HttpClient(CIO)

            try {
                val composer = client.get(started.endpoint.url("/api/v1/composer/collections"))
                val mutation = client.post(started.endpoint.url("/api/v1/state/cache/mutations/clear"))

                // A disabled Composer answers 404 and a disabled mutation surface answers 403 before
                // authentication is ever considered, so 401 is what proves the opt-ins were forwarded.
                assertEquals(HttpStatusCode.Unauthorized, composer.status)
                assertEquals(HttpStatusCode.Unauthorized, mutation.status)
            } finally {
                client.close()
                engine.stop()
            }
        }

    @Test
    fun `engine forwards composer collaborators, host allow-list, audit log, and redaction policy`() =
        runBlocking {
            val authority = SessionAuthority()
            var submittedUrl = ""
            val collections =
                InMemoryComposerCollectionStore().apply {
                    save("smoke", ComposerRequest(method = "GET", url = "https://api.example.test/orders"))
                }
            val audit = InMemoryCommandAuditLog()
            val engine =
                KtorLocalServerEngine(
                    sessionAuthority = authority,
                    composerEnabled = true,
                    composerAllowedHosts = setOf("api.example.test"),
                    redactionPolicy = RedactionPolicy.default().copy(sensitiveFieldNames = setOf("x-tenant-secret")),
                    commandAuditLog = audit,
                    composerCollections = collections,
                    composerExecutor =
                        ComposerExecutor(
                            ComposerTransport { request ->
                                submittedUrl = request.url
                                ComposerResponse(statusCode = 202, body = "accepted")
                            },
                        ),
                )
            val started = engine.start(StartRequest(BindingMode.LOOPBACK, 8480..8499))
            assertTrue(started is ServerStartResult.Started)
            started as ServerStartResult.Started
            val client = HttpClient(CIO)
            val session = client.exchangeSession(started.endpoint, started.sessionCode.code)

            try {
                val meta =
                    client.get(started.endpoint.url("/api/v1/meta")) {
                        header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    }
                val listed =
                    client.get(started.endpoint.url("/api/v1/composer/collections")) {
                        header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    }
                val executed =
                    client.post(started.endpoint.url("/api/v1/composer/execute")) {
                        controlCredentials(started.endpoint, session)
                        setBody("method=GET&url=https%3A%2F%2Fapi.example.test%2Forders")
                    }
                val rejected =
                    client.post(started.endpoint.url("/api/v1/composer/execute")) {
                        controlCredentials(started.endpoint, session)
                        setBody("method=GET&url=https%3A%2F%2Fexfiltrate.example%2Forders")
                    }

                assertTrue(meta.bodyAsText().contains("\"x-tenant-secret\""))
                assertTrue(listed.bodyAsText().contains("\"name\":\"smoke\""))
                assertEquals(HttpStatusCode.OK, executed.status)
                assertEquals("https://api.example.test/orders", submittedUrl)
                assertEquals(HttpStatusCode.Forbidden, rejected.status)
                assertTrue(rejected.bodyAsText().contains("COMPOSER_HOST_REJECTED"))
                assertEquals(listOf("composer.execute", "composer.execute"), audit.events().map { it.commandType })
            } finally {
                client.close()
                engine.stop()
            }
        }

    @Test
    fun `engine forwards the push simulator, timeline annotations, and its public stream hub`() =
        runBlocking {
            val authority = SessionAuthority()
            val pushes = InMemoryPushStore()
            val annotations = InMemoryTimelineAnnotations()
            val engine =
                KtorLocalServerEngine(
                    sessionAuthority = authority,
                    timeline =
                        InMemoryTimeline(
                            listOf(StoredEvent("event-1", "session", 1, "system", "event", 1, 1, 1, "ready")),
                            CursorCodec("engine-cursor-key".encodeToByteArray()),
                        ),
                    pushSimulator =
                        PushSimulator(
                            PushSimulationCallback { PushLifecycle.DISPLAYED },
                            PushRecorder(RedactionEngine(RedactionPolicy.default()), pushes),
                        ),
                    annotations = annotations,
                )
            val started = engine.start(StartRequest(BindingMode.LOOPBACK, 8500..8519))
            assertTrue(started is ServerStartResult.Started)
            started as ServerStartResult.Started
            val client = HttpClient(CIO) { install(WebSockets) }
            val session = client.exchangeSession(started.endpoint, started.sessionCode.code)

            try {
                val simulated =
                    client.post(started.endpoint.url("/api/v1/push/simulate")) {
                        controlCredentials(started.endpoint, session)
                        setBody("provider=local&messageId=preview")
                    }
                val bookmarked =
                    client.post(started.endpoint.url("/api/v1/events/event-1/bookmark")) {
                        controlCredentials(started.endpoint, session)
                    }

                assertEquals(HttpStatusCode.OK, simulated.status)
                assertEquals(PushLifecycle.DISPLAYED, pushes.events().single().lifecycle)
                assertEquals(HttpStatusCode.OK, bookmarked.status)
                assertTrue(annotations.get("event-1").bookmarked)

                client.webSocket(
                    urlString = "ws://${started.endpoint.host}:${started.endpoint.port}/api/v1/stream",
                    request = { header(HttpHeaders.Authorization, "Bearer ${session.token}") },
                ) {
                    send(Frame.Text("{\"type\":\"client.hello\",\"protocolVersion\":1}"))
                    assertTrue((incoming.receive() as Frame.Text).data.decodeToString().contains("server.welcome"))

                    engine.streamHub.publish(
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
                    assertTrue((incoming.receive() as Frame.Text).data.decodeToString().contains("event.appended"))
                }
            } finally {
                client.close()
                engine.stop()
            }
        }
}

/** Lightweight local stand-in for `BrowserSession`, whose constructor is internal to `sdk:server-api`. */
private data class ExchangedSession(
    val token: String,
    val csrfToken: String,
)

/** Exchanges a live session code over HTTP -- the only way to mint a session as an external caller. */
private suspend fun HttpClient.exchangeSession(
    endpoint: Endpoint,
    code: String,
): ExchangedSession {
    val response =
        post(endpoint.url("/api/v1/auth/session-code/exchange")) {
            header(HttpHeaders.Host, "${endpoint.host}:${endpoint.port}")
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            setBody("code=$code&browserLabel=Chrome")
        }
    val body = response.bodyAsText()
    val token = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
    val csrf = Regex("\"csrfToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
    return ExchangedSession(token, csrf)
}

private fun Endpoint.url(path: String): String = "http://$host:$port$path"

/** The module derives the expected Origin from the Host header, so both must name the bound port. */
private fun HttpRequestBuilder.controlCredentials(
    endpoint: Endpoint,
    session: ExchangedSession,
) {
    header(HttpHeaders.Authorization, "Bearer ${session.token}")
    header(HttpHeaders.Origin, "http://${endpoint.host}:${endpoint.port}")
    header("X-DevConsole-CSRF", session.csrfToken)
    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
}
