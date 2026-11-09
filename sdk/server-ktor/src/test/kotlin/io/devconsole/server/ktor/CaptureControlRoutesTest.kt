/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.server.ktor

import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.InMemoryCommandAuditLog
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureControlRoutesTest {
    @Test
    fun `reading capture state requires a bearer session`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { protectedDevConsoleModule(sessions, sessionCodes) }

            val response = client.get("/api/v1/capture") { header(HttpHeaders.Host, "localhost") }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("{\"code\":\"AUTH_REQUIRED\"}", response.bodyAsText())
        }

    @Test
    fun `reading capture state reflects the injected control`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    captureControl = FakeCaptureControl(paused = true)
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response = client.get("/api/v1/capture") { authHeaders(session) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("{\"paused\":true,\"available\":true}", response.bodyAsText())
        }

    @Test
    fun `pausing then resuming returns the new state and audits both commands`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val control = FakeCaptureControl()
            val audit = InMemoryCommandAuditLog()
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    captureControl = control
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val paused =
                client.post("/api/v1/capture/paused") {
                    controlHeaders(session)
                    setBody("true")
                }
            assertEquals(HttpStatusCode.OK, paused.status)
            assertEquals("{\"paused\":true}", paused.bodyAsText())
            assertTrue(control.isPaused())

            val resumed =
                client.post("/api/v1/capture/paused") {
                    controlHeaders(session)
                    setBody("\"false\"")
                }
            assertEquals(HttpStatusCode.OK, resumed.status)
            assertEquals("{\"paused\":false}", resumed.bodyAsText())
            assertFalse(control.isPaused())

            val events = audit.events()
            assertEquals(listOf("capture.pause", "capture.resume"), events.map { it.commandType })
            assertTrue(events.all { it.target == "capture" })
        }

    @Test
    fun `a mutation without origin and csrf headers is refused`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val control = FakeCaptureControl()
            application {
                protectedDevConsoleModule(sessions, sessionCodes) { captureControl = control }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/capture/paused") {
                    authHeaders(session)
                    setBody("true")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals("{\"code\":\"CSRF_INVALID\"}", response.bodyAsText())
            assertFalse("the control must stay untouched", control.isPaused())
        }

    @Test
    fun `an unrecognised body is rejected without touching the control`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val control = FakeCaptureControl()
            application {
                protectedDevConsoleModule(sessions, sessionCodes) { captureControl = control }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/capture/paused") {
                    controlHeaders(session)
                    setBody("maybe")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("{\"code\":\"VALIDATION_FAILED\"}", response.bodyAsText())
            assertFalse(control.isPaused())
        }

    @Test
    fun `purging reports the removed counts and audits them`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    captureControl =
                        FakeCaptureControl(summary = CapturePurgeSummary(2, 3, 4, 5))
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response = client.post("/api/v1/capture/purge") { controlHeaders(session) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "{\"networkTransactions\":2,\"socketConnections\":3," +
                    "\"pushEvents\":4,\"retainedRunsDeleted\":5}",
                response.bodyAsText(),
            )
            val event = audit.events().single()
            assertEquals("capture.purge", event.commandType)
            assertEquals("capture", event.target)
            assertEquals("2", event.parameters["networkTransactions"])
            assertEquals("3", event.parameters["socketConnections"])
            assertEquals("4", event.parameters["pushEvents"])
            assertEquals("5", event.parameters["retainedRunsDeleted"])
        }

    @Test
    fun `an absent control reports unavailable and refuses mutations`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { protectedDevConsoleModule(sessions, sessionCodes) }
            val session = approvedSession(sessions, sessionCodes)

            val state = client.get("/api/v1/capture") { authHeaders(session) }
            assertEquals(HttpStatusCode.OK, state.status)
            assertEquals("{\"paused\":false,\"available\":false}", state.bodyAsText())

            val paused =
                client.post("/api/v1/capture/paused") {
                    controlHeaders(session)
                    setBody("true")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, paused.status)
            assertEquals(
                "{\"code\":\"CAPTURE_CONTROL_UNAVAILABLE\"}",
                paused.bodyAsText(),
            )

            val purge = client.post("/api/v1/capture/purge") { controlHeaders(session) }
            assertEquals(HttpStatusCode.ServiceUnavailable, purge.status)
            assertEquals(
                "{\"code\":\"CAPTURE_CONTROL_UNAVAILABLE\"}",
                purge.bodyAsText(),
            )
        }
}

private class FakeCaptureControl(
    private var paused: Boolean = false,
    private val summary: CapturePurgeSummary = CapturePurgeSummary(0, 0, 0, 0),
) : CaptureControl {
    override fun isPaused(): Boolean = paused

    override fun setPaused(paused: Boolean): Boolean {
        this.paused = paused
        return true
    }

    override suspend fun purge(): CapturePurgeSummary = summary
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
