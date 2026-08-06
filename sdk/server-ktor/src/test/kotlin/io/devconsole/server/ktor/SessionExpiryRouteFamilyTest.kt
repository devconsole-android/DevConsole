/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.server.ktor

import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.DatabaseExecResult
import io.devconsole.server.api.DatabaseInspector
import io.devconsole.server.api.DatabaseListingData
import io.devconsole.server.api.DatabaseQueryData
import io.devconsole.server.api.FileEntryData
import io.devconsole.server.api.FileInspector
import io.devconsole.server.api.FileListingData
import io.devconsole.server.api.FilePreviewData
import io.devconsole.server.api.PreferencesEntryData
import io.devconsole.server.api.PreferencesFileData
import io.devconsole.server.api.PreferencesInspector
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A bearer session past its TTL must be rejected by every route family, not just the one route that
 * happened to already have a test. Before this file, expiry was only exercised for the session-code
 * itself ([SessionCodeRoutesTest]) and for explicit revocation on the WebSocket stream
 * ([DevConsoleKtorModuleTest]`.authenticated stream closes promptly when its principal is revoked`)
 * -- natural TTL lapse of an already-issued bearer token was never exercised against a single HTTP
 * route, let alone the read/auth-mutation/capability-mutation/WebSocket-upgrade families that all
 * gate independently in [DevConsoleKtorModule]. Uses the same fake-clock pattern as
 * [SessionAuthorityTest] so expiry is deterministic rather than a real sleep.
 */
class SessionExpiryRouteFamilyTest {
    @Test
    fun `an expired bearer session is rejected across read, auth, and every capability mutation route family`() =
        testApplication {
            var now = 0L
            val sessions = SessionAuthority(nowEpochMs = { now }, sessionTtlMs = 1_000L)
            val sessionCodes = SessionCodeAuthority(sessions, nowEpochMs = { now })
            application {
                devConsoleModule(sessions, sessionCodes) {
                    mocksEditable = true
                    captureRulesEditable = true
                    preferencesEditable = true
                    databaseEditable = true
                    filesEditable = true
                    preferencesInspector = ExpiryPreferencesInspector()
                    databaseInspector = ExpiryDatabaseInspector()
                    fileInspector = ExpiryFileInspector()
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            // Sanity check: the session works before it expires.
            val beforeExpiry =
                client.get("/api/v1/session") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            assertEquals(HttpStatusCode.OK, beforeExpiry.status)

            now = 1_001L // one ms past sessionTtlMs=1_000

            expiredSessionProbes(session).forEach { (label, call) ->
                val response = call()
                assertEquals("$label must reject an expired session", HttpStatusCode.Unauthorized, response.status)
                val body = response.bodyAsText()
                assertTrue("$label expected AUTH_REQUIRED, got $body", body.contains("AUTH_REQUIRED"))
            }
        }

    /** One probe per independently-gating route family, each reusing the now-expired [session]. */
    private fun ApplicationTestBuilder.expiredSessionProbes(
        session: BrowserSession,
    ): List<Pair<String, suspend () -> HttpResponse>> =
        listOf(
            "GET /api/v1/session (read)" to
                { bearerGet(session, "/api/v1/session") },
            "GET /api/v1/events (read)" to
                { bearerGet(session, "/api/v1/events") },
            "POST /api/v1/auth/refresh (auth-only mutation)" to
                { client.post("/api/v1/auth/refresh") { controlHeaders(session) } },
            "POST /api/v1/mocks/disable-all (auth-only mutation)" to
                { client.post("/api/v1/mocks/disable-all") { controlHeaders(session) } },
            "POST /api/v1/mocks/rules (mocks capability)" to
                {
                    client.post("/api/v1/mocks/rules") {
                        controlHeaders(session)
                        setBody("id=r1&priority=1&path=%2Forders&status=200")
                    }
                },
            "POST /api/v1/capture-rules (captureRules capability)" to
                {
                    client.post("/api/v1/capture-rules") {
                        controlHeaders(session)
                        setBody("id=r1&host=api.example.test")
                    }
                },
            "POST /api/v1/preferences/app_prefs (preferences capability)" to
                {
                    client.post("/api/v1/preferences/app_prefs") {
                        controlHeaders(session)
                        setBody("key=theme&value=dark&type=STRING")
                    }
                },
            "POST /api/v1/database/app.db/sql (database capability)" to
                {
                    client.post("/api/v1/database/app.db/sql") {
                        controlHeaders(session)
                        setBody("sql=SELECT+1")
                    }
                },
            "PUT /api/v1/files/storage (files capability)" to
                {
                    client.put("/api/v1/files/storage") {
                        controlHeaders(session)
                        setBody("path=a.txt&content=hi")
                    }
                },
        )

    private suspend fun ApplicationTestBuilder.bearerGet(
        session: BrowserSession,
        path: String,
    ): HttpResponse =
        client.get(path) {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
        }

    @Test
    fun `a websocket upgrade presenting an already-expired bearer token is refused at handshake`() =
        testApplication {
            var now = 0L
            val sessions = SessionAuthority(nowEpochMs = { now }, sessionTtlMs = 1_000L)
            val sessionCodes = SessionCodeAuthority(sessions, nowEpochMs = { now })
            application { devConsoleModule(sessions, sessionCodes) }
            val session = approvedSession(sessions, sessionCodes)
            now = 1_001L
            val streamClient = createClient { install(WebSockets) }

            streamClient.webSocket(
                urlString = "/api/v1/stream",
                request = {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Cookie, "DevConsoleStreamSession=${session.token}")
                },
            ) {
                val closeReason = withTimeout(2_000) { closeReason.await() }
                assertEquals("AUTH_REQUIRED", closeReason?.message)
            }
        }

    @Test
    fun `an established websocket stream closes when its session naturally expires, not only when revoked`() =
        testApplication {
            var now = 0L
            val sessions = SessionAuthority(nowEpochMs = { now }, sessionTtlMs = 1_000L)
            val sessionCodes = SessionCodeAuthority(sessions, nowEpochMs = { now })
            application { devConsoleModule(sessions, sessionCodes) }
            val session = approvedSession(sessions, sessionCodes)
            val streamClient = createClient { install(WebSockets) }

            streamClient.webSocket(
                urlString = "/api/v1/stream",
                request = {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Cookie, "DevConsoleStreamSession=${session.token}")
                },
            ) {
                send(Frame.Text("{\"type\":\"client.hello\",\"protocolVersion\":1}"))
                assertTrue((incoming.receive() as Frame.Text).data.decodeToString().contains("server.welcome"))

                // No revocation call: the session simply outlives its TTL, which the periodic
                // auth recheck must catch exactly like an explicit revoke does.
                now = 1_001L

                withTimeout(2_000) { incoming.receiveCatching().getOrNull() }
                assertEquals("AUTH_REVOKED", withTimeout(2_000) { closeReason.await() }?.message)
            }
        }
}

