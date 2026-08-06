/**
 * @author Shakib
 * @since 02/08/26
 */
package io.devconsole.server.ktor

import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.DatabaseExecResult
import io.devconsole.server.api.DatabaseInspector
import io.devconsole.server.api.DatabaseListingData
import io.devconsole.server.api.DatabaseQueryData
import io.devconsole.server.api.DatabaseTableData
import io.devconsole.server.api.FileEntryData
import io.devconsole.server.api.FileInspector
import io.devconsole.server.api.FileListingData
import io.devconsole.server.api.FilePreviewData
import io.devconsole.server.api.InMemoryCommandAuditLog
import io.devconsole.server.api.PreferencesEntryData
import io.devconsole.server.api.PreferencesFileData
import io.devconsole.server.api.PreferencesInspector
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the browser Data-parity routes added in this change: `/api/v1/preferences`,
 * `/api/v1/database`, and `/api/v1/files`. Follows the fixture/patterns established in
 * [DevConsoleKtorModuleTest] -- same `testApplication`/`SessionAuthority` setup, same
 * Bearer/Origin/`X-DevConsole-CSRF` header convention for mutating routes. There is a single
 * access tier now (no more READ_ONLY/CONTROL split): every route below requires only an
 * authenticated session, and mutations are additionally gated by the relevant
 * `EditingCapabilities` flag (preferences/database/files).
 */
class InspectorRoutesTest {
    @Test
    fun `unauthenticated requests are rejected across preferences database and files routes`() =
        testApplication {
            application { devConsoleModule(SessionAuthority()) }

            val unauthenticated =
                listOf(
                    suspend { client.get("/api/v1/preferences") { header(HttpHeaders.Host, "localhost") } },
                    suspend {
                        client.post("/api/v1/preferences/app_prefs") { header(HttpHeaders.Host, "localhost") }
                    },
                    suspend {
                        client.delete(
                            "/api/v1/preferences/app_prefs?key=theme",
                        ) { header(HttpHeaders.Host, "localhost") }
                    },
                    suspend { client.get("/api/v1/database") { header(HttpHeaders.Host, "localhost") } },
                    suspend { client.get("/api/v1/database/app.db") { header(HttpHeaders.Host, "localhost") } },
                    suspend {
                        client.get("/api/v1/database/app.db/tables/users") { header(HttpHeaders.Host, "localhost") }
                    },
                    suspend {
                        client.post("/api/v1/database/app.db/sql") { header(HttpHeaders.Host, "localhost") }
                    },
                    suspend { client.get("/api/v1/files") { header(HttpHeaders.Host, "localhost") } },
                    suspend { client.get("/api/v1/files/storage") { header(HttpHeaders.Host, "localhost") } },
                    suspend {
                        client.get("/api/v1/files/storage/preview") { header(HttpHeaders.Host, "localhost") }
                    },
                    suspend {
                        client.delete("/api/v1/files/storage") { header(HttpHeaders.Host, "localhost") }
                    },
                )

            unauthenticated.forEach { call ->
                val response = call()
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertTrue(response.bodyAsText().contains("AUTH_REQUIRED"))
            }
        }

