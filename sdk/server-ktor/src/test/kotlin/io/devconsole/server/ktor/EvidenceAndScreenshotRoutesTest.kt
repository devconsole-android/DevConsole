/**
 * @author Shakib
 * @since 06/08/26
 */
package io.devconsole.server.ktor

import io.devconsole.api.ScreenshotResult
import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkTransaction
import io.devconsole.push.InMemoryPushStore
import io.devconsole.push.PushEvent
import io.devconsole.push.PushLifecycle
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.InMemoryCommandAuditLog
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.devconsole.socket.InMemorySocketStore
import io.devconsole.socket.SocketConnection
import io.devconsole.socket.SocketDirection
import io.devconsole.socket.SocketMessage
import io.devconsole.socket.SocketPayload
import io.devconsole.storage.api.EvidenceKind
import io.devconsole.storage.api.EvidenceStore
import io.devconsole.storage.api.EvidenceWriteResult
import io.devconsole.storage.api.RedactionApplicability
import io.devconsole.storage.api.StoredAttachment
import io.devconsole.storage.api.StoredEvent
import io.devconsole.storage.api.StoredEvidenceItem
import io.devconsole.storage.api.StoredEvidenceReport
import io.devconsole.timeline.CursorCodec
import io.devconsole.timeline.InMemoryTimeline
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Covers `/api/v1/evidence` (list/flag/unflag/clear/report) and `/api/v1/screenshots`. Follows
 * [CaptureRuleRoutesTest]'s fixture conventions: same `approvedSession`/`controlHeaders` pattern, same
 * `testApplication` setup. The snapshot-materialization tests below are the regression this whole
 * workstream exists to prevent -- see the `paged past it` test.
 */
@Suppress("LargeClass") // One route family's full coverage; splitting would scatter the shared fixtures below.
class EvidenceAndScreenshotRoutesTest {
    @Test
    fun `unauthenticated requests are rejected across every evidence and screenshot route`() =
        testApplication {
            application { devConsoleModule(SessionAuthority()) }

            val unauthenticated =
                listOf(
                    suspend { client.get("/api/v1/evidence") { header(HttpHeaders.Host, "localhost") } },
                    suspend { client.post("/api/v1/evidence") { header(HttpHeaders.Host, "localhost") } },
                    suspend { client.delete("/api/v1/evidence/network/x") { header(HttpHeaders.Host, "localhost") } },
                    suspend { client.delete("/api/v1/evidence") { header(HttpHeaders.Host, "localhost") } },
                    suspend { client.put("/api/v1/evidence/report") { header(HttpHeaders.Host, "localhost") } },
                    suspend { client.post("/api/v1/screenshots") { header(HttpHeaders.Host, "localhost") } },
                )

            unauthenticated.forEach { call ->
                val response = call()
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertTrue(response.bodyAsText().contains("AUTH_REQUIRED"))
            }
        }

