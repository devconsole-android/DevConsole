/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.server.ktor

import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.devconsole.server.api.SessionPolicy
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SESSION_CODE is the only browser-access flow -- there is no more `AccessMode` to gate on, so
 * every test here exercises `devConsoleModule(sessionAuthority, sessionCodeAuthority)` directly
 * rather than opting into a mode.
 */
class SessionCodeRoutesTest {
    @Test
    fun `valid code exchanges directly for a session with no approval round trip`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val info = sessionCodes.issueCode()
            application { devConsoleModule(sessions, sessionCodes) }

            val response =
                client.post("/api/v1/auth/session-code/exchange") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("code=${info.code}&browserLabel=Chrome")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("accessToken"))
            assertTrue(body.contains("csrfToken"))
            // Single access tier: the exchange response no longer carries an "access" field.
            assertFalse(body.contains("\"access\""))
            val cookie =
                response.headers
                    .getAll(HttpHeaders.SetCookie)
                    .orEmpty()
                    .joinToString(";")
            assertTrue(cookie.contains("DevConsoleStreamSession="))
        }

    @Test
    fun `code is single-use so a second exchange fails`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val info = sessionCodes.issueCode()
            application { devConsoleModule(sessions, sessionCodes) }

            suspend fun exchange() =
                client.post("/api/v1/auth/session-code/exchange") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("code=${info.code}")
                }

            val first = exchange()
            val second = exchange()

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.Unauthorized, second.status)
            assertTrue(second.bodyAsText().contains("SESSION_CODE_INVALID"))
        }

    @Test
    fun `unknown code is rejected`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            sessionCodes.issueCode()
            application { devConsoleModule(sessions, sessionCodes) }

            val response =
                client.post("/api/v1/auth/session-code/exchange") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("code=WRONGONE")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("SESSION_CODE_INVALID"))
        }

    @Test
    fun `expired code is rejected and never falls back to a new session`() =
        testApplication {
            var now = 0L
            val sessions = SessionAuthority(nowEpochMs = { now })
            val sessionCodes = SessionCodeAuthority(sessions, nowEpochMs = { now }, codeTtlMs = 10L)
            val info = sessionCodes.issueCode()
            application { devConsoleModule(sessions, sessionCodes) }
            now = 11L

            val response =
                client.post("/api/v1/auth/session-code/exchange") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("code=${info.code}")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("SESSION_CODE_EXPIRED"))
        }

    @Test
    fun `session-code exchange is rate limited per source IP`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }

            suspend fun attempt() =
                client.post("/api/v1/auth/session-code/exchange") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("code=WRONGONE")
                }

            repeat(5) { attempt() }
            val limited = attempt()

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertTrue(limited.bodyAsText().contains("RATE_LIMITED"))
        }

    @Test
    fun `a session-code issued session still requires CSRF and Origin for mutations`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val info = sessionCodes.issueCode()
            application { devConsoleModule(sessions, sessionCodes) }
            val exchanged =
                client.post("/api/v1/auth/session-code/exchange") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("code=${info.code}")
                }
            val token = Regex("\"accessToken\":\"([^\"]+)\"").find(exchanged.bodyAsText())!!.groupValues[1]
            val csrf = Regex("\"csrfToken\":\"([^\"]+)\"").find(exchanged.bodyAsText())!!.groupValues[1]

            val withoutCsrf =
                client.post("/api/v1/mocks/disable-all") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Origin, "http://localhost")
                }
            val withCsrf =
                client.post("/api/v1/mocks/disable-all") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", csrf)
                }
            assertEquals(HttpStatusCode.Forbidden, withoutCsrf.status)
            assertTrue(withoutCsrf.bodyAsText().contains("CSRF_INVALID"))
            assertEquals(HttpStatusCode.OK, withCsrf.status)
        }

    @Test
    fun `rotate requires authentication and control csrf`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val exchanged =
                client.post("/api/v1/auth/session-code/exchange") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("code=${sessionCodes.issueCode().code}")
                }
            val token = Regex("\"accessToken\":\"([^\"]+)\"").find(exchanged.bodyAsText())!!.groupValues[1]
            val csrf = Regex("\"csrfToken\":\"([^\"]+)\"").find(exchanged.bodyAsText())!!.groupValues[1]

            val unauthenticated =
                client.post("/api/v1/auth/session-code/rotate") {
                    header(HttpHeaders.Host, "localhost")
                }
            val missingCsrf =
                client.post("/api/v1/auth/session-code/rotate") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Origin, "http://localhost")
                }
            val rotated =
                client.post("/api/v1/auth/session-code/rotate") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", csrf)
                }

            assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
            assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)
            assertTrue(missingCsrf.bodyAsText().contains("CSRF_INVALID"))
            assertEquals(HttpStatusCode.OK, rotated.status)
            val body = rotated.bodyAsText()
            assertTrue(body.contains("\"browserUrl\""))
            assertTrue(body.contains("\"code\""))
            assertTrue(body.contains("\"expiresAtEpochMs\""))
        }

    @Test
    fun `rotate invalidates whatever code was previously live and the new one exchanges normally`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val exchanged =
                client.post("/api/v1/auth/session-code/exchange") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("code=${sessionCodes.issueCode().code}")
                }
            val token = Regex("\"accessToken\":\"([^\"]+)\"").find(exchanged.bodyAsText())!!.groupValues[1]
            val csrf = Regex("\"csrfToken\":\"([^\"]+)\"").find(exchanged.bodyAsText())!!.groupValues[1]
            // A code issued directly against the authority, standing in for one displayed on the
            // device but never exchanged -- exactly the "stale but still live" case rotate must kill.
            val staleCode = sessionCodes.issueCode().code

            val rotated =
                client.post("/api/v1/auth/session-code/rotate") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", csrf)
                }
            val newCode = Regex("\"code\":\"([^\"]+)\"").find(rotated.bodyAsText())!!.groupValues[1]

            val staleExchange =
                client.post("/api/v1/auth/session-code/exchange") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("code=$staleCode")
                }
            val freshExchange =
                client.post("/api/v1/auth/session-code/exchange") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("code=$newCode")
                }

            assertEquals(HttpStatusCode.Unauthorized, staleExchange.status)
            assertTrue(staleExchange.bodyAsText().contains("SESSION_CODE_INVALID"))
            assertEquals(HttpStatusCode.OK, freshExchange.status)
        }

    @Test
    fun `session-count limits reject an exchange over capacity with a 409 and still consume the code`() =
        testApplication {
            val sessions = SessionAuthority()
            assertTrue(sessions.configurePolicy(SessionPolicy(maxAuthenticatedSessions = 1)))
            val sessionCodes = SessionCodeAuthority(sessions)
            val first = sessionCodes.issueCode()
            application { devConsoleModule(sessions, sessionCodes) }
            client.post("/api/v1/auth/session-code/exchange") {
                header(HttpHeaders.Host, "localhost")
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                setBody("code=${first.code}")
            }
            val second = sessionCodes.issueCode()

            val overCapacity =
                client.post("/api/v1/auth/session-code/exchange") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("code=${second.code}")
                }

            assertEquals(HttpStatusCode.Conflict, overCapacity.status)
            assertTrue(overCapacity.bodyAsText().contains("SESSION_CODE_SESSION_LIMIT"))

            val retry =
                client.post("/api/v1/auth/session-code/exchange") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("code=${second.code}")
                }
            assertEquals(HttpStatusCode.Unauthorized, retry.status)
            assertTrue(retry.bodyAsText().contains("SESSION_CODE_INVALID"))
        }
}
