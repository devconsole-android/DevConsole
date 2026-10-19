package io.devconsole.server.ktor

import io.devconsole.composer.ComposerExecutor
import io.devconsole.composer.ComposerResponse
import io.devconsole.composer.ComposerTransport
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.InMemoryCommandAuditLog
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerHostRoutesTest {
    private val executor =
        ComposerExecutor(ComposerTransport { ComposerResponse(statusCode = 200, body = "ok") })

    @Test
    fun `a host added from the dashboard is allowed for execution until it is removed`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerHostsEditable = true
                    composerAllowedHosts = setOf("fixed.example.test")
                    composerExecutor = executor
                    commandAuditLog = audit
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            assertEquals(HttpStatusCode.Forbidden, client.execute(session, "https://api.acme.com/v1/ping").status)

            val added = client.addHost(session, "https://api.acme.com/some/path")
            assertEquals(HttpStatusCode.OK, added.status)
            assertTrue(added.bodyAsText().contains("\"host\":\"api.acme.com\",\"added\":true"))
            assertEquals(HttpStatusCode.OK, client.execute(session, "https://api.acme.com/v1/ping").status)

            val listed = client.listHosts(session)
            assertTrue(listed, listed.contains("\"editable\":true"))
            assertTrue(listed, listed.contains("{\"host\":\"fixed.example.test\",\"source\":\"app\"}"))
            assertTrue(listed, listed.contains("{\"host\":\"api.acme.com\",\"source\":\"session\"}"))

            val removed = client.delete("/api/v1/composer/hosts/api.acme.com") { controlRequest(session) }
            assertEquals(HttpStatusCode.OK, removed.status)
            assertEquals(HttpStatusCode.Forbidden, client.execute(session, "https://api.acme.com/v1/ping").status)
            assertEquals(
                listOf("composer.host.add", "composer.host.remove"),
                audit.events().map { it.commandType }.filter { it.startsWith("composer.host") },
            )
        }

    @Test
    fun `hosts the app configured cannot be removed from the dashboard`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerHostsEditable = true
                    composerAllowedHosts = setOf("fixed.example.test")
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val response = client.delete("/api/v1/composer/hosts/fixed.example.test") { controlRequest(session) }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("COMPOSER_HOST_SET_BY_APP"))
        }

    @Test
    fun `when the app has not made the allowlist editable every change is refused`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerAllowedHosts = setOf("fixed.example.test")
                    commandAuditLog = audit
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            val added = client.addHost(session, "api.acme.com")

            assertEquals(HttpStatusCode.Forbidden, added.status)
            assertTrue(added.bodyAsText().contains("COMPOSER_HOSTS_READ_ONLY"))
            assertTrue(client.listHosts(session).contains("\"editable\":false"))
            assertFalse(client.listHosts(session).contains("api.acme.com"))
            assertEquals(HttpStatusCode.Forbidden, client.execute(session, "https://api.acme.com/x").status)
            assertTrue(audit.events().any { it.commandType == "composer.host.add" })
        }

    @Test
    fun `invalid hosts and missing csrf are rejected`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerHostsEditable = true
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)

            assertEquals(HttpStatusCode.BadRequest, client.addHost(session, "*.acme.com").status)
            assertEquals(HttpStatusCode.BadRequest, client.addHost(session, "").status)
            val noCsrf =
                client.post("/api/v1/composer/hosts") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("host=api.acme.com")
                }
            assertEquals(HttpStatusCode.Forbidden, noCsrf.status)
            assertFalse(client.listHosts(session).contains("api.acme.com"))
        }

    @Test
    fun `the host routes answer 404 while the composer is disabled`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) { composerHostsEditable = true } }
            val session = client.exchangeSession(sessions, sessionCodes)

            assertEquals(HttpStatusCode.NotFound, client.addHost(session, "api.acme.com").status)
        }

    @Test
    fun `meta reports session hosts alongside the configured allowlist`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    composerEnabled = true
                    composerHostsEditable = true
                    composerAllowedHosts = setOf("b.example.test")
                }
            }
            val session = client.exchangeSession(sessions, sessionCodes)
            client.addHost(session, "a.example.test")

            val meta =
                client
                    .get("/api/v1/meta") {
                        header(HttpHeaders.Host, "localhost")
                        header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    }.bodyAsText()

            assertTrue(meta, meta.contains("\"allowedHosts\":[\"a.example.test\",\"b.example.test\"]"))
        }
}

private suspend fun HttpClient.addHost(
    session: BrowserSession,
    host: String,
): HttpResponse =
    post("/api/v1/composer/hosts") {
        controlRequest(session)
        setBody(listOf("host" to host).formUrlEncode())
    }

private suspend fun HttpClient.listHosts(session: BrowserSession): String =
    get("/api/v1/composer/hosts") {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Authorization, "Bearer ${session.token}")
    }.bodyAsText()

private suspend fun HttpClient.execute(
    session: BrowserSession,
    url: String,
): HttpResponse =
    post("/api/v1/composer/execute") {
        controlRequest(session)
        setBody(listOf("method" to "GET", "url" to url).formUrlEncode())
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
