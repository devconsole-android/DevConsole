/**
 * @author Shakib
 * @since 04/11/26
 */
package io.devconsole.server.ktor

import io.devconsole.remoteconfig.InMemoryRemoteConfigOverrideStore
import io.devconsole.remoteconfig.RemoteConfigEntry
import io.devconsole.remoteconfig.RemoteConfigFetchInfo
import io.devconsole.remoteconfig.RemoteConfigSnapshot
import io.devconsole.remoteconfig.RemoteConfigSource
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.InMemoryCommandAuditLog
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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

private const val OVER_LONG_KEY_LENGTH = 300

class RemoteConfigOverrideRoutesTest {
    @Test
    fun `put stores the override`() =
        testApplication {
            val fixtures = createFixtures()
            val sessions = fixtures.sessions
            val sessionCodes = fixtures.sessionCodes
            val audit = fixtures.audit
            val store = fixtures.store
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    featureFlagsEditable = true
                    remoteConfigSnapshots = { listOf(firebaseSnapshot()) }
                    remoteConfigOverrides = store
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.put("/api/v1/remote-config/firebase/overrides/welcome_message") {
                    controlHeaders(session)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("value=Hello+World")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("{\"status\":\"updated\"}", response.bodyAsText())
            assertEquals("Hello World", store.overrides()["firebase"]?.get("welcome_message"))
        }

    @Test
    fun `delete one clears it`() =
        testApplication {
            val fixtures = createFixtures()
            val sessions = fixtures.sessions
            val sessionCodes = fixtures.sessionCodes
            val audit = fixtures.audit
            val store = fixtures.store
            store.setOverride("firebase", "welcome_message", "Hello")
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    featureFlagsEditable = true
                    remoteConfigSnapshots = { listOf(firebaseSnapshot()) }
                    remoteConfigOverrides = store
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.delete("/api/v1/remote-config/firebase/overrides/welcome_message") {
                    controlHeaders(session)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("{\"status\":\"cleared\"}", response.bodyAsText())
            assertFalse(store.overrides().getOrDefault("firebase", emptyMap()).containsKey("welcome_message"))
        }

    @Test
    fun `delete all clears all`() =
        testApplication {
            val fixtures = createFixtures()
            val sessions = fixtures.sessions
            val sessionCodes = fixtures.sessionCodes
            val audit = fixtures.audit
            val store = fixtures.store
            store.setOverride("firebase", "key1", "val1")
            store.setOverride("firebase", "key2", "val2")
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    featureFlagsEditable = true
                    remoteConfigSnapshots = { listOf(firebaseSnapshot()) }
                    remoteConfigOverrides = store
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.delete("/api/v1/remote-config/overrides") {
                    controlHeaders(session)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("{\"status\":\"cleared\"}", response.bodyAsText())
            assertTrue(store.overrides().isEmpty())
        }

    @Test
    fun `get reports overridesEditable true and providerValue and providerSource fields`() =
        testApplication {
            val fixtures = createFixtures()
            val sessions = fixtures.sessions
            val sessionCodes = fixtures.sessionCodes
            val store = fixtures.store
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    remoteConfigSnapshots = { listOf(firebaseSnapshot()) }
                    remoteConfigOverrides = store
                    featureFlagsEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response = client.get("/api/v1/remote-config") { authHeaders(session) }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"overridesEditable\":true"))
            assertTrue(body.contains("\"providerValue\":\"Original\""))
            assertTrue(body.contains("\"providerSource\":\"remote\""))
        }

    @Test
    fun `get reports overridesEditable false when store is null or featureFlagsEditable is false`() =
        testApplication {
            val fixtures = createFixtures()
            val sessions = fixtures.sessions
            val sessionCodes = fixtures.sessionCodes
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    remoteConfigSnapshots = { listOf(firebaseSnapshot()) }
                    remoteConfigOverrides = null
                    featureFlagsEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response = client.get("/api/v1/remote-config") { authHeaders(session) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"overridesEditable\":false"))
        }