    @Test
    fun `authenticated browser lists preference files with editable flag and redaction pass-through`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector =
                FakePreferencesInspector(
                    filesResult =
                        listOf(
                            PreferencesFileData(
                                name = "app_prefs",
                                entries =
                                    listOf(
                                        PreferencesEntryData("theme", "dark", "STRING"),
                                        PreferencesEntryData("auth_token", "<redacted>", "STRING", redacted = true),
                                    ),
                            ),
                        ),
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    preferencesInspector = inspector
                    preferencesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/preferences") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"editable\":true"))
            assertTrue(body.contains("\"name\":\"app_prefs\""))
            assertTrue(body.contains("\"key\":\"theme\",\"value\":\"dark\",\"type\":\"STRING\",\"redacted\":false"))
            assertTrue(
                body.contains("\"key\":\"auth_token\",\"value\":\"<redacted>\",\"type\":\"STRING\",\"redacted\":true"),
            )
        }

    @Test
    fun `preference entry set requires a valid csrf token`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector = FakePreferencesInspector(filesResult = emptyList())
            application {
                devConsoleModule(sessions, sessionCodes) {
                    preferencesInspector = inspector
                    preferencesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val missingCsrf =
                client.post("/api/v1/preferences/app_prefs") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("key=theme&value=dark&type=STRING")
                }
            val accepted =
                client.post("/api/v1/preferences/app_prefs") {
                    controlHeaders(session)
                    setBody("key=theme&value=dark&type=STRING")
                }

            assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)
            assertTrue(missingCsrf.bodyAsText().contains("CSRF_INVALID"))
            assertEquals(HttpStatusCode.OK, accepted.status)
            assertTrue(accepted.bodyAsText().contains("\"status\":\"updated\""))
            assertEquals(listOf("app_prefs", "theme", "dark", "STRING"), inspector.putCalls.single())
        }

    @Test
    fun `preference entry set validates required fields and reports inspector rejection`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val rejectingInspector = FakePreferencesInspector(filesResult = emptyList(), putResult = false)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    preferencesInspector = rejectingInspector
                    preferencesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val blankKey =
                client.post("/api/v1/preferences/app_prefs") {
                    controlHeaders(session)
                    setBody("key=&value=dark&type=STRING")
                }
            val inspectorRejects =
                client.post("/api/v1/preferences/app_prefs") {
                    controlHeaders(session)
                    setBody("key=theme&value=dark&type=STRING")
                }

            assertEquals(HttpStatusCode.BadRequest, blankKey.status)
            assertTrue(blankKey.bodyAsText().contains("VALIDATION_FAILED"))
            assertEquals(HttpStatusCode.BadRequest, inspectorRejects.status)
            assertTrue(inspectorRejects.bodyAsText().contains("VALIDATION_FAILED"))
        }

    @Test
    fun `preference entry set refuses to overwrite a redacted entry`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            // The dashboard shows this entry as "<redacted>"; a write would replace the real
            // secret with that placeholder, so the server must refuse it like the device UI does.
            val inspector =
                FakePreferencesInspector(
                    filesResult =
                        listOf(
                            PreferencesFileData(
                                name = "app_prefs",
                                entries =
                                    listOf(
                                        PreferencesEntryData("auth_token", "<redacted>", "STRING", redacted = true),
                                    ),
                            ),
                        ),
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    preferencesInspector = inspector
                    preferencesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/preferences/app_prefs") {
                    controlHeaders(session)
                    setBody("key=auth_token&value=%3Credacted%3E&type=STRING")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("REDACTED_WRITE_BLOCKED"))
            assertTrue("inspector must not write a redacted entry", inspector.putCalls.isEmpty())
        }

    @Test
    fun `preference entry set answers UNAVAILABLE when no inspector is configured`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    // preferencesInspector left null on purpose.
                    preferencesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/preferences/app_prefs") {
                    controlHeaders(session)
                    setBody("key=theme&value=dark&type=STRING")
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("UNAVAILABLE"))
        }

    @Test
    fun `preference entry remove requires a key and reports not found instead of validation failed`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector = FakePreferencesInspector(filesResult = emptyList(), removeResult = true)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    preferencesInspector = inspector
                    preferencesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val missingKey =
                client.delete("/api/v1/preferences/app_prefs") {
                    controlHeaders(session)
                }
            val removed =
                client.delete("/api/v1/preferences/app_prefs?key=theme") {
                    controlHeaders(session)
                }

            // Unlike POST (400 VALIDATION_FAILED for a blank key), DELETE folds a blank key into the
            // same 404 branch used for "inspector missing" and "remove() returned false".
            assertEquals(HttpStatusCode.NotFound, missingKey.status)
            assertTrue(missingKey.bodyAsText().contains("NOT_FOUND"))
            assertEquals(HttpStatusCode.OK, removed.status)
            assertEquals(listOf("app_prefs", "theme"), inspector.removeCalls.single())
        }

    @Test
    fun `preference mutations are rejected when the preferences editing capability is disabled`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    preferencesInspector = FakePreferencesInspector(filesResult = emptyList())
                    preferencesEditable = false
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val setDenied =
                client.post("/api/v1/preferences/app_prefs") {
                    controlHeaders(session)
                    setBody("key=theme&value=dark&type=STRING")
                }
            val removeDenied =
                client.delete("/api/v1/preferences/app_prefs?key=theme") {
                    controlHeaders(session)
                }

            assertEquals(HttpStatusCode.Forbidden, setDenied.status)
            assertTrue(setDenied.bodyAsText().contains("PREFERENCES_DISABLED"))
            assertEquals(HttpStatusCode.Forbidden, removeDenied.status)
            assertTrue(removeDenied.bodyAsText().contains("PREFERENCES_DISABLED"))
            assertTrue(audit.events().any { it.commandType == "preferences.entry.set" })
            assertTrue(audit.events().any { it.commandType == "preferences.entry.remove" })
        }

    @Test
    fun `authenticated browser reads database listing and table rows and unknown targets answer not found`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector =
                FakeDatabaseInspector(
                    databaseNames = listOf("app.db"),
                    tablesResult = DatabaseListingData("app.db", listOf(DatabaseTableData("users", 3))),
                    queryResult =
                        DatabaseQueryData(
                            listOf("id", "name"),
                            listOf(listOf("1", "Ann")),
                            truncated = false,
                        ),
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    databaseInspector = inspector
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val databases =
                client.get("/api/v1/database") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val tables =
                client.get("/api/v1/database/app.db") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val rows =
                client.get("/api/v1/database/app.db/tables/users") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val unknownDatabase =
                client.get("/api/v1/database/missing.db") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, databases.status)
            assertTrue(databases.bodyAsText().contains("\"editable\":false"))
            assertTrue(databases.bodyAsText().contains("\"app.db\""))
            assertEquals(HttpStatusCode.OK, tables.status)
            assertTrue(tables.bodyAsText().contains("\"name\":\"users\",\"rowCount\":3"))
            assertEquals(HttpStatusCode.OK, rows.status)
            assertTrue(rows.bodyAsText().contains("\"columns\":[\"id\",\"name\"]"))
            assertTrue(rows.bodyAsText().contains("\"truncated\":false"))
            assertEquals(HttpStatusCode.NotFound, unknownDatabase.status)
        }

    @Test
    fun `database listing exposes on-disk file size and table rows expose a rowid for safe per-row edits`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector =
                FakeDatabaseInspector(
                    databaseNames = listOf("app.db"),
                    tablesResult =
                        DatabaseListingData("app.db", listOf(DatabaseTableData("users", 3)), sizeBytes = 4_096),
                    queryResult =
                        DatabaseQueryData(
                            listOf("id", "name"),
                            listOf(listOf("1", "Ann"), listOf("2", "Bo")),
                            truncated = false,
                            rowIds = listOf(10L, 11L),
                        ),
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    databaseInspector = inspector
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val tables =
                client.get("/api/v1/database/app.db") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val rows =
                client.get("/api/v1/database/app.db/tables/users") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, tables.status)
            assertTrue(tables.bodyAsText().contains("\"sizeBytes\":4096"))
            assertEquals(HttpStatusCode.OK, rows.status)
            assertTrue(rows.bodyAsText().contains("\"rowIds\":[10,11]"))
            // rowid never goes through the same-column masking path as ordinary data.
            assertFalse(rows.bodyAsText().contains("\"rowid\""))
        }

    @Test
    fun `raw sql console results report an empty rowIds array since no single table is guaranteed`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector =
                FakeDatabaseInspector(
                    executeResult =
                        DatabaseExecResult.Query(
                            DatabaseQueryData(listOf("id"), listOf(listOf("1")), false),
                        ),
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    databaseInspector = inspector
                    databaseEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/database/app.db/sql") {
                    controlHeaders(session)
                    setBody("sql=SELECT+1")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"rowIds\":[]"))
        }

    @Test
    fun `database sql execution requires a valid csrf token`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector =
                FakeDatabaseInspector(
                    executeResult =
                        DatabaseExecResult.Query(
                            DatabaseQueryData(listOf("id"), listOf(listOf("1")), false),
                        ),
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    databaseInspector = inspector
                    databaseEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val missingCsrf =
                client.post("/api/v1/database/app.db/sql") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("sql=SELECT+1")
                }
            val accepted =
                client.post("/api/v1/database/app.db/sql") {
                    controlHeaders(session)
                    setBody("sql=SELECT+1")
                }

            assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)
            assertTrue(missingCsrf.bodyAsText().contains("CSRF_INVALID"))
            assertEquals(HttpStatusCode.OK, accepted.status)
            assertTrue(accepted.bodyAsText().contains("\"kind\":\"QUERY\""))
            assertEquals(listOf("SELECT 1"), inspector.executedSql)
        }

    @Test
    fun `database sql execution validates blank and oversized statements and a missing inspector`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector = FakeDatabaseInspector()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    databaseInspector = inspector
                    databaseEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val blank =
                client.post("/api/v1/database/app.db/sql") {
                    controlHeaders(session)
                    setBody("sql=")
                }
            val oversized =
                client.post("/api/v1/database/app.db/sql") {
                    controlHeaders(session)
                    setBody("sql=" + "a".repeat(8 * 1024 + 1))
                }

            assertEquals(HttpStatusCode.BadRequest, blank.status)
            assertTrue(blank.bodyAsText().contains("VALIDATION_FAILED"))
            assertEquals(HttpStatusCode.BadRequest, oversized.status)
            assertTrue(oversized.bodyAsText().contains("VALIDATION_FAILED"))
            assertTrue("inspector should not have been invoked for rejected input", inspector.executedSql.isEmpty())
        }

    @Test
    fun `database sql execution reports affected rows for a write when database editing is enabled`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector = FakeDatabaseInspector(executeResult = DatabaseExecResult.Write(affectedRows = 2))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    databaseInspector = inspector
                    databaseEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/database/app.db/sql") {
                    controlHeaders(session)
                    setBody("sql=UPDATE+users+SET+name%3D%27x%27")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"kind\":\"WRITE\",\"affectedRows\":2"))
        }

    @Test
    fun `database sql execution is refused entirely when database editing is disabled`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            // SELECT is refused too: a caller who writes the SQL can alias columns past redaction,
            // so the capability gates the whole console, not just mutating statements.
            val inspector =
                FakeDatabaseInspector(
                    executeResult =
                        DatabaseExecResult.Query(
                            DatabaseQueryData(listOf("p"), listOf(listOf("hunter2")), false),
                        ),
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    databaseInspector = inspector
                    databaseEditable = false
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/database/app.db/sql") {
                    controlHeaders(session)
                    setBody("sql=SELECT+password+AS+p+FROM+users")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("DATABASE_DISABLED"))
            assertTrue("inspector must not run when the capability is disabled", inspector.executedSql.isEmpty())
        }

    @Test
    fun `database sql execution reports a failed statement with its message`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector = FakeDatabaseInspector(executeResult = DatabaseExecResult.Failed("no such table: ghost"))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    databaseInspector = inspector
                    databaseEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/database/app.db/sql") {
                    controlHeaders(session)
                    setBody("sql=SELECT+*+FROM+ghost")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("VALIDATION_FAILED"))
            assertTrue(response.bodyAsText().contains("no such table: ghost"))
        }

    @Test
    fun `authenticated browser reads file roots listing and preview and unknown paths answer not found`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val listing = FileListingData("storage", "", listOf(FileEntryData("a.txt", "a.txt", false, 10, 1_000)))
            val inspector =
                FakeFileInspector(
                    rootNames = listOf("storage"),
                    listProvider = { _, path -> if (path.isBlank()) listing else null },
                    previewResult = FilePreviewData.Text("hello world", truncated = false),
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = inspector
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val roots =
                client.get("/api/v1/files") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val list =
                client.get("/api/v1/files/storage") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val preview =
                client.get("/api/v1/files/storage/preview?path=a.txt") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val unknown =
                client.get("/api/v1/files/storage?path=missing") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, roots.status)
            assertTrue(roots.bodyAsText().contains("\"editable\":false"))
            assertTrue(roots.bodyAsText().contains("\"storage\""))
            assertEquals(HttpStatusCode.OK, list.status)
            assertTrue(list.bodyAsText().contains("\"name\":\"a.txt\""))
            assertEquals(HttpStatusCode.OK, preview.status)
            assertTrue(
                preview.bodyAsText().contains("\"kind\":\"TEXT\",\"content\":\"hello world\",\"truncated\":false"),
            )
            assertEquals(HttpStatusCode.NotFound, unknown.status)
        }

    @Test
    fun `file preview defaults to unavailable when no inspector is configured`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/files/storage/preview?path=a.txt") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"kind\":\"UNAVAILABLE\""))
            assertTrue(response.bodyAsText().contains("Inspector is not connected"))
        }

    @Test
    fun `file path traversal attempts are surfaced as not found via the inspector guard`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            // The real canonicalization guard lives in the Android FileInspector implementation (see
            // sdk/full FileInspector doc comment: "anything escaping a root ('..', symlinks, absolute
            // paths) is refused"). This fake reproduces that contract -- list/delete answer null/false
            // for a traversal attempt -- so this test verifies the *route* correctly maps a guarded
            // inspector response to 404 rather than any other status.
            val inspector =
                FakeFileInspector(
                    rootNames = listOf("storage"),
                    listProvider = {
                        _,
                        path,
                        ->
                        if (path.contains("..")) null else FileListingData("storage", path, emptyList())
                    },
                    deleteProvider = { _, path -> !path.contains("..") },
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = inspector
                    filesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val list =
                client.get("/api/v1/files/storage?path=..%2F..%2Fetc%2Fpasswd") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val delete =
                client.delete("/api/v1/files/storage?path=..%2F..%2Fetc%2Fpasswd") {
                    controlHeaders(session)
                }

            assertEquals(HttpStatusCode.NotFound, list.status)
            assertEquals(HttpStatusCode.NotFound, delete.status)
            assertTrue(delete.bodyAsText().contains("NOT_FOUND"))
        }

    @Test
    fun `file deletion requires a valid csrf and the files editing capability`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector = FakeFileInspector(rootNames = listOf("storage"))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = inspector
                    filesEditable = false
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val missingCsrf =
                client.delete("/api/v1/files/storage?path=a.txt") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val editingDisabled =
                client.delete("/api/v1/files/storage?path=a.txt") {
                    controlHeaders(session)
                }

            assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)
            assertTrue(missingCsrf.bodyAsText().contains("CSRF_INVALID"))
            assertEquals(HttpStatusCode.Forbidden, editingDisabled.status)
            assertTrue(editingDisabled.bodyAsText().contains("FILES_DISABLED"))
            assertTrue(inspector.deleteCalls.isEmpty())
        }

    @Test
    fun `file deletion succeeds when enabled and reports not found for a blank path or a failed delete`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector =
                FakeFileInspector(rootNames = listOf("storage"), deleteProvider = { _, path ->
                    path ==
                        "a.txt"
                })
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = inspector
                    filesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val blankPath =
                client.delete("/api/v1/files/storage") {
                    controlHeaders(session)
                }
            val notFound =
                client.delete("/api/v1/files/storage?path=missing.txt") {
                    controlHeaders(session)
                }
            val deleted =
                client.delete("/api/v1/files/storage?path=a.txt") {
                    controlHeaders(session)
                }

            assertEquals(HttpStatusCode.NotFound, blankPath.status)
            assertEquals(HttpStatusCode.NotFound, notFound.status)
            assertEquals(HttpStatusCode.OK, deleted.status)
            assertTrue(deleted.bodyAsText().contains("\"status\":\"deleted\""))
            // A blank path short-circuits before the inspector is called; "missing.txt" reaches the
            // inspector and is rejected there, so both the failed and successful attempt are recorded.
            assertEquals(
                listOf(listOf("storage", "missing.txt"), listOf("storage", "a.txt")),
                inspector.deleteCalls,
            )
        }

    @Test
    fun `preference mutations are rate limited per browser principal`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    preferencesInspector = FakePreferencesInspector(filesResult = emptyList())
                    preferencesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            repeat(30) {
                assertFalse(
                    "attempt $it should not be rate limited",
                    client
                        .post("/api/v1/preferences/app_prefs") {
                            controlHeaders(session)
                            setBody("key=theme&value=dark&type=STRING")
                        }.status == HttpStatusCode.TooManyRequests,
                )
            }
            val limited =
                client.post("/api/v1/preferences/app_prefs") {
                    controlHeaders(session)
                    setBody("key=theme&value=dark&type=STRING")
                }

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertTrue(limited.bodyAsText().contains("RATE_LIMITED"))
        }

    @Test
    fun `database sql execution is rate limited per browser principal`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    databaseInspector =
                        FakeDatabaseInspector(
                            executeResult =
                                DatabaseExecResult.Query(
                                    DatabaseQueryData(emptyList(), emptyList(), false),
                                ),
                        )
                    databaseEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            repeat(30) {
                assertFalse(
                    "attempt $it should not be rate limited",
                    client
                        .post("/api/v1/database/app.db/sql") {
                            controlHeaders(session)
                            setBody("sql=SELECT+1")
                        }.status == HttpStatusCode.TooManyRequests,
                )
            }
            val limited =
                client.post("/api/v1/database/app.db/sql") {
                    controlHeaders(session)
                    setBody("sql=SELECT+1")
                }

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertTrue(limited.bodyAsText().contains("RATE_LIMITED"))
        }

    @Test
    fun `files mutations are rate limited per browser principal`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = FakeFileInspector(rootNames = listOf("storage"), deleteProvider = { _, _ -> true })
                    filesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            repeat(30) {
                assertFalse(
                    "attempt $it should not be rate limited",
                    client
                        .delete("/api/v1/files/storage?path=a.txt") {
                            controlHeaders(session)
                        }.status == HttpStatusCode.TooManyRequests,
                )
            }
            val limited =
                client.delete("/api/v1/files/storage?path=a.txt") {
                    controlHeaders(session)
                }

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertTrue(limited.bodyAsText().contains("RATE_LIMITED"))
        }

    @Test
    fun `file create requires a valid csrf and the files editing capability`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector = FakeFileInspector(rootNames = listOf("storage"))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = inspector
                    filesEditable = false
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val missingCsrf =
                client.put("/api/v1/files/storage") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    setBody("path=a.txt&content=hi")
                }
            val editingDisabled =
                client.put("/api/v1/files/storage") {
                    controlHeaders(session)
                    setBody("path=a.txt&content=hi")
                }

            assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)
            assertTrue(missingCsrf.bodyAsText().contains("CSRF_INVALID"))
            assertEquals(HttpStatusCode.Forbidden, editingDisabled.status)
            assertTrue(editingDisabled.bodyAsText().contains("FILES_DISABLED"))
            assertTrue(inspector.createCalls.isEmpty())
        }

    @Test
    fun `file create succeeds and reports conflict when the inspector refuses to overwrite`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector =
                FakeFileInspector(rootNames = listOf("storage"), createProvider = { _, path, _ -> path == "new.txt" })
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = inspector
                    filesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val blankPath =
                client.put("/api/v1/files/storage") {
                    controlHeaders(session)
                    setBody("path=&content=hi")
                }
            val conflict =
                client.put("/api/v1/files/storage") {
                    controlHeaders(session)
                    setBody("path=exists.txt&content=hi")
                }
            val created =
                client.put("/api/v1/files/storage") {
                    controlHeaders(session)
                    setBody("path=new.txt&content=hello+world")
                }

            assertEquals(HttpStatusCode.BadRequest, blankPath.status)
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertTrue(conflict.bodyAsText().contains("CONFLICT"))
            assertEquals(HttpStatusCode.OK, created.status)
            assertTrue(created.bodyAsText().contains("\"status\":\"created\""))
            assertEquals(listOf("storage", "exists.txt", "hi"), inspector.createCalls[0])
            assertEquals(listOf("storage", "new.txt", "hello world"), inspector.createCalls[1])
        }

    @Test
    fun `file create refuses content over the write size cap`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector = FakeFileInspector(rootNames = listOf("storage"))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = inspector
                    filesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.put("/api/v1/files/storage") {
                    controlHeaders(session)
                    setBody("path=big.txt&content=" + "a".repeat(300 * 1024))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(inspector.createCalls.isEmpty())
        }

    @Test
    fun `file replace succeeds and reports not found when the inspector has nothing there`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector =
                FakeFileInspector(rootNames = listOf("storage"), replaceProvider = { _, path, _ -> path == "a.txt" })
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = inspector
                    filesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val notFound =
                client.post("/api/v1/files/storage") {
                    controlHeaders(session)
                    setBody("path=missing.txt&content=hi")
                }
            val updated =
                client.post("/api/v1/files/storage") {
                    controlHeaders(session)
                    setBody("path=a.txt&content=updated")
                }

            assertEquals(HttpStatusCode.NotFound, notFound.status)
            assertEquals(HttpStatusCode.OK, updated.status)
            assertTrue(updated.bodyAsText().contains("\"status\":\"updated\""))
            assertEquals(listOf("storage", "a.txt", "updated"), inspector.replaceCalls.last())
        }

    @Test
    fun `file rename validates both paths and refuses to clobber an existing destination`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector =
                FakeFileInspector(
                    rootNames = listOf("storage"),
                    renameProvider = { _, _, newPath -> newPath == "b.txt" },
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = inspector
                    filesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val missingNewPath =
                client.post("/api/v1/files/storage/rename") {
                    controlHeaders(session)
                    setBody("path=a.txt&newPath=")
                }
            val clobberRefused =
                client.post("/api/v1/files/storage/rename") {
                    controlHeaders(session)
                    setBody("path=a.txt&newPath=existing.txt")
                }
            val renamed =
                client.post("/api/v1/files/storage/rename") {
                    controlHeaders(session)
                    setBody("path=a.txt&newPath=b.txt")
                }

            assertEquals(HttpStatusCode.BadRequest, missingNewPath.status)
            assertEquals(HttpStatusCode.Conflict, clobberRefused.status)
            assertEquals(HttpStatusCode.OK, renamed.status)
            assertTrue(renamed.bodyAsText().contains("\"status\":\"renamed\""))
            assertEquals(listOf("storage", "a.txt", "b.txt"), inspector.renameCalls.last())
        }

    @Test
    fun `file rename requires the files editing capability`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val inspector = FakeFileInspector(rootNames = listOf("storage"))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = inspector
                    filesEditable = false
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val editingDisabled =
                client.post("/api/v1/files/storage/rename") {
                    controlHeaders(session)
                    setBody("path=a.txt&newPath=b.txt")
                }

            assertEquals(HttpStatusCode.Forbidden, editingDisabled.status)
            assertTrue(editingDisabled.bodyAsText().contains("FILES_DISABLED"))
            assertTrue(inspector.renameCalls.isEmpty())
        }

    @Test
    fun `file download requires the files capability because bytes are unredacted`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val bytes = byteArrayOf(1, 2, 3, 0, 4, 5)
            val inspector =
                FakeFileInspector(
                    rootNames = listOf("storage"),
                    readBytesProvider = { _, path -> if (path == "a.bin") bytes else null },
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = inspector
                    filesEditable = false
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val disabled =
                client.get("/api/v1/files/storage/download?path=a.bin") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.Forbidden, disabled.status)
            assertTrue(disabled.bodyAsText().contains("FILES_DISABLED"))
        }

    @Test
    fun `file download streams raw unredacted bytes with an attachment content disposition when enabled`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val bytes = byteArrayOf(1, 2, 3, 0, 4, 5)
            val inspector =
                FakeFileInspector(
                    rootNames = listOf("storage"),
                    readBytesProvider = { _, path -> if (path == "a.bin") bytes else null },
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = inspector
                    filesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val missing =
                client.get("/api/v1/files/storage/download?path=missing.bin") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }
            val downloaded =
                client.get("/api/v1/files/storage/download?path=a.bin") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals(HttpStatusCode.OK, downloaded.status)
            assertArrayEquals(bytes, downloaded.bodyAsBytes())
            assertTrue(downloaded.headers[HttpHeaders.ContentDisposition].orEmpty().contains("attachment"))
            assertTrue(downloaded.headers[HttpHeaders.ContentDisposition].orEmpty().contains("a.bin"))
            assertTrue(downloaded.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/octet-stream"))
        }

    @Test
    fun `file download sanitizes a header-injection attempt in the filename`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val bytes = byteArrayOf(9)
            val maliciousName = "evil\r\nX-Injected: true\".txt"
            val inspector =
                FakeFileInspector(
                    rootNames = listOf("storage"),
                    readBytesProvider = { _, path -> if (path == maliciousName) bytes else null },
                )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = inspector
                    filesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val encodedPath = java.net.URLEncoder.encode(maliciousName, "UTF-8")
            val response =
                client.get("/api/v1/files/storage/download?path=$encodedPath") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            // The security property is the absence of CR/LF -- without them "X-Injected: true" can
            // never break out of the quoted filename into a real header, so it staying present as
            // inert filename text is fine and expected.
            val disposition = response.headers[HttpHeaders.ContentDisposition].orEmpty()
            assertFalse(disposition.contains("\r"))
            assertFalse(disposition.contains("\n"))
            assertNull(response.headers["X-Injected"])
        }

    @Test
    fun `file create and rename mutations are rate limited per browser principal`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    fileInspector = FakeFileInspector(rootNames = listOf("storage"))
                    filesEditable = true
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            repeat(30) {
                assertFalse(
                    "attempt $it should not be rate limited",
                    client
                        .put("/api/v1/files/storage") {
                            controlHeaders(session)
                            setBody("path=file$it.txt&content=hi")
                        }.status == HttpStatusCode.TooManyRequests,
                )
            }
            val limited =
                client.put("/api/v1/files/storage") {
                    controlHeaders(session)
                    setBody("path=onemore.txt&content=hi")
                }

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertTrue(limited.bodyAsText().contains("RATE_LIMITED"))
        }
}

