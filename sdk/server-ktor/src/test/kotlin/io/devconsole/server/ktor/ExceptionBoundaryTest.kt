/**
 * @author Shakib
 * @since 05/08/26
 */
package io.devconsole.server.ktor

import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.CommandAuditEvent
import io.devconsole.server.api.CommandAuditLog
import io.devconsole.server.api.DatabaseExecResult
import io.devconsole.server.api.DatabaseInspector
import io.devconsole.server.api.DatabaseListingData
import io.devconsole.server.api.DatabaseQueryData
import io.devconsole.server.api.FileInspector
import io.devconsole.server.api.FileListingData
import io.devconsole.server.api.FilePreviewData
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

/**
 * Covers the global exception boundary installed around every route in [devConsoleModule]: a
 * host-supplied inspector (database/file, the only collaborators a host implements itself rather
 * than the SDK) can throw for any reason -- a locked SQLite file, a permission-denied read, a bug
 * in the host's own code -- and before this boundary existed, that exception would reach Ktor's
 * default handler, which can render the exception's message/stack trace straight into the HTTP
 * response body. These tests assert the opposite: a bounded `{"code":"INTERNAL_ERROR"}` envelope,
 * HTTP 500, and no trace of the original exception's message anywhere in the response.
 *
 * There are actually two boundaries, since [devConsoleModule] installs one per pipeline phase: the
 * `ApplicationCallPipeline.Call`-phase one wrapping `routing { }` (most of the tests below), and an
 * earlier `ApplicationCallPipeline.Plugins`-phase one wrapping host header parsing, CSP
 * construction, session lookup, and rate limiting -- code that runs *before* routing dispatch and so
 * is outside the Call-phase boundary's `proceed()` entirely. The last test below exercises that
 * earlier boundary specifically.
 */
class ExceptionBoundaryTest {
    @Test
    fun `a throwing database inspector answers a bounded error instead of leaking the exception`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val secretMarker = "sqlite disk I/O error at offset 0xDEADBEEF"
            application {
                devConsoleModule(sessions, sessionCodes) {
                    databaseInspector = ThrowingDatabaseInspector(secretMarker)
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/database/app.db") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("INTERNAL_ERROR"))
            assertFalse("the response must not leak the inspector's exception message", body.contains(secretMarker))
            assertFalse("the response must not leak a stack frame", body.contains("ThrowingDatabaseInspector"))
        }

    @Test
    fun `a throwing database sql execution answers a bounded error instead of leaking the exception`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val secretMarker = "column 'hunter2_api_key' does not exist"
            application {
                devConsoleModule(sessions, sessionCodes) {
                    databaseInspector = ThrowingDatabaseInspector(secretMarker)
                    databaseEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/database/app.db/sql") {
                    controlHeaders(session)
                    setBody("sql=SELECT+1")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("INTERNAL_ERROR"))
            assertFalse("the response must not leak the inspector's exception message", body.contains(secretMarker))
        }

    @Test
    fun `a throwing file inspector answers a bounded error instead of leaking the exception`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val secretMarker = "/private/var/mobile/Containers/Data/host-only-path"
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = ThrowingFileInspector(secretMarker)
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/files/storage") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("INTERNAL_ERROR"))
            assertFalse("the response must not leak the inspector's exception message", body.contains(secretMarker))
            assertFalse("the response must not leak a stack frame", body.contains("ThrowingFileInspector"))
        }