    @Test
    fun `every evidence mutation requires csrf and is audited`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            val store = FakeEvidenceStore()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    evidenceStore = store
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val missingCsrfFlag =
                client.post("/api/v1/evidence") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("kind=network&id=missing")
                }
            val missingCsrfClear = client.delete("/api/v1/evidence") { authHeaders(session) }

            assertEquals(HttpStatusCode.Forbidden, missingCsrfFlag.status)
            assertTrue(missingCsrfFlag.bodyAsText().contains("CSRF_INVALID"))
            assertEquals(HttpStatusCode.Forbidden, missingCsrfClear.status)
            assertTrue(missingCsrfClear.bodyAsText().contains("CSRF_INVALID"))
            assertTrue(audit.events().any { it.commandType == "evidence.flag" })
            assertTrue(audit.events().any { it.commandType == "evidence.clear" })
        }

    @Test
    fun `single-item unflag requires csrf and is audited`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            val store = FakeEvidenceStore()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    evidenceStore = store
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val missingCsrf =
                client.delete("/api/v1/evidence/network/tx-1") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)
            assertTrue(missingCsrf.bodyAsText().contains("CSRF_INVALID"))
            assertTrue(audit.events().any { it.commandType == "evidence.unflag" && it.result.name == "REJECTED" })
        }

    @Test
    fun `report save requires csrf and is audited`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            val store = FakeEvidenceStore()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    evidenceStore = store
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val missingCsrf =
                client.put("/api/v1/evidence/report") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("severity=BLOCKER")
                }

            assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)
            assertTrue(missingCsrf.bodyAsText().contains("CSRF_INVALID"))
            assertTrue(
                audit.events().any { it.commandType == "evidence.report.save" && it.result.name == "REJECTED" },
            )
            assertTrue(store.savedReports.isEmpty())
        }

    @Test
    fun `flagging without a configured evidence store answers EVIDENCE_UNAVAILABLE`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { devConsoleModule(sessions, sessionCodes) }
            val session = approvedSession(sessions, sessionCodes)

            val listed = client.get("/api/v1/evidence") { authHeaders(session) }
            val flagged =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=network&id=x")
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, listed.status)
            assertTrue(listed.bodyAsText().contains("EVIDENCE_UNAVAILABLE"))
            assertEquals(HttpStatusCode.ServiceUnavailable, flagged.status)
            assertTrue(flagged.bodyAsText().contains("EVIDENCE_UNAVAILABLE"))
        }

    @Test
    fun `an unknown subject id is reported as not found and never reaches the store`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            application { devConsoleModule(sessions, sessionCodes) { evidenceStore = store } }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=network&id=does-not-exist")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("NOT_FOUND"))
            assertTrue(store.items.isEmpty())
        }

    // ========================================================================================
    // A client-supplied `kind` must not be trusted on its own: SCREENSHOT and CRASH each claim a
    // specific capture plugin, so an event whose pluginId disagrees must be rejected rather than
    // materialized -- otherwise the manifest's screenshot-kind fallback (buildEvidenceExportRequest)
    // can be tricked into reporting a raw, unredacted attachment as if it were a redacted one.
    // ========================================================================================

    @Test
    fun `flagging an event under a kind its pluginId contradicts is rejected`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val timeline = InMemoryTimeline(emptyList(), CursorCodec(ByteArray(16)))
            timeline.append(timelineEvent(id = "log-1", pluginId = "logs", summary = "just a log line"))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    this.timeline = timeline
                    evidenceStore = store
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val asScreenshot =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=screenshot&id=log-1")
                }
            val asCrash =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=crash&id=log-1")
                }
            val asTimeline =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=timeline&id=log-1")
                }

            assertEquals(HttpStatusCode.NotFound, asScreenshot.status)
            assertTrue(asScreenshot.bodyAsText().contains("NOT_FOUND"))
            assertEquals(HttpStatusCode.NotFound, asCrash.status)
            assertTrue(asCrash.bodyAsText().contains("NOT_FOUND"))
            // TIMELINE has no pluginId constraint -- it is the generic "flag any event" kind, so the
            // same event must still succeed under its actual kind.
            assertEquals(HttpStatusCode.Created, asTimeline.status)
            assertEquals(1, store.items.size)
        }

    @Test
    fun `an over-long label is rejected as VALIDATION_FAILED before reaching the store`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val timeline = InMemoryTimeline(emptyList(), CursorCodec(ByteArray(16)))
            timeline.append(timelineEvent(id = "event-1", summary = "x".repeat(600)))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    this.timeline = timeline
                    evidenceStore = store
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=timeline&id=event-1")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("VALIDATION_FAILED"))
            assertTrue("the store's own require() must never be reached for this rejection", store.items.isEmpty())
        }

    @Test
    fun `an over-long report summary is rejected as VALIDATION_FAILED before reaching the store`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            application { devConsoleModule(sessions, sessionCodes) { evidenceStore = store } }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.put("/api/v1/evidence/report") {
                    controlHeaders(session)
                    setBody("summary=" + "s".repeat(5000))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("VALIDATION_FAILED"))
            assertTrue(store.savedReports.isEmpty())
        }

    @Test
    fun `quota rejection reports EVIDENCE_QUOTA_EXCEEDED`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore(maxItemsPerSession = 1)
            val networkStore = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16)))
            networkStore.record(networkTransaction("tx-1"))
            networkStore.record(networkTransaction("tx-2"))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = networkStore
                    evidenceStore = store
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val first =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=network&id=tx-1")
                }
            val second =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=network&id=tx-2")
                }

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Conflict, second.status)
            assertTrue(second.bodyAsText().contains("EVIDENCE_QUOTA_EXCEEDED"))
        }

    @Test
    fun `listing evidence is bounded per response and pages via limit and offset`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore(maxItemsPerSession = 10)
            val networkStore = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16)))
            repeat(3) { networkStore.record(networkTransaction("tx-$it")) }
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = networkStore
                    evidenceStore = store
                }
            }
            val session = approvedSession(sessions, sessionCodes)
            repeat(3) { index ->
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=network&id=tx-$index")
                }
            }

            val defaultPage = client.get("/api/v1/evidence") { authHeaders(session) }
            val firstPage = client.get("/api/v1/evidence?limit=2") { authHeaders(session) }
            val secondPage = client.get("/api/v1/evidence?limit=2&offset=2") { authHeaders(session) }

            // The default (parameterless) call -- what the shipped dashboard always sends -- must see
            // every item it always has, since 3 is well under MAX_EVIDENCE_ITEMS_PER_RESPONSE.
            assertEquals(HttpStatusCode.OK, defaultPage.status)
            assertTrue(defaultPage.bodyAsText().contains("\"totalCount\":3"))
            assertTrue(defaultPage.bodyAsText().contains("\"hasMore\":false"))

            assertEquals(HttpStatusCode.OK, firstPage.status)
            val firstBody = firstPage.bodyAsText()
            assertTrue(firstBody.contains("\"totalCount\":3"))
            assertTrue("a limited page must say more is available", firstBody.contains("\"hasMore\":true"))
            assertEquals(2, Regex("\"subjectId\":\"tx-").findAll(firstBody).count())

            assertEquals(HttpStatusCode.OK, secondPage.status)
            val secondBody = secondPage.bodyAsText()
            assertTrue("the last page must say nothing more is available", secondBody.contains("\"hasMore\":false"))
            assertEquals(1, Regex("\"subjectId\":\"tx-").findAll(secondBody).count())
        }

    @Test
    fun `flagging the same subject twice is reported as already flagged`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val networkStore = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16)))
            networkStore.record(networkTransaction("tx-1"))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = networkStore
                    evidenceStore = store
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            client.post("/api/v1/evidence") {
                controlHeaders(session)
                setBody("kind=network&id=tx-1")
            }
            val again =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=network&id=tx-1")
                }

            assertEquals(HttpStatusCode.Conflict, again.status)
            assertTrue(again.bodyAsText().contains("ALREADY_FLAGGED"))
        }

    // ========================================================================================
    // Snapshot correctness -- this is the regression the whole workstream exists to prevent.
    // ========================================================================================

    @Test
    fun `a flagged network transaction still reports its status and duration after the list has paged past it`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val networkStore = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16)))
            networkStore.record(
                networkTransaction("tx-1", startedAtEpochMs = 1_000, completedAtEpochMs = 1_250, status = 503),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = networkStore
                    evidenceStore = store
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val flagged =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=network&id=tx-1")
                }
            assertEquals(HttpStatusCode.Created, flagged.status)

            // The list "pages past it": the live store no longer holds the transaction at all.
            networkStore.clear()

            val listed = client.get("/api/v1/evidence") { authHeaders(session) }
            val body = listed.bodyAsText()

            assertEquals(HttpStatusCode.OK, listed.status)
            assertTrue("status must survive the live store being cleared", body.contains("\"status\":503"))
            assertTrue("duration must survive the live store being cleared", body.contains("\"durationMs\":250"))
        }

    @Test
    fun `timeline snapshot carries summary severity tags payload and attachmentId`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val timeline = InMemoryTimeline(emptyList(), CursorCodec(ByteArray(16)))
            timeline.append(
                timelineEvent(
                    id = "event-1",
                    pluginId = "logs",
                    summary = "Something happened",
                    tagsJson = "{\"tag\":\"MainActivity\",\"level\":\"WARN\"}",
                    payloadJson = "{\"message\":\"Something happened\",\"stackTrace\":\"at Foo.bar\"}",
                    attachmentId = "attach-1",
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    this.timeline = timeline
                    evidenceStore = store
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=timeline&id=event-1")
                }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(body.contains("\"pluginId\":\"logs\""))
            assertTrue(body.contains("\"tag\":\"MainActivity\""))
            assertTrue(body.contains("\"stackTrace\":\"at Foo.bar\""))
            assertTrue(body.contains("\"attachmentId\":\"attach-1\""))
        }

    @Test
    fun `screenshot snapshot carries width height and attachmentId, honestly omitting byte count`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val timeline = InMemoryTimeline(emptyList(), CursorCodec(ByteArray(16)))
            timeline.append(
                timelineEvent(
                    id = "shot-1",
                    pluginId = "screenshot",
                    type = "screenshot.captured",
                    summary = "Screenshot captured (1080x1920)",
                    tagsJson = "{\"widthPx\":\"1080\",\"heightPx\":\"1920\"}",
                    attachmentId = "attach-shot",
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    this.timeline = timeline
                    evidenceStore = store
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=screenshot&id=shot-1")
                }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(body.contains("\"widthPx\":\"1080\""))
            assertTrue(body.contains("\"heightPx\":\"1920\""))
            assertTrue(body.contains("\"attachmentId\":\"attach-shot\""))
            assertFalse("byte count is never persisted, so it must not be fabricated", body.contains("byteCount"))
        }

    // ========================================================================================
    // The evidence tray badges from StoredAttachment.redactionApplicability, a live
    // lookup, never from EvidenceKind (the client-side "screenshot => NOT_APPLICABLE" rule this
    // replaces). Both directions are covered so a future NOT_APPLICABLE attachment that is not a
    // screenshot is provably still badged correctly.
    // ========================================================================================

    @Test
    fun `a flagged screenshot item reports NOT_APPLICABLE from stored attachment data`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val timeline = InMemoryTimeline(emptyList(), CursorCodec(ByteArray(16)))
            timeline.append(
                timelineEvent(
                    id = "shot-1",
                    pluginId = "screenshot",
                    type = "screenshot.captured",
                    summary = "Screenshot captured (1080x1920)",
                    tagsJson = "{\"widthPx\":\"1080\",\"heightPx\":\"1920\"}",
                    attachmentId = "attach-shot",
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    this.timeline = timeline
                    evidenceStore = store
                    attachmentMetadataReader = { id ->
                        if (id == "attach-shot") {
                            testAttachment("attach-shot", RedactionApplicability.NOT_APPLICABLE)
                        } else {
                            null
                        }
                    }
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val flagged =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=screenshot&id=shot-1")
                }
            val listed = client.get("/api/v1/evidence") { authHeaders(session) }

            assertEquals(HttpStatusCode.Created, flagged.status)
            assertTrue(flagged.bodyAsText().contains("\"redactionApplicability\":\"NOT_APPLICABLE\""))
            assertTrue(
                "the badge must survive a fresh GET, not just the flag response",
                listed.bodyAsText().contains("\"redactionApplicability\":\"NOT_APPLICABLE\""),
            )
        }

    @Test
    fun `a flagged non-screenshot timeline item with an attachment reports APPLIED from stored data`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val timeline = InMemoryTimeline(emptyList(), CursorCodec(ByteArray(16)))
            timeline.append(
                timelineEvent(
                    id = "event-1",
                    pluginId = "logs",
                    summary = "Something happened",
                    attachmentId = "attach-log",
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    this.timeline = timeline
                    evidenceStore = store
                    attachmentMetadataReader = { id ->
                        if (id == "attach-log") {
                            testAttachment("attach-log", RedactionApplicability.APPLIED)
                        } else {
                            null
                        }
                    }
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val flagged =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=timeline&id=event-1")
                }
            val listed = client.get("/api/v1/evidence") { authHeaders(session) }

            assertEquals(HttpStatusCode.Created, flagged.status)
            assertTrue(flagged.bodyAsText().contains("\"redactionApplicability\":\"APPLIED\""))
            assertTrue(listed.bodyAsText().contains("\"redactionApplicability\":\"APPLIED\""))
        }

    @Test
    fun `a flagged item with no attachment reports a null applicability rather than a fabricated one`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val networkStore = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16)))
            networkStore.record(networkTransaction("tx-1"))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = networkStore
                    evidenceStore = store
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val flagged =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=network&id=tx-1")
                }

            assertEquals(HttpStatusCode.Created, flagged.status)
            assertTrue(flagged.bodyAsText().contains("\"redactionApplicability\":null"))
        }

    @Test
    fun `crash snapshot carries kind thread summary and the all-thread dump with breadcrumbs`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val timeline = InMemoryTimeline(emptyList(), CursorCodec(ByteArray(16)))
            timeline.append(
                timelineEvent(
                    id = "crash-1",
                    pluginId = "crash",
                    type = "uncaught",
                    summary = "NullPointerException: boom",
                    tagsJson = "{\"kind\":\"UNCAUGHT\",\"thread\":\"main\"}",
                    payloadJson =
                        "{\"stackTrace\":\"at Foo.bar\",\"breadcrumbs\":" +
                            "[{\"ts\":1,\"plugin\":\"network\",\"type\":\"http\"," +
                            "\"severity\":1,\"summary\":\"GET /x\"}]}",
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    this.timeline = timeline
                    evidenceStore = store
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=crash&id=crash-1")
                }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(body.contains("\"kind\":\"UNCAUGHT\""))
            assertTrue(body.contains("\"thread\":\"main\""))
            assertTrue(body.contains("NullPointerException: boom"))
            assertTrue(body.contains("\"stackTrace\":\"at Foo.bar\""))
            assertTrue(body.contains("\"breadcrumbs\""))
        }

    // ========================================================================================
    // The device (InspectorJsonText.jsonStringField) and the server (StoredEvent.crashSnapshotJson's
    // tagValue) must decode the *same* tagsJson tag value back to the *same* raw string before each
    // independently re-escapes it for their own JSON output. The old server-side regex (`[^"]*`, no
    // unescaping) both truncated at the first embedded quote and skipped unescaping backslashes --
    // this is the exact scenario that broke.
    // ========================================================================================

    @Test
    fun `crash snapshot decodes and re-escapes a thread name with a quote, backslash and newline like the device`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val timeline = InMemoryTimeline(emptyList(), CursorCodec(ByteArray(16)))
            // What the raw thread name actually is, on the device, before any JSON escaping.
            val rawThreadName = "pool-1 \"worker\"\\thread\nline-2"
            // What CrashCapture's own escapeJson (the device side) would have written into the
            // persisted tagsJson for that raw value -- i.e. exactly what a well-formed JSON string
            // literal containing it looks like.
            val deviceEscapedThreadName = rawThreadName.asDeviceEscapedJsonString()
            timeline.append(
                timelineEvent(
                    id = "crash-1",
                    pluginId = "crash",
                    type = "uncaught",
                    summary = "NullPointerException: boom",
                    tagsJson = "{\"kind\":\"UNCAUGHT\",\"thread\":\"$deviceEscapedThreadName\"}",
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    this.timeline = timeline
                    evidenceStore = store
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=crash&id=crash-1")
                }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.Created, response.status)
            // The server must have decoded the tag back to rawThreadName, then re-escaped it once for
            // its own snapshot JSON -- which, since both sides use an equivalent escapeJson, is the
            // identical string the device itself would have produced. Never double-escaped (a
            // literal `\\\\` where the device would write `\\`) and never cut off at the embedded
            // quote (the old regex's failure mode).
            assertTrue(
                "expected the device-equivalent escaping \"$deviceEscapedThreadName\" in: $body",
                body.contains("\"thread\":\"$deviceEscapedThreadName\""),
            )
        }

    @Test
    fun `socket snapshot carries connection url direction opcode payload and timestamp`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val socketStore = InMemorySocketStore()
            socketStore.open(SocketConnection(id = "conn-1", url = "wss://api.test/socket", openedAtEpochMs = 1))
            socketStore.append(
                SocketMessage(
                    connectionId = "conn-1",
                    direction = SocketDirection.RECEIVED,
                    timestampEpochMs = 5_000,
                    payload = SocketPayload.Text("{\"hello\":\"world\"}"),
                ),
            )
            application {
                devConsoleModule(sessions, sessionCodes) {
                    this.socketStore = socketStore
                    evidenceStore = store
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=socket&id=conn-1@5000")
                }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(body.contains("\"connectionUrl\":\"wss://api.test/socket\""))
            assertTrue(body.contains("\"direction\":\"RECEIVED\""))
            assertTrue(body.contains("\"timestampEpochMs\":5000"))
            assertTrue(body.contains("hello"))
        }

    @Test
    fun `push snapshot is materialized from the store's own index-based subject id`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val pushStore = InMemoryPushStore()
            pushStore.append(PushEvent(provider = "fcm", data = mapOf("k" to "v"), lifecycle = PushLifecycle.DISPLAYED))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    this.pushStore = pushStore
                    evidenceStore = store
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody("kind=push&id=0")
                }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(body.contains("\"provider\":\"fcm\""))
            assertTrue(body.contains("\"lifecycle\":\"DISPLAYED\""))
        }

    @Test
    fun `unflag and clear remove items and report save round-trips through GET`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val networkStore = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16)))
            networkStore.record(networkTransaction("tx-1"))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    networkTransactions = networkStore
                    evidenceStore = store
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            client.post("/api/v1/evidence") {
                controlHeaders(session)
                setBody("kind=network&id=tx-1")
            }
            val saved =
                client.put("/api/v1/evidence/report") {
                    controlHeaders(session)
                    setBody("severity=BLOCKER&summary=repro+steps&expected=A&actual=B")
                }
            val listedAfterSave = client.get("/api/v1/evidence") { authHeaders(session) }
            val unflagged = client.delete("/api/v1/evidence/network/tx-1") { controlHeaders(session) }
            val afterUnflag = client.get("/api/v1/evidence") { authHeaders(session) }

            assertEquals(HttpStatusCode.OK, saved.status)
            assertTrue(saved.bodyAsText().contains("\"severity\":\"BLOCKER\""))
            assertTrue(listedAfterSave.bodyAsText().contains("\"severity\":\"BLOCKER\""))
            assertEquals(HttpStatusCode.OK, unflagged.status)
            assertFalse(afterUnflag.bodyAsText().contains("\"subjectId\":\"tx-1\""))
        }

    // ========================================================================================
    // Evidence export bundle -- POST /api/v1/exports?scope=EVIDENCE. Only EventExportWriter's own
    // unit tests covered bundle assembly before; this drives the real route end to end.
    // ========================================================================================

    @Test
    fun `evidence export bundle has every file, unresolvable applicability is not reported APPLIED`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = FakeEvidenceStore()
            val exportDirectory = Files.createTempDirectory("devconsole-evidence-export-e2e").toFile()
            val timeline = InMemoryTimeline(emptyList(), CursorCodec(ByteArray(16)))
            timeline.append(
                timelineEvent(id = "log-1", pluginId = "logs", summary = "boom happened", attachmentId = "attach-log"),
            )
            val networkStore = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16)))
            networkStore.record(networkTransaction("tx-1"))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    this.timeline = timeline
                    this.networkTransactions = networkStore
                    this.exportDirectory = exportDirectory
                    evidenceStore = store
                    attachmentReader = { id ->
                        if (id == "attach-log") "captured body".encodeToByteArray() else null
                    }
                    // The metadata row is unavailable for this attachment -- the manifest must report
                    // no redaction claim at all, never a fabricated APPLIED (Finding 3).
                    attachmentMetadataReader = { null }
                }
            }
            val session = approvedSession(sessions, sessionCodes)
            listOf("kind=timeline&id=log-1", "kind=network&id=tx-1").forEach { formBody ->
                client.post("/api/v1/evidence") {
                    controlHeaders(session)
                    setBody(formBody)
                }
            }

            val exported =
                client.post("/api/v1/exports") {
                    controlHeaders(session)
                    setBody("scope=EVIDENCE")
                }

            assertEquals(HttpStatusCode.OK, exported.status)
            val zipEntries = exported.bodyAsBytes().evidenceZipEntries()
            listOf("report.md", "report.json", "network.har", "postman_collection.json", "session.json").forEach {
                assertTrue("missing $it", zipEntries.containsKey(it))
            }
            assertTrue(zipEntries.getValue("report.md").decodeToString().contains("QA Evidence Report"))
            val attachmentEntry = zipEntries.entries.single { it.key.startsWith("attachments/bodies/") }
            assertEquals("captured body", attachmentEntry.value.decodeToString())
            val manifest = zipEntries.getValue("manifest.json").decodeToString()
            assertTrue(manifest.contains("\"format\":\"devconsole-evidence-bundle-v1\""))
            val attachmentReportsApplied =
                Regex(
                    "\"path\":\"${Regex.escape(attachmentEntry.key)}\"[^}]*\"redactionApplicability\":\"APPLIED\"",
                ).containsMatchIn(manifest)
            assertTrue(manifest.contains("\"redactionApplicability\":null"))
            assertFalse("the unresolvable attachment must never be reported APPLIED", attachmentReportsApplied)
            exportDirectory.deleteRecursively()
        }

    // ========================================================================================
    // Screenshots
    // ========================================================================================

    @Test
    fun `every ScreenshotResult failure variant maps to its own response code`() {
        val results =
            listOf(
                ScreenshotResult.Disabled to "SCREENSHOT_DISABLED",
                ScreenshotResult.DisabledForBuild to "SCREENSHOT_DISABLED",
                ScreenshotResult.NoForegroundActivity to "NO_FOREGROUND_ACTIVITY",
                ScreenshotResult.SecureWindow to "SECURE_WINDOW",
                ScreenshotResult.Failed("boom") to "SCREENSHOT_FAILED",
            )
        results.forEach { (result, expectedCode) ->
            testApplication {
                val sessions = SessionAuthority()
                val sessionCodes = SessionCodeAuthority(sessions)
                val audit = InMemoryCommandAuditLog()
                application {
                    devConsoleModule(sessions, sessionCodes) {
                        commandAuditLog = audit
                        screenshotCapture = { result }
                    }
                }
                val session = approvedSession(sessions, sessionCodes)

                val response = client.post("/api/v1/screenshots") { controlHeaders(session) }

                assertTrue("$result -> body was ${response.bodyAsText()}", response.bodyAsText().contains(expectedCode))
                assertTrue(audit.events().any { it.commandType == "screenshot.capture" })
            }
        }
    }

    @Test
    fun `a successful capture returns attachmentId eventId width and height`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    screenshotCapture = {
                        ScreenshotResult.Captured(
                            attachmentId = "attach-1",
                            eventId = "event-1",
                            widthPx = 1080,
                            heightPx = 1920,
                            byteCount = 12_345,
                        )
                    }
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response = client.post("/api/v1/screenshots") { controlHeaders(session) }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(body.contains("\"attachmentId\":\"attach-1\""))
            assertTrue(body.contains("\"eventId\":\"event-1\""))
            assertTrue(body.contains("\"widthPx\":1080"))
            assertTrue(body.contains("\"heightPx\":1920"))
        }

    @Test
    fun `screenshot capture requires csrf`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            var captured = false
            application {
                devConsoleModule(sessions, sessionCodes) {
                    screenshotCapture = {
                        captured = true
                        ScreenshotResult.Captured("a", "e", 1, 1, 1)
                    }
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/screenshots") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("CSRF_INVALID"))
            assertFalse("policy/CSRF gate must short-circuit before capture ever runs", captured)
        }
}