/**
 * Mints an authenticated [BrowserSession] the only way an external caller can: issue a session code
 * and exchange it over the real HTTP route. Must be called after `application { devConsoleModule(...) }`
 * has been registered, since the exchange request is what triggers the test application to start.
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

private class FakePreferencesInspector(
    private val filesResult: List<PreferencesFileData>,
    private val putResult: Boolean = true,
    private val removeResult: Boolean = true,
) : PreferencesInspector {
    val putCalls = mutableListOf<List<String>>()
    val removeCalls = mutableListOf<List<String>>()

    override fun files(): List<PreferencesFileData> = filesResult

    override fun put(
        file: String,
        key: String,
        value: String,
        type: String,
    ): Boolean {
        putCalls += listOf(file, key, value, type)
        return putResult
    }

    override fun remove(
        file: String,
        key: String,
    ): Boolean {
        removeCalls += listOf(file, key)
        return removeResult
    }
}

private class FakeDatabaseInspector(
    private val databaseNames: List<String> = emptyList(),
    private val tablesResult: DatabaseListingData? = null,
    private val queryResult: DatabaseQueryData? = null,
    private val executeResult: DatabaseExecResult =
        DatabaseExecResult.Query(
            DatabaseQueryData(emptyList(), emptyList(), false),
        ),
) : DatabaseInspector {
    val executedSql = mutableListOf<String>()

    override fun databases(): List<String> = databaseNames

    override fun tables(database: String): DatabaseListingData? = tablesResult.takeIf { database in databaseNames }

    override fun query(
        database: String,
        table: String,
    ): DatabaseQueryData? = queryResult

    override fun execute(
        database: String,
        sql: String,
        writeEnabled: Boolean,
    ): DatabaseExecResult {
        executedSql += sql
        return executeResult
    }
}

// One optional provider lambda per FileInspector operation, matching the interface 1:1 so tests can
// stub exactly the behavior each case needs.
@Suppress("LongParameterList")
private class FakeFileInspector(
    private val rootNames: List<String> = emptyList(),
    private val listProvider: (String, String) -> FileListingData? = { _, _ -> null },
    private val previewResult: FilePreviewData = FilePreviewData.Unavailable("no preview"),
    private val deleteProvider: (String, String) -> Boolean = { _, _ -> true },
    private val createProvider: (String, String, String) -> Boolean = { _, _, _ -> true },
    private val replaceProvider: (String, String, String) -> Boolean = { _, _, _ -> true },
    private val renameProvider: (String, String, String) -> Boolean = { _, _, _ -> true },
    private val readBytesProvider: (String, String) -> ByteArray? = { _, _ -> null },
) : FileInspector {
    val deleteCalls = mutableListOf<List<String>>()
    val createCalls = mutableListOf<List<String>>()
    val replaceCalls = mutableListOf<List<String>>()
    val renameCalls = mutableListOf<List<String>>()
    val readBytesCalls = mutableListOf<List<String>>()

    override fun roots(): List<String> = rootNames

    override fun list(
        root: String,
        relativePath: String,
    ): FileListingData? = listProvider(root, relativePath)

    override fun preview(
        root: String,
        relativePath: String,
    ): FilePreviewData = previewResult

    override fun delete(
        root: String,
        relativePath: String,
    ): Boolean {
        deleteCalls += listOf(root, relativePath)
        return deleteProvider(root, relativePath)
    }

    override fun create(
        root: String,
        relativePath: String,
        content: String,
    ): Boolean {
        createCalls += listOf(root, relativePath, content)
        return createProvider(root, relativePath, content)
    }

    override fun replace(
        root: String,
        relativePath: String,
        content: String,
    ): Boolean {
        replaceCalls += listOf(root, relativePath, content)
        return replaceProvider(root, relativePath, content)
    }

    override fun rename(
        root: String,
        relativePath: String,
        newRelativePath: String,
    ): Boolean {
        renameCalls += listOf(root, relativePath, newRelativePath)
        return renameProvider(root, relativePath, newRelativePath)
    }

    override fun readBytes(
        root: String,
        relativePath: String,
    ): ByteArray? {
        readBytesCalls += listOf(root, relativePath)
        return readBytesProvider(root, relativePath)
    }
}
