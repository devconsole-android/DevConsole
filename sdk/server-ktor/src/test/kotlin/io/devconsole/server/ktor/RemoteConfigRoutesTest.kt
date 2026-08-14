/**
 * @author Md. Asaduzzaman Nur
 * @since 13/08/26
 */
package io.devconsole.server.ktor

import io.devconsole.remoteconfig.RemoteConfigEntry
import io.devconsole.remoteconfig.RemoteConfigFetchInfo
import io.devconsole.remoteconfig.RemoteConfigFetchStatus
import io.devconsole.remoteconfig.RemoteConfigSnapshot
import io.devconsole.remoteconfig.RemoteConfigSource
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.ServerMetadata
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigRoutesTest {
    private fun snapshot(
        unavailableReason: String? = null,
        entries: List<RemoteConfigEntry> =
            listOf(
                RemoteConfigEntry("checkout_v2", "true", RemoteConfigSource.REMOTE),
                RemoteConfigEntry("banner", "hi", RemoteConfigSource.DEFAULT),
            ),
    ) = RemoteConfigSnapshot(
        providerId = "firebase",
        entries = entries,
        fetchInfo =
            RemoteConfigFetchInfo(
                lastFetchEpochMs = 1_755_043_200_000L,
                status = RemoteConfigFetchStatus.SUCCESS,
                minimumFetchIntervalSeconds = 3600L,
            ),
        unavailableReason = unavailableReason,
    )

    @Test
    fun `requires an authenticated session`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    remoteConfigSnapshots = { listOf(snapshot()) }
                }
            }

            val response = client.get("/api/v1/remote-config") { header(HttpHeaders.Host, "localhost") }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("AUTH_REQUIRED"))
        }

    @Test
    fun `is refused when the state category is disabled`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    remoteConfigSnapshots = { listOf(snapshot()) }
                    metadata = ServerMetadata(captureCategories = listOf("network", "logs"))
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response = client.get("/api/v1/remote-config") { authed(session) }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("CATEGORY_DISABLED"))
        }

    @Test
    fun `serves entries with source and fetch metadata under the documented wire names`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    remoteConfigSnapshots = { listOf(snapshot()) }
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val body = client.get("/api/v1/remote-config") { authed(session) }.bodyAsText()

            assertTrue(body, body.contains("\"id\":\"firebase\""))
            assertTrue(body, body.contains("\"fetch\":{"))
            assertTrue(body, body.contains("\"lastFetchEpochMs\":1755043200000"))
            assertTrue(body, body.contains("\"status\":\"success\""))
            assertTrue(body, body.contains("\"minimumFetchIntervalSeconds\":3600"))
            assertTrue(body, body.contains("\"key\":\"checkout_v2\""))
            assertTrue(body, body.contains("\"source\":\"remote\""))
            assertTrue(body, body.contains("\"source\":\"default\""))
        }

    @Test
    fun `reports never-fetched as a null timestamp rather than a sentinel`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    remoteConfigSnapshots = {
                        listOf(
                            RemoteConfigSnapshot(
                                "firebase",
                                emptyList(),
                                RemoteConfigFetchInfo(null, RemoteConfigFetchStatus.NO_FETCH_YET, null),
                            ),
                        )
                    }
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val body = client.get("/api/v1/remote-config") { authed(session) }.bodyAsText()

            assertTrue(body, body.contains("\"lastFetchEpochMs\":null"))
            assertTrue(body, body.contains("\"status\":\"no_fetch_yet\""))
            assertTrue(body, body.contains("\"minimumFetchIntervalSeconds\":null"))
        }

    @Test
    fun `surfaces an unavailable provider`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    remoteConfigSnapshots =
                        { listOf(snapshot(unavailableReason = "disabled-build", entries = emptyList())) }
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val body = client.get("/api/v1/remote-config") { authed(session) }.bodyAsText()

            assertTrue(body, body.contains("\"unavailableReason\":\"disabled-build\""))
        }

    @Test
    fun `an absent provider list serves an empty payload rather than failing`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) {} }
            val session = approvedSession(sessions, sessionCodes)

            val response = client.get("/api/v1/remote-config") { authed(session) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("{\"data\":[]}", response.bodyAsText())
        }

    @Test
    fun `escapes quotes in keys and values so the payload stays parseable`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    remoteConfigSnapshots = {
                        listOf(
                            snapshot(
                                entries = listOf(RemoteConfigEntry("quote\"key", "va\"lue", RemoteConfigSource.REMOTE)),
                            ),
                        )
                    }
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val body = client.get("/api/v1/remote-config") { authed(session) }.bodyAsText()

            assertTrue(body, body.contains("quote\\\"key"))
            assertFalse(body, body.contains("\"quote\"key\""))
        }

    @Test
    fun `carries the redaction and truncation flags through to the wire`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    remoteConfigSnapshots = {
                        listOf(
                            snapshot(
                                entries =
                                    listOf(
                                        RemoteConfigEntry(
                                            "api_key",
                                            "<redacted>",
                                            RemoteConfigSource.REMOTE,
                                            redacted = true,
                                            truncated = true,
                                        ),
                                    ),
                            ),
                        )
                    }
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val body = client.get("/api/v1/remote-config") { authed(session) }.bodyAsText()

            assertTrue(body, body.contains("\"redacted\":true"))
            assertTrue(body, body.contains("\"truncated\":true"))
        }

    private fun io.ktor.client.request.HttpRequestBuilder.authed(session: BrowserSession) {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Authorization, "Bearer ${session.token}")
    }

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
}
