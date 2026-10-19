/**
 * @author Shakib
 * @since 19/10/26
 */
package io.devconsole.server.ktor

import io.devconsole.api.BrowserSecurity
import io.devconsole.api.DevConsoleConfig
import io.devconsole.api.NetworkProtection
import io.devconsole.composer.ComposerExecutor
import io.devconsole.composer.ComposerResponse
import io.devconsole.composer.ComposerTransport
import io.devconsole.network.BodyPreview
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkExport
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkTransaction
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.ktor.client.request.get
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

class SecurityOptInTest {
    @Test
    fun `default config preserves authorization cookies token fields and export values`() {
        val config = DevConsoleConfig()
        val capture = capture(config)

        assertEquals("Bearer header-secret", capture.request.headers["Authorization"])
        assertEquals("session=cookie-secret", capture.request.headers["Cookie"])
        assertEquals("query-secret", capture.request.url.query["access_token"])
        assertEquals("""{"token":"body-secret","":"empty-key"}""", (capture.request.body as BodyPreview.Text).value)
        val transaction = NetworkTransaction("default", 0, 1, capture)
        listOf(
            NetworkExport.toCurl(capture),
            NetworkExport.toHarTransactions(listOf(transaction)),
            NetworkExport.toPostman(listOf(transaction)),
        ).forEach { output ->
            assertTrue(output.contains("header-secret"))
            assertTrue(output.contains("cookie-secret"))
            assertTrue(output.contains("body-secret"))
        }
    }

    @Test
    fun `recommended config masks historical fields while preserving authorization as before`() {
        val config = DevConsoleConfig(redactionPolicy = RedactionPolicy.recommended())
        val capture = capture(config)

        // Authorization was intentionally absent from the old built-in set, and remains absent.
        assertEquals("Bearer header-secret", capture.request.headers["Authorization"])
        assertEquals("<redacted>", capture.request.headers["Cookie"])
        assertEquals("<redacted>", capture.request.url.query["access_token"])
        assertFalse((capture.request.body as BodyPreview.Text).value.contains("body-secret"))
        val transaction = NetworkTransaction("recommended", 0, 1, capture)
        listOf(
            NetworkExport.toCurl(capture),
            NetworkExport.toHarTransactions(listOf(transaction)),
            NetworkExport.toPostman(listOf(transaction)),
        ).forEach { output ->
            assertFalse(output.contains("cookie-secret"))
            assertFalse(output.contains("body-secret"))
        }
    }

    @Test
    fun `hosts can explicitly add authorization to the recommended policy`() {
        val recommended = RedactionPolicy.recommended()
        val config =
            DevConsoleConfig(
                redactionPolicy =
                    recommended.copy(
                        sensitiveFieldNames =
                            recommended.sensitiveFieldNames + "authorization",
                    ),
            )
        assertEquals("<redacted>", capture(config).request.headers["Authorization"])
    }

    @Test
    fun `default Composer permits an arbitrary destination and preserves response metadata`() =
        testApplication {
            var destination = ""
            application {
                devConsoleModule(SessionAuthority()) {
                    composerExecutor =
                        ComposerExecutor(
                            ComposerTransport { request ->
                                destination = request.url
                                ComposerResponse(statusCode = 200)
                            },
                        )
                }
            }
            val response =
                client.post("/api/v1/composer/execute") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("method=GET&url=https%3A%2F%2Fanywhere.example%2Forders%3Ftoken%3Draw-token")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("https://anywhere.example/orders?token=raw-token", destination)
            assertTrue(response.bodyAsText().contains("raw-token"))
        }

