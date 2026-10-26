package io.devconsole.server.ktor

import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkTransactionQuery
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.InMemoryCommandAuditLog
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
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
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.http.parseQueryString
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerAgentRoutesTest {
    @Test
    fun `a connected agent is listed with the name and address it announced`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { protectedDevConsoleModule(sessions, sessionCodes) { composerEnabled = true } }
            val session = client.exchangeSession(sessions, sessionCodes)
            val agentClient = createClient { install(WebSockets) }

            agentClient.connectAgent(session, name = "My Mac") { agentId ->
                val listed =
                    client
                        .get("/api/v1/composer/agents") {
                            header(HttpHeaders.Host, "localhost")
                            header(HttpHeaders.Authorization, "Bearer ${session.token}")
                        }.bodyAsText()

                assertTrue(listed.contains("\"id\":\"$agentId\""), listed)
                assertTrue(listed.contains("\"name\":\"My Mac\""), listed)
                assertTrue(listed.contains("\"os\":\"TestOS\""), listed)
            }
        }

    @Test
    fun `an agent runs a request with the resolved bearer token and its result is redacted`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val network = InMemoryNetworkTransactionStore(NetworkCursorCodec("network-cursor-key".encodeToByteArray()))
            val audit = InMemoryCommandAuditLog()
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.corp.test")
                    networkTransactions = network
                    commandAuditLog = audit
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)
            val agentClient = createClient { install(WebSockets) }

            agentClient.connectAgent(session, name = "My Mac") { agentId ->
                val execution =
                    coroutineScope {
                        async {
                            client.post("/api/v1/composer/execute") {
                                controlRequest(session)
                                setBody(
                                    "method=POST&url=https%3A%2F%2Fapi.corp.test%2Forders" +
                                        "&header=Authorization%3A+Bearer+%24%7Btoken%7D" +
                                        "&secretVariable=token%3Dtop-secret-token" +
                                        "&bodyType=JSON&body=%7B%22a%22%3A1%7D&executor=agent%3A$agentId",
                                )
                            }
                        }.also {
                            val job = receiveFrame()
                            assertJobCarriesResolvedRequest(job)
                            sendFrame(
                                "type" to "agent.result",
                                "jobId" to job["jobId"]!!,
                                "status" to "201",
                                "durationMs" to "12",
                                "header" to "Content-Type:application/json",
                                "body" to "{\"id\":7,\"echo\":\"top-secret-token\"}",
                            )
                        }.await()
                    }

                val body = execution.bodyAsText()
                assertEquals(HttpStatusCode.OK, execution.status, body)
                assertTrue(body.contains("\"executor\":\"agent:My Mac\""), body)
                assertTrue(body.contains("\"statusCode\":201"), body)
                assertTrue(body.contains("\"Content-Type\":\"application/json\""), body)
                assertTrue(body.contains("\\\"id\\\":7"), body)
                assertFalse(body.contains("top-secret-token"), "the secret must be redacted in the Composer result")
                val captured = network.page(NetworkTransactionQuery()).transactions.single()
                assertEquals("agent:My Mac", captured.capture.request.metadata.tags["executor"])
                assertFalse(
                    captured.capture.request.headers.values
                        .any { it.contains("top-secret-token") },
                )
                assertTrue(audit.events().any { it.commandType == "composer.agent.connect" })
            }
        }

    @Test
    fun `a megabyte sized response survives the agent socket`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.corp.test")
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)
            val agentClient = createClient { install(WebSockets) }

            agentClient.connectAgent(session) { agentId ->
                val execution =
                    coroutineScope {
                        async { client.executeVia(session, agentId, "GET", "https://api.corp.test/big") }
                            .also {
                                val job = receiveFrame()
                                sendFrame(
                                    "type" to "agent.result",
                                    "jobId" to job["jobId"]!!,
                                    "status" to "200",
                                    "body" to "x".repeat(900_000),
                                )
                            }.await()
                    }

                assertEquals(HttpStatusCode.OK, execution.status)
                assertTrue(execution.bodyAsText().contains("\"statusCode\":200"))
            }
        }

    @Test
    fun `a browser origin can never register as an agent`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) { composerEnabled = true }
            }
            val session = client.exchangeSession(sessions, sessionCodes)
            val agentClient = createClient { install(WebSockets) }

            var rejection: CloseReason? = null
            runCatching {
                agentClient.webSocket(
                    urlString = "/api/v1/composer/agent",
                    request = {
                        header(HttpHeaders.Host, "localhost")
                        header(HttpHeaders.Origin, "http://evil.example")
                        header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    },
                ) {
                    rejection = closeReason.await()
                }
            }

            assertEquals("ORIGIN_REJECTED", rejection?.message)
        }

    @Test
    fun `an agent without a session is refused when browser authentication is required`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    browserSecurity = io.devconsole.api.BrowserSecurity.SESSION_CODE
                }
            }
            val agentClient = createClient { install(WebSockets) }

            var rejection: CloseReason? = null
            runCatching {
                agentClient.webSocket(
                    urlString = "/api/v1/composer/agent",
                    request = { header(HttpHeaders.Host, "localhost") },
                ) {
                    rejection = closeReason.await()
                }
            }

            assertEquals("AUTH_REQUIRED", rejection?.message)
        }

    @Test
    fun `agent routes answer 404 while the composer is disabled`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { protectedDevConsoleModule(sessions, sessionCodes) }
            val session = client.exchangeSession(sessions, sessionCodes)

            val agents =
                client.get("/api/v1/composer/agents") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.NotFound, agents.status)
            assertTrue(agents.bodyAsText().contains("COMPOSER_DISABLED"))
        }

    @Test
    fun `a host outside the device allowlist is rejected before the agent hears about it`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.corp.test")
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)
            val agentClient = createClient { install(WebSockets) }

            agentClient.connectAgent(session) { agentId ->
                val rejected = client.executeVia(session, agentId, "GET", "https://exfiltrate.example/x")

                assertEquals(HttpStatusCode.Forbidden, rejected.status)
                assertTrue(rejected.bodyAsText().contains("COMPOSER_HOST_REJECTED"))
                assertNull(incoming.tryReceive().getOrNull(), "no execute frame may reach the agent")
            }
        }

    @Test
    fun `an unknown or malformed executor answers 409 AGENT_UNAVAILABLE`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.corp.test")
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            listOf("agent:nope", "nope", "agent:").forEach { executor ->
                val response =
                    client.post("/api/v1/composer/execute") {
                        controlRequest(session)
                        setBody(
                            "method=GET&url=https%3A%2F%2Fapi.corp.test%2Fx&executor=${executor.replace(":", "%3A")}",
                        )
                    }

                assertEquals(HttpStatusCode.Conflict, response.status, executor)
                assertTrue(response.bodyAsText().contains("AGENT_UNAVAILABLE"))
            }
        }

    @Test
    fun `an agent that disconnects mid-request fails the request immediately`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.corp.test")
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)
            val agentClient = createClient { install(WebSockets) }

            lateinit var execution: io.ktor.client.statement.HttpResponse
            agentClient.connectAgent(session) { agentId ->
                coroutineScope {
                    val pending = async { client.executeVia(session, agentId, "GET", "https://api.corp.test/slow") }
                    receiveFrame()
                    close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
                    execution = pending.await()
                }
            }

            assertEquals(HttpStatusCode.Conflict, execution.status)
            assertTrue(execution.bodyAsText().contains("AGENT_UNAVAILABLE"))
        }

    @Test
    fun `an agent that reports a failure surfaces its reason without leaking secrets`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.corp.test")
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)
            val agentClient = createClient { install(WebSockets) }

            agentClient.connectAgent(session) { agentId ->
                val execution =
                    coroutineScope {
                        async { client.executeVia(session, agentId, "GET", "https://api.corp.test/down") }
                            .also {
                                val job = receiveFrame()
                                sendFrame(
                                    "type" to "agent.error",
                                    "jobId" to job["jobId"]!!,
                                    "code" to "FAILED",
                                    "message" to
                                        "Could not connect to api.corp.test (connection refused or host unreachable)",
                                )
                            }.await()
                    }

                assertEquals(HttpStatusCode.BadGateway, execution.status)
                val body = execution.bodyAsText()
                assertTrue(body.contains("EXECUTION_FAILED"), body)
                assertTrue(body.contains("Could not connect to api.corp.test"), body)
            }
        }

    @Test
    fun `an agent HOST_REJECTED reply maps to COMPOSER_HOST_REJECTED`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.corp.test")
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)
            val agentClient = createClient { install(WebSockets) }

            agentClient.connectAgent(session) { agentId ->
                val execution =
                    coroutineScope {
                        async { client.executeVia(session, agentId, "GET", "https://api.corp.test/x") }
                            .also {
                                val job = receiveFrame()
                                sendFrame(
                                    "type" to "agent.error",
                                    "jobId" to job["jobId"]!!,
                                    "code" to "HOST_REJECTED",
                                    "message" to "not in --allow-host",
                                )
                            }.await()
                    }

                assertEquals(HttpStatusCode.Forbidden, execution.status)
                assertTrue(execution.bodyAsText().contains("COMPOSER_HOST_REJECTED"))
            }
        }

    @Test
    fun `redirects are re-issued per hop and drop credentials across origins`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.corp.test", "cdn.corp.test")
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)
            val agentClient = createClient { install(WebSockets) }

            agentClient.connectAgent(session) { agentId ->
                val execution =
                    coroutineScope {
                        async {
                            client.post("/api/v1/composer/execute") {
                                controlRequest(session)
                                setBody(
                                    "method=GET&url=https%3A%2F%2Fapi.corp.test%2Fa" +
                                        "&header=Authorization%3A+Bearer+t0k3n&executor=agent%3A$agentId",
                                )
                            }
                        }.also {
                            val first = receiveFrame()
                            assertTrue(first.getAll("header").orEmpty().contains("Authorization:Bearer t0k3n"))
                            sendFrame(
                                "type" to "agent.result",
                                "jobId" to first["jobId"]!!,
                                "status" to "302",
                                "header" to "Location:https://cdn.corp.test/b",
                                "body" to "",
                            )
                            val second = receiveFrame()
                            assertEquals("https://cdn.corp.test/b", second["url"])
                            assertTrue(
                                second
                                    .getAll(
                                        "header",
                                    ).orEmpty()
                                    .none { it.startsWith("Authorization", ignoreCase = true) },
                                "the bearer token must not follow a redirect to another origin",
                            )
                            sendFrame(
                                "type" to "agent.result",
                                "jobId" to second["jobId"]!!,
                                "status" to "200",
                                "body" to "ok",
                            )
                        }.await()
                    }

                assertEquals(HttpStatusCode.OK, execution.status)
                assertTrue(execution.bodyAsText().contains("\"statusCode\":200"))
            }
        }

    @Test
    fun `a request the agent never answers times out with 504`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("api.corp.test")
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)
            val agentClient = createClient { install(WebSockets) }

            agentClient.connectAgent(session) { agentId ->
                val execution =
                    client.post("/api/v1/composer/execute") {
                        controlRequest(session)
                        setBody(
                            "method=GET&url=https%3A%2F%2Fapi.corp.test%2Fhang&timeoutMs=1&executor=agent%3A$agentId",
                        )
                    }

                assertEquals(HttpStatusCode.GatewayTimeout, execution.status)
                assertTrue(execution.bodyAsText().contains("AGENT_TIMEOUT"))
            }
        }
}

