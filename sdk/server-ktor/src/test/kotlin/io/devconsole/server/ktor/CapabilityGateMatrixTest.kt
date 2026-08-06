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
import io.ktor.client.request.HttpRequestBuilder
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Explicit cross-product matrix for the five independent `EditingCapabilities` flags
 * (mocks/captureRules/preferences/database/files). Every mutation route in [DevConsoleKtorModule]
 * is gated by exactly one of `mocksEditable`/`captureRulesEditable`/`preferencesEditable`/
 * `databaseEditable`/`filesEditable`, and existing tests scattered across
 * [DevConsoleKtorModuleTest], [InspectorRoutesTest], and [CaptureRuleRoutesTest] each check "this
 * flag off blocks its own route" and "this flag on allows its own route" in isolation. None of them
 * assert the piece that actually makes this a *capability* system rather than five independent
 * booleans that happen to share a name: that flipping ONE flag on does not also, even
 * accidentally, unlock a sibling capability's mutation route. This test builds that matrix
 * explicitly -- enabling each capability alone and probing all five route families every time.
 */
class CapabilityGateMatrixTest {
    private enum class Capability(
        val disabledCode: String,
    ) {
        MOCKS("MOCKS_DISABLED"),
        CAPTURE_RULES("CAPTURE_RULES_DISABLED"),
        PREFERENCES("PREFERENCES_DISABLED"),
        DATABASE("DATABASE_DISABLED"),
        FILES("FILES_DISABLED"),
    }

    @Test
    fun `enabling exactly one editing capability unlocks only that capability's mutation routes`() {
        Capability.entries.forEach { target -> assertMatrixRow(target) }
    }

    private fun assertMatrixRow(target: Capability) =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    mocksEditable = target == Capability.MOCKS
                    captureRulesEditable = target == Capability.CAPTURE_RULES
                    preferencesEditable = target == Capability.PREFERENCES
                    databaseEditable = target == Capability.DATABASE
                    filesEditable = target == Capability.FILES
                    preferencesInspector = MatrixPreferencesInspector()
                    databaseInspector = MatrixDatabaseInspector()
                    fileInspector = MatrixFileInspector()
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            Capability.entries.forEach { probe ->
                val response = probeMutationRoute(probe, session)
                if (probe == target) {
                    assertOwnRouteSucceeded(target, response)
                } else {
                    assertSiblingBlocked(target, probe, response)
                }
            }
        }

    private suspend fun ApplicationTestBuilder.probeMutationRoute(
        capability: Capability,
        session: BrowserSession,
    ): HttpResponse =
        when (capability) {
            Capability.MOCKS ->
                client.post("/api/v1/mocks/rules") {
                    controlHeaders(session)
                    setBody("id=r1&priority=1&path=%2Forders&status=200")
                }
            Capability.CAPTURE_RULES ->
                client.post("/api/v1/capture-rules") {
                    controlHeaders(session)
                    setBody("id=r1&host=api.example.test")
                }
            Capability.PREFERENCES ->
                client.post("/api/v1/preferences/app_prefs") {
                    controlHeaders(session)
                    setBody("key=theme&value=dark&type=STRING")
                }
            Capability.DATABASE ->
                client.post("/api/v1/database/app.db/sql") {
                    controlHeaders(session)
                    setBody("sql=SELECT+1")
                }
            Capability.FILES ->
                client.put("/api/v1/files/storage") {
                    controlHeaders(session)
                    setBody("path=a.txt&content=hi")
                }
        }

    private suspend fun assertSiblingBlocked(
        target: Capability,
        probe: Capability,
        response: HttpResponse,
    ) {
        assertEquals("enabling $target must not affect $probe's gate", HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertTrue(
            "expected ${probe.disabledCode} for $probe while only $target is enabled, got $body",
            body.contains(probe.disabledCode),
        )
    }

    private suspend fun assertOwnRouteSucceeded(
        capability: Capability,
        response: HttpResponse,
    ) {
        val body = response.bodyAsText()
        assertFalse(
            "enabling $capability must not leave its own route capability-blocked, got $body",
            body.contains(capability.disabledCode),
        )
        val expectedStatus =
            when (capability) {
                Capability.MOCKS, Capability.CAPTURE_RULES -> HttpStatusCode.Created
                Capability.PREFERENCES, Capability.DATABASE, Capability.FILES -> HttpStatusCode.OK
            }
        assertEquals("$capability's own route should succeed once enabled, got $body", expectedStatus, response.status)
    }
}

private class MatrixPreferencesInspector : PreferencesInspector {
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

private class MatrixDatabaseInspector : DatabaseInspector {
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

private class MatrixFileInspector : FileInspector {
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