    @Test
    fun `default server accepts 200 rapid reads with an unknown Host`() =
        testApplication {
            application { devConsoleModule(SessionAuthority()) }
            repeat(200) {
                val response = client.get("/api/v1/meta") { header(HttpHeaders.Host, "unknown.example:8080") }
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.headers["Content-Security-Policy"].orEmpty().contains("ws://unknown.example:8080"))
            }
        }

    @Test
    fun `rate limiting alone enforces the read budget without requiring an allowed Host`() =
        testApplication {
            application {
                devConsoleModule(SessionAuthority()) {
                    networkProtection = NetworkProtection(rateLimiting = true)
                }
            }
            val statuses =
                (1..200).map {
                    client.get("/api/v1/meta") { header(HttpHeaders.Host, "unknown.example") }.status
                }
            assertTrue(statuses.contains(HttpStatusCode.OK))
            assertTrue(statuses.contains(HttpStatusCode.TooManyRequests))
            assertFalse(statuses.contains(HttpStatusCode.Forbidden))
        }

    @Test
    fun `Host checks alone reject unknown hosts and permit 200 allowed reads`() =
        testApplication {
            application {
                devConsoleModule(SessionAuthority()) {
                    networkProtection = NetworkProtection(hostAndOriginChecks = true)
                }
            }
            val rejected = client.get("/api/v1/meta") { header(HttpHeaders.Host, "unknown.example") }
            assertEquals(HttpStatusCode.Forbidden, rejected.status)
            assertTrue(rejected.bodyAsText().contains("ORIGIN_REJECTED"))
            repeat(200) {
                assertEquals(
                    HttpStatusCode.OK,
                    client.get("/api/v1/meta") { header(HttpHeaders.Host, "localhost") }.status,
                )
            }
        }

    @Test
    fun `meta reports default redaction and network protection`() =
        testApplication {
            application { devConsoleModule(SessionAuthority()) }
            val body = client.get("/api/v1/meta").bodyAsText()
            assertTrue(body.contains("\"redaction\":{\"enabled\":false"))
            assertTrue(body.contains("\"networkProtection\":{\"rateLimiting\":false,\"hostAndOriginChecks\":false}"))
            assertTrue(body.contains("\"sensitiveFieldNames\":[]"))
            assertTrue(body.contains("\"composer\":{\"enabled\":true,\"allowedHosts\":[]}"))
        }

    @Test
    fun `meta reports text only redaction and recommended protection`() =
        testApplication {
            application {
                devConsoleModule(SessionAuthority()) {
                    redactionPolicy = RedactionPolicy(emptySet(), textPatterns = listOf(Regex("private")))
                    networkProtection = NetworkProtection.recommended()
                }
            }
            val body = client.get("/api/v1/meta") { header(HttpHeaders.Host, "localhost") }.bodyAsText()
            assertTrue(body.contains("\"redaction\":{\"enabled\":true"))
            assertTrue(body.contains("\"networkProtection\":{\"rateLimiting\":true,\"hostAndOriginChecks\":true}"))
        }

    @Test
    fun `default mutations accept a foreign Origin`() =
        testApplication {
            application { devConsoleModule(SessionAuthority()) }
            val response =
                client.post("/api/v1/session/stop") {
                    header(HttpHeaders.Host, "unknown.example")
                    header(HttpHeaders.Origin, "https://elsewhere.example")
                }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }

    @Test
    fun `opted in Origin checks reject foreign mutations in open mode`() =
        testApplication {
            application {
                devConsoleModule(SessionAuthority()) {
                    networkProtection = NetworkProtection(hostAndOriginChecks = true)
                }
            }
            val response =
                client.post("/api/v1/session/stop") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Origin, "https://elsewhere.example")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("ORIGIN_REJECTED"))
        }

    @Test
    fun `session code keeps auth csrf and attempt limits when network protection is off`() =
        testApplication {
            val sessions = SessionAuthority()
            val codes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, codes) { browserSecurity = BrowserSecurity.SESSION_CODE }
            }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/meta").status)
            val exchange =
                client.post("/api/v1/auth/session-code/exchange") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("code=${codes.issueCode().code}&browserLabel=Test")
                }
            assertEquals(HttpStatusCode.OK, exchange.status)
            val token = Regex("\"accessToken\":\"([^\"]+)\"").find(exchange.bodyAsText())!!.groupValues[1]
            val session = sessions.sessionForToken(token)!!
            val missingCsrf =
                client.post("/api/v1/session/stop") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)
            assertTrue(missingCsrf.bodyAsText().contains("CSRF_INVALID"))
            val accepted =
                client.post("/api/v1/session/stop") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.Origin, "https://elsewhere.example")
                }
            assertEquals(HttpStatusCode.Accepted, accepted.status)
            val attempts =
                (1..6).map {
                    client
                        .post("/api/v1/auth/session-code/exchange") {
                            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                            setBody("code=invalid&browserLabel=Test")
                        }.status
                }
            assertTrue(attempts.contains(HttpStatusCode.TooManyRequests))
        }

    private fun capture(config: DevConsoleConfig) =
        NetworkCaptureFactory(RedactionEngine(config.redactionPolicy)).capture(
            NetworkRequestInput(
                method = "POST",
                url = "https://api.test/orders?access_token=query-secret",
                headers = mapOf("Authorization" to "Bearer header-secret", "Cookie" to "session=cookie-secret"),
                body = """{"token":"body-secret","":"empty-key"}""".encodeToByteArray(),
                contentType = "application/json",
            ),
            null,
        )
}
