/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.server.ktor

import io.devconsole.api.CaptureRule
import io.devconsole.api.CaptureRuleEngine
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
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

/**
 * The `/api/v1/capture-rules` route family (list/create/enable-toggle/delete) had zero coverage at
 * the HTTP layer before this file -- [io.devconsole.api.CaptureRuleEngineTest] thoroughly covers the
 * engine itself (host-shape validation, matcher precedence, enable/disable, persistence), but never
 * exercised through Ktor routing, auth, CSRF, the `captureRules` editing capability, or rate
 * limiting. Follows the fixture conventions from [InspectorRoutesTest]: same
 * `approvedSession`/`controlHeaders` pattern, same `testApplication` setup.
 */
class CaptureRuleRoutesTest {
    @Test
    fun `unauthenticated requests are rejected across every capture-rule route`() =
        testApplication {
            application { devConsoleModule(SessionAuthority()) }

            val unauthenticated =
                listOf(
                    suspend { client.get("/api/v1/capture-rules") { header(HttpHeaders.Host, "localhost") } },
                    suspend { client.post("/api/v1/capture-rules") { header(HttpHeaders.Host, "localhost") } },
                    suspend {
                        client.post("/api/v1/capture-rules/r/enabled") { header(HttpHeaders.Host, "localhost") }
                    },
                    suspend { client.delete("/api/v1/capture-rules/r") { header(HttpHeaders.Host, "localhost") } },
                )

            unauthenticated.forEach { call ->
                val response = call()
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertTrue(response.bodyAsText().contains("AUTH_REQUIRED"))
            }
        }