    @Test
    fun `a throwing collaborator reached from the Plugins-phase interceptor answers a bounded error too`() =
        testApplication {
            // devConsoleModule's Plugins-phase interceptor (host parsing, CSP, session lookup, rate
            // limiting) runs upstream of the Call-phase boundary the other tests in this file cover,
            // so a throw there previously reached Ktor's default handler unfiltered. commandAuditLog
            // is the one host-supplied collaborator this interceptor calls directly (recording a
            // rejected composer command while composerEnabled defaults to false), so a throwing
            // CommandAuditLog forces an exception inside that earlier phase, not the later one.
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val secretMarker = "audit-sink-unavailable: replica lease lost at 0xFEEDFACE"
            application {
                devConsoleModule(sessions, sessionCodes) {
                    commandAuditLog = ThrowingCommandAuditLog(secretMarker)
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/composer/execute") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("INTERNAL_ERROR"))
            // Proves the Plugins-phase catch fired before this branch's own COMPOSER_DISABLED
            // response could complete, not merely that some later handler recovered.
            assertFalse("must not fall through to the branch's own response", body.contains("COMPOSER_DISABLED"))
            assertFalse("the response must not leak the audit log's exception message", body.contains(secretMarker))
            assertFalse("the response must not leak a stack frame", body.contains("ThrowingCommandAuditLog"))
        }

    /** A malformed body used to answer 500, reading as "the SDK broke". */
    @Test
    fun `a form route sent the wrong content type answers a 4xx rather than 500`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/network/postman") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.Origin, "http://localhost")
                    header("X-DevConsole-CSRF", session.csrfToken)
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody("{\"id\":\"whatever\"}")
                }

            // Ktor raises a content-transformation failure here, not the typed media-type error.
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue("expected the typed client error, got: $body", body.contains("VALIDATION_FAILED"))
            assertFalse("a caller's bad content type is not a server fault", body.contains("INTERNAL_ERROR"))
        }

    @Test
    fun `the same form route still succeeds with the documented content type`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/network/postman") {
                    controlHeaders(session)
                    setBody("")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"info\""))
        }

    @Test
    fun `a route that does not throw is unaffected by the exception boundary`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }

            val response =
                client.get("/health") {
                    header(HttpHeaders.Host, "localhost")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(response.bodyAsText().contains("INTERNAL_ERROR"))
        }
}

/** Mints an authenticated [BrowserSession] via the real session-code exchange route. */
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

/** Every member throws, simulating a host [CommandAuditLog] implementation blowing up. */
private class ThrowingCommandAuditLog(
    private val message: String,
) : CommandAuditLog {
    override fun record(event: CommandAuditEvent): Unit = throw IllegalStateException(message)

    override fun events(): List<CommandAuditEvent> = throw IllegalStateException(message)
}

/** Every member throws, simulating a host [DatabaseInspector] implementation blowing up. */
private class ThrowingDatabaseInspector(
    private val message: String,
) : DatabaseInspector {
    override fun databases(): List<String> = throw IllegalStateException(message)

    override fun tables(database: String): DatabaseListingData? = throw IllegalStateException(message)

    override fun query(
        database: String,
        table: String,
    ): DatabaseQueryData? = throw IllegalStateException(message)

    override fun execute(
        database: String,
        sql: String,
        writeEnabled: Boolean,
    ): DatabaseExecResult = throw IllegalStateException(message)
}

/** Every member throws, simulating a host [FileInspector] implementation blowing up. */
private class ThrowingFileInspector(
    private val message: String,
) : FileInspector {
    override fun roots(): List<String> = throw IllegalStateException(message)

    override fun list(
        root: String,
        relativePath: String,
    ): FileListingData? = throw IllegalStateException(message)

    override fun preview(
        root: String,
        relativePath: String,
    ): FilePreviewData = throw IllegalStateException(message)

    override fun delete(
        root: String,
        relativePath: String,
    ): Boolean = throw IllegalStateException(message)

    override fun create(
        root: String,
        relativePath: String,
        content: String,
    ): Boolean = throw IllegalStateException(message)

    override fun replace(
        root: String,
        relativePath: String,
        content: String,
    ): Boolean = throw IllegalStateException(message)

    override fun rename(
        root: String,
        relativePath: String,
        newRelativePath: String,
    ): Boolean = throw IllegalStateException(message)

    override fun readBytes(
        root: String,
        relativePath: String,
    ): ByteArray? = throw IllegalStateException(message)
}