@Suppress("LongParameterList") // Mirrors StoredEvent's own field list; a test fixture builder, not production code.
private fun timelineEvent(
    id: String,
    pluginId: String = "system",
    type: String = "system.event",
    summary: String = "summary",
    tagsJson: String = "{}",
    payloadJson: String? = null,
    attachmentId: String? = null,
): StoredEvent =
    StoredEvent(
        id = id,
        sessionId = "current",
        sequence = 1,
        pluginId = pluginId,
        type = type,
        wallTimeMs = 1,
        monoTimeNs = 1,
        severity = 2,
        summary = summary,
        tagsJson = tagsJson,
        payloadJson = payloadJson,
        attachmentId = attachmentId,
    )

/** Minimal [StoredAttachment] fixture -- only [id] and [redactionApplicability] vary across tests. */
private fun testAttachment(
    id: String,
    redactionApplicability: RedactionApplicability,
): StoredAttachment =
    StoredAttachment(
        id = id,
        eventId = "event-$id",
        sessionId = "current",
        mimeType = "application/octet-stream",
        originalLength = 1,
        storedLength = 1,
        truncated = false,
        sha256 = "hash-$id",
        isRedacted = redactionApplicability == RedactionApplicability.APPLIED,
        relativePath = "attachments/$id",
        redactionApplicability = redactionApplicability,
    )