    @Test
    fun `returns 403 when featureFlagsEditable is false`() =
        testApplication {
            val fixtures = createFixtures()
            val sessions = fixtures.sessions
            val sessionCodes = fixtures.sessionCodes
            val audit = fixtures.audit
            val store = fixtures.store
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    remoteConfigSnapshots = { listOf(firebaseSnapshot()) }
                    remoteConfigOverrides = store
                    featureFlagsEditable = false
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val putRes =
                client.put("/api/v1/remote-config/firebase/overrides/welcome_message") {
                    controlHeaders(session)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("value=test")
                }
            val deleteOneRes =
                client.delete("/api/v1/remote-config/firebase/overrides/welcome_message") {
                    controlHeaders(session)
                }
            val deleteAllRes =
                client.delete("/api/v1/remote-config/overrides") {
                    controlHeaders(session)
                }

            assertEquals(HttpStatusCode.Forbidden, putRes.status)
            assertEquals("{\"code\":\"REMOTE_CONFIG_OVERRIDES_DISABLED\"}", putRes.bodyAsText())
            assertEquals(HttpStatusCode.Forbidden, deleteOneRes.status)
            assertEquals("{\"code\":\"REMOTE_CONFIG_OVERRIDES_DISABLED\"}", deleteOneRes.bodyAsText())
            assertEquals(HttpStatusCode.Forbidden, deleteAllRes.status)
            assertEquals("{\"code\":\"REMOTE_CONFIG_OVERRIDES_DISABLED\"}", deleteAllRes.bodyAsText())
        }

    @Test
    fun `returns 503 when remoteConfigOverrides store is null`() =
        testApplication {
            val fixtures = createFixtures()
            val sessions = fixtures.sessions
            val sessionCodes = fixtures.sessionCodes
            val audit = fixtures.audit
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    featureFlagsEditable = true
                    remoteConfigSnapshots = { listOf(firebaseSnapshot()) }
                    remoteConfigOverrides = null
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val putRes =
                client.put("/api/v1/remote-config/firebase/overrides/welcome_message") {
                    controlHeaders(session)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("value=test")
                }
            val deleteOneRes =
                client.delete("/api/v1/remote-config/firebase/overrides/welcome_message") {
                    controlHeaders(session)
                }
            val deleteAllRes =
                client.delete("/api/v1/remote-config/overrides") {
                    controlHeaders(session)
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, putRes.status)
            assertEquals("{\"code\":\"REMOTE_CONFIG_OVERRIDES_UNAVAILABLE\"}", putRes.bodyAsText())
            assertEquals(HttpStatusCode.ServiceUnavailable, deleteOneRes.status)
            assertEquals("{\"code\":\"REMOTE_CONFIG_OVERRIDES_UNAVAILABLE\"}", deleteOneRes.bodyAsText())
            assertEquals(HttpStatusCode.ServiceUnavailable, deleteAllRes.status)
            assertEquals("{\"code\":\"REMOTE_CONFIG_OVERRIDES_UNAVAILABLE\"}", deleteAllRes.bodyAsText())
        }

    @Test
    fun `returns 404 when provider is unknown`() =
        testApplication {
            val fixtures = createFixtures()
            val sessions = fixtures.sessions
            val sessionCodes = fixtures.sessionCodes
            val audit = fixtures.audit
            val store = fixtures.store
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    featureFlagsEditable = true
                    remoteConfigSnapshots = { listOf(firebaseSnapshot()) }
                    remoteConfigOverrides = store
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val putRes =
                client.put("/api/v1/remote-config/unknown_provider/overrides/key") {
                    controlHeaders(session)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("value=test")
                }
            val deleteRes =
                client.delete("/api/v1/remote-config/unknown_provider/overrides/key") {
                    controlHeaders(session)
                }

            assertEquals(HttpStatusCode.NotFound, putRes.status)
            assertEquals("{\"code\":\"REMOTE_CONFIG_PROVIDER_NOT_FOUND\"}", putRes.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, deleteRes.status)
            assertEquals("{\"code\":\"REMOTE_CONFIG_PROVIDER_NOT_FOUND\"}", deleteRes.bodyAsText())
        }