    @Test
    fun `authenticated browser lists capture rules with the editable flag`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val engine = CaptureRuleEngine(listOf(CaptureRule.of(id = "r", host = "api.example.test")))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    captureRules = engine
                    captureRulesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/capture-rules") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"editable\":true"))
            assertTrue(body.contains("\"id\":\"r\""))
            assertTrue(body.contains("\"host\":\"api.example.test\""))
        }

    @Test
    fun `create requires a valid csrf token and the captureRules editing capability`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val engine = CaptureRuleEngine()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    captureRules = engine
                    captureRulesEditable = false
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val missingCsrf =
                client.post("/api/v1/capture-rules") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("id=r&host=api.example.test")
                }
            val disabledCapability =
                client.post("/api/v1/capture-rules") {
                    controlHeaders(session)
                    setBody("id=r&host=api.example.test")
                }

            assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)
            assertTrue(missingCsrf.bodyAsText().contains("CSRF_INVALID"))
            assertEquals(HttpStatusCode.Forbidden, disabledCapability.status)
            assertTrue(disabledCapability.bodyAsText().contains("CAPTURE_RULES_DISABLED"))
            assertTrue("engine must not be touched when the capability is disabled", engine.rules().isEmpty())
        }

    @Test
    fun `create succeeds when editable and rejects an invalid host as validation failure`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val engine = CaptureRuleEngine()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    captureRules = engine
                    captureRulesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val created =
                client.post("/api/v1/capture-rules") {
                    controlHeaders(session)
                    setBody("id=r&host=api.example.test&method=post&pathPrefix=%2Forders")
                }
            val invalidHost =
                client.post("/api/v1/capture-rules") {
                    controlHeaders(session)
                    setBody("id=bad&host=*.example.test")
                }

            assertEquals(HttpStatusCode.Created, created.status)
            val body = created.bodyAsText()
            assertTrue(body.contains("\"id\":\"r\""))
            assertTrue(body.contains("\"host\":\"api.example.test\""))
            assertTrue("method is normalized to uppercase", body.contains("\"method\":\"POST\""))
            assertEquals(HttpStatusCode.BadRequest, invalidHost.status)
            assertTrue(invalidHost.bodyAsText().contains("VALIDATION_FAILED"))
            assertEquals(listOf("r"), engine.rules().map { it.id })
        }

    @Test
    fun `enabled toggle requires the capability and reports unknown ids as validation failures`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val engine = CaptureRuleEngine(listOf(CaptureRule.of(id = "r", host = "api.example.test")))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    captureRules = engine
                    captureRulesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val toggled =
                client.post("/api/v1/capture-rules/r/enabled") {
                    controlHeaders(session)
                    setBody("false")
                }
            val unknownId =
                client.post("/api/v1/capture-rules/missing/enabled") {
                    controlHeaders(session)
                    setBody("true")
                }

            assertEquals(HttpStatusCode.OK, toggled.status)
            assertFalse(engine.rules().single().enabled)
            // setEnabled() on an unknown id returns false with no distinct "not found" signal at
            // this route -- it is reported the same as any other rejected mutation.
            assertEquals(HttpStatusCode.BadRequest, unknownId.status)
            assertTrue(unknownId.bodyAsText().contains("VALIDATION_FAILED"))
        }

    @Test
    fun `delete requires the capability and reports unknown ids as not found`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val engine = CaptureRuleEngine(listOf(CaptureRule.of(id = "r", host = "api.example.test")))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    captureRules = engine
                    captureRulesEditable = false
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val disabledCapability =
                client.delete("/api/v1/capture-rules/r") { controlHeaders(session) }

            assertEquals(HttpStatusCode.Forbidden, disabledCapability.status)
            assertTrue(disabledCapability.bodyAsText().contains("CAPTURE_RULES_DISABLED"))
            assertEquals(1, engine.rules().size)
        }

    @Test
    fun `delete removes an existing rule and reports a second delete as not found`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val engine = CaptureRuleEngine(listOf(CaptureRule.of(id = "r", host = "api.example.test")))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    captureRules = engine
                    captureRulesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val deleted = client.delete("/api/v1/capture-rules/r") { controlHeaders(session) }
            val deletedAgain = client.delete("/api/v1/capture-rules/r") { controlHeaders(session) }

            assertEquals(HttpStatusCode.OK, deleted.status)
            assertTrue(engine.rules().isEmpty())
            assertEquals(HttpStatusCode.NotFound, deletedAgain.status)
            assertTrue(deletedAgain.bodyAsText().contains("NOT_FOUND"))
        }

    @Test
    fun `capture-rule mutations are rate limited per browser principal`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    captureRules = CaptureRuleEngine()
                    captureRulesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            repeat(30) {
                assertFalse(
                    "attempt $it should not be rate limited",
                    client
                        .post("/api/v1/capture-rules") {
                            controlHeaders(session)
                            setBody("id=rule$it&host=h$it.example.test")
                        }.status == HttpStatusCode.TooManyRequests,
                )
            }
            val limited =
                client.post("/api/v1/capture-rules") {
                    controlHeaders(session)
                    setBody("id=onemore&host=onemore.example.test")
                }

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertTrue(limited.bodyAsText().contains("RATE_LIMITED"))
        }
}

/**
 * Mints an authenticated [BrowserSession] the only way an external caller can: issue a session code
 * and exchange it over the real HTTP route. Mirrors [InspectorRoutesTest]'s private helper of the
 * same name (file-scoped, so duplicated rather than shared).
 */
private suspend fun ApplicationTestBuilder.approvedSession(
    sessions: SessionAuthority,
    sessionCodes: SessionCodeAuthority,
): BrowserSession {
    val code = sessionCodes.issueCode().code
    val response =
        client.post("/api/v1/auth/session-code/exchange") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            setBody("code=$code")
        }
    val token = Regex("\"accessToken\":\"([^\"]+)\"").find(response.bodyAsText())!!.groupValues[1]
    return sessions.sessionForToken(token)!!
}

private fun HttpRequestBuilder.controlHeaders(session: BrowserSession) {
    header(HttpHeaders.Host, "localhost")
    header(HttpHeaders.Authorization, "Bearer ${session.token}")
    header(HttpHeaders.Origin, "http://localhost")
    header("X-DevConsole-CSRF", session.csrfToken)
    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
}