private class ExpiryPreferencesInspector : PreferencesInspector {
    override fun files(): List<PreferencesFileData> =
        listOf(
            PreferencesFileData(
                name = "app_prefs",
                entries = listOf(PreferencesEntryData("theme", "light", "STRING")),
            ),
        )

    override fun put(
        file: String,
        key: String,
        value: String,
        type: String,
    ): Boolean = true

    override fun remove(
        file: String,
        key: String,
    ): Boolean = true
}

private class ExpiryDatabaseInspector : DatabaseInspector {
    override fun databases(): List<String> = listOf("app.db")

    override fun tables(database: String): DatabaseListingData? = DatabaseListingData(database, emptyList())

    override fun query(
        database: String,
        table: String,
    ): DatabaseQueryData? = DatabaseQueryData(emptyList(), emptyList(), false)

    override fun execute(
        database: String,
        sql: String,
        writeEnabled: Boolean,
    ): DatabaseExecResult = DatabaseExecResult.Query(DatabaseQueryData(listOf("value"), listOf(listOf("1")), false))
}

private class ExpiryFileInspector : FileInspector {
    override fun roots(): List<String> = listOf("storage")

    override fun list(
        root: String,
        relativePath: String,
    ): FileListingData? = FileListingData(root, relativePath, emptyList<FileEntryData>())

    override fun preview(
        root: String,
        relativePath: String,
    ): FilePreviewData = FilePreviewData.Unavailable("no preview")

    override fun delete(
        root: String,
        relativePath: String,
    ): Boolean = true

    override fun create(
        root: String,
        relativePath: String,
        content: String,
    ): Boolean = true

    override fun replace(
        root: String,
        relativePath: String,
        content: String,
    ): Boolean = true

    override fun rename(
        root: String,
        relativePath: String,
        newRelativePath: String,
    ): Boolean = true

    override fun readBytes(
        root: String,
        relativePath: String,
    ): ByteArray? = null
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