private fun assertJobCarriesResolvedRequest(job: Parameters) {
    assertEquals("agent.execute", job["type"])
    assertEquals("POST", job["method"])
    assertEquals("https://api.corp.test/orders", job["url"])
    assertTrue(job.getAll("header").orEmpty().contains("Authorization:Bearer top-secret-token"))
    assertEquals("{\"a\":1}", Base64.getDecoder().decode(job["bodyBase64"]).decodeToString())
    assertEquals("application/json; charset=utf-8", job["contentType"])
}

private suspend fun HttpClient.connectAgent(
    session: BrowserSession,
    name: String = "Test agent",
    block: suspend DefaultClientWebSocketSession.(agentId: String) -> Unit,
) {
    webSocket(
        urlString = "/api/v1/composer/agent",
        request = {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
        },
    ) {
        sendFrame("type" to "agent.hello", "name" to name, "os" to "TestOS", "version" to "1")
        val welcome = receiveFrame()
        assertEquals("agent.welcome", welcome["type"])
        block(welcome["agentId"]!!)
    }
}

private suspend fun DefaultClientWebSocketSession.sendFrame(vararg pairs: Pair<String, String>) =
    send(Frame.Text(pairs.toList().formUrlEncode()))

private suspend fun DefaultClientWebSocketSession.receiveFrame(): Parameters =
    parseQueryString((incoming.receive() as Frame.Text).readText())

private suspend fun HttpClient.executeVia(
    session: BrowserSession,
    agentId: String,
    method: String,
    url: String,
) = post("/api/v1/composer/execute") {
    controlRequest(session)
    setBody(
        listOf("method" to method, "url" to url, "executor" to "agent:$agentId").formUrlEncode(),
    )
}

private fun HttpRequestBuilder.controlRequest(session: BrowserSession) {
    header(HttpHeaders.Host, "localhost")
    header(HttpHeaders.Authorization, "Bearer ${session.token}")
    header(HttpHeaders.Origin, "http://localhost")
    header("X-DevConsole-CSRF", session.csrfToken)
    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
}

private suspend fun HttpClient.exchangeSession(
    sessions: SessionAuthority,
    sessionCodes: SessionCodeAuthority,
): BrowserSession {
    val response =
        post("/api/v1/auth/session-code/exchange") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            setBody("code=${sessionCodes.issueCode().code}&browserLabel=Chrome")
        }
    val token = Regex("\"accessToken\":\"([^\"]+)\"").find(response.bodyAsText())!!.groupValues[1]
    return sessions.sessionForToken(token)!!
}
