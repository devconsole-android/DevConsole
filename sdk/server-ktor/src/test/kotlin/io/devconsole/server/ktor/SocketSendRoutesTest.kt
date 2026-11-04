/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.server.ktor

import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.InMemoryCommandAuditLog
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.SocketConnection
import io.devconsole.socket.SocketProtocol
import io.devconsole.socket.SocketSendRequest
import io.devconsole.socket.SocketSendResult
import io.devconsole.socket.SocketSender
import io.devconsole.socket.SocketSenderRegistry
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketSendRoutesTest {
    @Test
    fun `sending requires a bearer session`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { protectedDevConsoleModule(sessions, sessionCodes) }

            val response =
                client.post("/api/v1/websockets/connections/socket-1/send") {
                    header(HttpHeaders.Host, "localhost")
                    setBody("text=hi")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("{\"code\":\"AUTH_REQUIRED\"}", response.bodyAsText())
        }

    @Test
    fun `connection listings report which connections can be written to`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val sockets = InMemorySocketStore()
            sockets.open(SocketConnection("socket-1", "wss://api.test/socket", 1))
            sockets.open(SocketConnection("socket-2", "wss://api.test/other", 2))
            val senders = SocketSenderRegistry()
            senders.register("socket-1", SocketProtocol.WEBSOCKET, FakeSocketSender())
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    socketStore = sockets
                    socketSenders = senders
                    composerEnabled = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val list = client.get("/api/v1/websockets/connections") { authHeaders(session) }
            val detail =
                client.get("/api/v1/websockets/connections/socket-1") { authHeaders(session) }
            val sendable = client.get("/api/v1/websockets/connections/socket-2") { authHeaders(session) }

            assertEquals(HttpStatusCode.OK, list.status)
            assertTrue(list.bodyAsText().contains("\"canSend\":true,\"sendProtocol\":\"websocket\""))
            assertTrue(list.bodyAsText().contains("\"canSend\":false,\"sendProtocol\":null"))
            assertTrue(detail.bodyAsText().contains("\"canSend\":true,\"sendProtocol\":\"websocket\""))
            assertTrue(sendable.bodyAsText().contains("\"canSend\":false,\"sendProtocol\":null"))
        }

    @Test
    fun `sending passes the frame and MQTT fields to the registered sender and audits it`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val senders = SocketSenderRegistry()
            val sender = FakeSocketSender()
            senders.register("socket-1", SocketProtocol.MQTT, sender)
            val audit = InMemoryCommandAuditLog()
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    socketSenders = senders
                    commandAuditLog = audit
                    composerEnabled = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/websockets/connections/socket-1/send") {
                    controlHeaders(session)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("text=hello&topic=sensors%2F1&qos=1&retained=true")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("{\"status\":\"sent\"}", response.bodyAsText())
            assertEquals(SocketSendRequest("hello", "sensors/1", 1, true), sender.lastRequest)
            val event = audit.events().single()
            assertEquals("socket.send", event.commandType)
            assertEquals("socket-1", event.target)
            assertEquals("5", event.parameters["bytes"])
            assertEquals("sensors/1", event.parameters["topic"])
            assertTrue("the frame text must never be audited", event.parameters.values.none { it.contains("hello") })
        }

    @Test
    fun `a disconnected sender answers NOT_CONNECTED`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val senders = SocketSenderRegistry()
            senders.register("socket-1", SocketProtocol.WEBSOCKET, FakeSocketSender(SocketSendResult.NotConnected))
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    socketSenders = senders
                    composerEnabled = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response = sendFrame(session, "socket-1", "text=hi")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("{\"code\":\"NOT_CONNECTED\"}", response.bodyAsText())
        }

    @Test
    fun `a sender rejection answers SEND_REJECTED with its reason`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val senders = SocketSenderRegistry()
            senders.register(
                "socket-1",
                SocketProtocol.WEBSOCKET,
                FakeSocketSender(SocketSendResult.Rejected("queue full")),
            )
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    socketSenders = senders
                    composerEnabled = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response = sendFrame(session, "socket-1", "text=hi")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("{\"code\":\"SEND_REJECTED\",\"message\":\"queue full\"}", response.bodyAsText())
        }

    @Test
    fun `a body without a text field is rejected`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val senders = SocketSenderRegistry()
            val sender = FakeSocketSender()
            senders.register("socket-1", SocketProtocol.WEBSOCKET, sender)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    socketSenders = senders
                    composerEnabled = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response = sendFrame(session, "socket-1", "topic=sensors%2F1")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("{\"code\":\"VALIDATION_FAILED\"}", response.bodyAsText())
            assertNull(sender.lastRequest)
        }

    @Test
    fun `a non-numeric qos is rejected before reaching the sender`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val senders = SocketSenderRegistry()
            val sender = FakeSocketSender()
            senders.register("socket-1", SocketProtocol.MQTT, sender)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    socketSenders = senders
                    composerEnabled = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response = sendFrame(session, "socket-1", "text=hi&topic=a&qos=high")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("{\"code\":\"VALIDATION_FAILED\"}", response.bodyAsText())
            assertNull(sender.lastRequest)
        }

    @Test
    fun `an unwired registry answers SEND_UNSUPPORTED`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) { composerEnabled = true }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response = sendFrame(session, "socket-1", "text=hi")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("{\"code\":\"SEND_UNSUPPORTED\"}", response.bodyAsText())
        }
}

private class FakeSocketSender(
    private val result: SocketSendResult = SocketSendResult.Sent,
) : SocketSender {
    var lastRequest: SocketSendRequest? = null
        private set

    override fun send(request: SocketSendRequest): SocketSendResult {
        lastRequest = request
        return result
    }
}

private suspend fun ApplicationTestBuilder.sendFrame(
    session: BrowserSession,
    connectionId: String,
    body: String,
) = client.post("/api/v1/websockets/connections/$connectionId/send") {
    controlHeaders(session)
    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
    setBody(body)
}

/** Bearer-only headers for a plain GET -- no Origin/CSRF, since a read route never needs them. */
private fun HttpRequestBuilder.authHeaders(session: BrowserSession) {
    header(HttpHeaders.Host, "localhost")
    header(HttpHeaders.Authorization, "Bearer ${session.token}")
}

private fun HttpRequestBuilder.controlHeaders(session: BrowserSession) {
    header(HttpHeaders.Host, "localhost")
    header(HttpHeaders.Authorization, "Bearer ${session.token}")
    header(HttpHeaders.Origin, "http://localhost")
    header("X-DevConsole-CSRF", session.csrfToken)
}

private suspend fun ApplicationTestBuilder.approvedSession(
    sessions: SessionAuthority,
    sessionCodes: SessionCodeAuthority,
): BrowserSession {
    val response =
        client.post("/api/v1/auth/session-code/exchange") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            setBody("code=${sessionCodes.issueCode().code}")
        }
    val token = Regex("\"accessToken\":\"([^\"]+)\"").find(response.bodyAsText())!!.groupValues[1]
    return sessions.sessionForToken(token)!!
}