private fun networkTransaction(
    id: String,
    startedAtEpochMs: Long = 1,
    completedAtEpochMs: Long? = 2,
    status: Int = 200,
): NetworkTransaction =
    NetworkTransaction(
        id = id,
        startedAtEpochMs = startedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        capture =
            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                NetworkRequestInput("GET", "https://api.test/orders"),
                NetworkResponseInput(status),
            ),
    )

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

/**
 * Escapes exactly like both sides of the crash-snapshot round-trip (device `escapeJson` in
 * `TimelineLogSink.kt`/`CrashCapture.kt`, server `escapeJson` in `DevConsoleKtorModule.kt`): backslash
 * first, then quote, then the C0 control escapes. Used to build a tagsJson fixture that looks exactly
 * like what the device would have persisted, and to compute the value the server's re-escaped snapshot
 * output must match byte-for-byte.
 */
private fun String.asDeviceEscapedJsonString(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

private fun ByteArray.evidenceZipEntries(): Map<String, ByteArray> =
    buildMap {
        ZipInputStream(ByteArrayInputStream(this@evidenceZipEntries)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                put(entry.name, zip.readBytes())
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

private fun HttpRequestBuilder.controlHeaders(session: BrowserSession) {
    header(HttpHeaders.Host, "localhost")
    header(HttpHeaders.Authorization, "Bearer ${session.token}")
    header(HttpHeaders.Origin, "http://localhost")
    header("X-DevConsole-CSRF", session.csrfToken)
    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
}

/** Bearer-only headers for a plain GET -- no Origin/CSRF, since a read route never needs them. */
private fun HttpRequestBuilder.authHeaders(session: BrowserSession) {
    header(HttpHeaders.Host, "localhost")
    header(HttpHeaders.Authorization, "Bearer ${session.token}")
}

/**
 * In-memory [EvidenceStore] test double that mirrors `RoomEvidenceStore`'s own caps (label 512,
 * text fields 4096, 200 items/session) via the same unconditional `require()` guards, so a test that
 * forgets route-boundary validation would see the store throw -- exactly the risk this module's route
 * validation exists to prevent.
 */
private class FakeEvidenceStore(
    private val maxItemsPerSession: Int = 200,
) : EvidenceStore {
    val items = mutableListOf<StoredEvidenceItem>()
    val savedReports = mutableListOf<StoredEvidenceReport>()
    private val reports = mutableMapOf<String, StoredEvidenceReport>()

    @Suppress("ReturnCount") // One early-exit per already-flagged/quota check reads clearest.
    override suspend fun flag(item: StoredEvidenceItem): EvidenceWriteResult {
        require(item.label.length <= 512) { "label exceeds 512 characters" }
        val alreadyFlagged =
            items.any { it.sessionId == item.sessionId && it.kind == item.kind && it.subjectId == item.subjectId }
        if (alreadyFlagged) {
            return EvidenceWriteResult.AlreadyFlagged
        }
        if (items.count { it.sessionId == item.sessionId } >= maxItemsPerSession) {
            return EvidenceWriteResult.QuotaExceeded
        }
        items += item
        return EvidenceWriteResult.Success(item)
    }

    override suspend fun unflag(
        sessionId: String,
        kind: EvidenceKind,
        subjectId: String,
    ) {
        items.removeAll { it.sessionId == sessionId && it.kind == kind && it.subjectId == subjectId }
    }

    override suspend fun items(sessionId: String): List<StoredEvidenceItem> = items.filter { it.sessionId == sessionId }

    override suspend fun clear(sessionId: String) {
        items.removeAll { it.sessionId == sessionId }
    }

    override suspend fun report(sessionId: String): StoredEvidenceReport =
        reports[sessionId] ?: StoredEvidenceReport(sessionId = sessionId)

    override suspend fun saveReport(report: StoredEvidenceReport) {
        require((report.summary?.length ?: 0) <= 4096) { "summary exceeds 4096 characters" }
        require((report.expected?.length ?: 0) <= 4096) { "expected exceeds 4096 characters" }
        require((report.actual?.length ?: 0) <= 4096) { "actual exceeds 4096 characters" }
        savedReports += report
        reports[report.sessionId] = report
    }

    override suspend fun deleteSession(sessionId: String) {
        items.removeAll { it.sessionId == sessionId }
        reports.remove(sessionId)
    }
}