    @Test
    fun `returns 400 when value parameter is missing or store throws IllegalArgumentException`() =
        testApplication {
            val fixtures = createFixtures()
            val sessions = fixtures.sessions
            val sessionCodes = fixtures.sessionCodes
            val audit = fixtures.audit
            val store = fixtures.store
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    featureFlagsEditable = true
                    remoteConfigSnapshots = { listOf(firebaseSnapshot()) }
                    remoteConfigOverrides = store
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val missingValueRes =
                client.put("/api/v1/remote-config/firebase/overrides/welcome_message") {
                    controlHeaders(session)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("other=test")
                }

            val overLongKey = "k".repeat(OVER_LONG_KEY_LENGTH)
            val blankKeyRes =
                client.put("/api/v1/remote-config/firebase/overrides/$overLongKey") {
                    controlHeaders(session)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("value=test")
                }

            assertEquals(HttpStatusCode.BadRequest, missingValueRes.status)
            assertEquals("{\"code\":\"VALIDATION_FAILED\"}", missingValueRes.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, blankKeyRes.status)
            assertEquals("{\"code\":\"VALIDATION_FAILED\"}", blankKeyRes.bodyAsText())
        }

    @Test
    fun `returns 401 when unauthenticated`() =
        testApplication {
            val fixtures = createFixtures()
            val sessions = fixtures.sessions
            val sessionCodes = fixtures.sessionCodes
            val store = fixtures.store
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    featureFlagsEditable = true
                    remoteConfigSnapshots = { listOf(firebaseSnapshot()) }
                    remoteConfigOverrides = store
                }
            }

            val putRes =
                client.put("/api/v1/remote-config/firebase/overrides/key") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("value=test")
                }
            val deleteOneRes =
                client.delete("/api/v1/remote-config/firebase/overrides/key") {
                    header(HttpHeaders.Host, "localhost")
                }
            val deleteAllRes =
                client.delete("/api/v1/remote-config/overrides") {
                    header(HttpHeaders.Host, "localhost")
                }

            assertEquals(HttpStatusCode.Unauthorized, putRes.status)
            assertEquals("{\"code\":\"AUTH_REQUIRED\"}", putRes.bodyAsText())
            assertEquals(HttpStatusCode.Unauthorized, deleteOneRes.status)
            assertEquals("{\"code\":\"AUTH_REQUIRED\"}", deleteOneRes.bodyAsText())
            assertEquals(HttpStatusCode.Unauthorized, deleteAllRes.status)
            assertEquals("{\"code\":\"AUTH_REQUIRED\"}", deleteAllRes.bodyAsText())
        }

    @Test
    fun `audit event for PUT does not contain raw value when key is sensitive`() =
        testApplication {
            val fixtures = createFixtures()
            val sessions = fixtures.sessions
            val sessionCodes = fixtures.sessionCodes
            val audit = fixtures.audit
            val store = fixtures.store
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    featureFlagsEditable = true
                    remoteConfigSnapshots = { listOf(firebaseSnapshot()) }
                    remoteConfigOverrides = store
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.put("/api/v1/remote-config/firebase/overrides/api_key") {
                    controlHeaders(session)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("value=super_secret_token_12345")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val event = audit.events().last { it.commandType == "remoteConfig.override" }
            assertEquals("firebase/api_key", event.target)
            assertFalse(
                "Audit log must not contain raw secret value",
                event.parameters.values.any { it.contains("super_secret_token_12345") },
            )
        }
}

private data class TestFixtures(
    val sessions: SessionAuthority,
    val sessionCodes: SessionCodeAuthority,
    val audit: InMemoryCommandAuditLog,
    val store: InMemoryRemoteConfigOverrideStore,
)

private fun createFixtures(): TestFixtures {
    val sessions = SessionAuthority()
    return TestFixtures(
        sessions = sessions,
        sessionCodes = SessionCodeAuthority(sessions),
        audit = InMemoryCommandAuditLog(),
        store = InMemoryRemoteConfigOverrideStore(),
    )
}

private fun firebaseSnapshot() =
    RemoteConfigSnapshot(
        providerId = "firebase",
        entries =
            listOf(
                RemoteConfigEntry(
                    key = "welcome_message",
                    value = "Override",
                    source = RemoteConfigSource.STATIC,
                    redacted = false,
                    truncated = false,
                    providerValue = "Original",
                    providerSource = RemoteConfigSource.REMOTE,
                ),
            ),
        fetchInfo = RemoteConfigFetchInfo.unknown(),
    )

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
