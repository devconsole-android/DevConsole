/**
 * @author Shakib
 * @since 07/08/26
 */
package io.devconsole.server.ktor

import io.devconsole.network.InMemoryNetworkTransactionStore
import io.devconsole.network.NetworkCaptureFactory
import io.devconsole.network.NetworkCursorCodec
import io.devconsole.network.NetworkRequestInput
import io.devconsole.network.NetworkResponseInput
import io.devconsole.network.NetworkTransaction
import io.devconsole.security.RedactionEngine
import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.InMemoryCommandAuditLog
import io.devconsole.server.api.ServerMetadata
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.devconsole.state.FeatureFlag
import io.devconsole.state.SessionFeatureFlags
import io.devconsole.storage.api.StoredEvent
import io.devconsole.timeline.CursorCodec
import io.devconsole.timeline.InMemoryTimeline
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Covers the feature-flag route family's capture-category and editing-capability gating, plus the
 * network/metadata enrichment the browser session ZIP now carries. The engine-level flag logic is
 * covered elsewhere; these drive the real Ktor routes end to end, following the same fixture
 * conventions as [CaptureRuleRoutesTest].
 */
class FeatureFlagAndExportBundleRoutesTest {
    @Test
    fun `listing flags is refused when the state category is disabled`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application {
                devConsoleModule(sessions, sessionCodes) {
                    featureFlags = SessionFeatureFlags(listOf(FeatureFlag.ofBoolean("newCheckout", false)))
                    // Every default category except "state" -- so the flag routes' STATE gate is the
                    // only thing standing between the caller and the flag list.
                    metadata = ServerMetadata(captureCategories = listOf("network", "logs"))
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/flags") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("CATEGORY_DISABLED"))
        }

    @Test
    fun `overriding a flag is refused when the featureFlags capability is off`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val flags = SessionFeatureFlags(listOf(FeatureFlag.ofBoolean("newCheckout", false)))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    featureFlags = flags
                    featureFlagsEditable = false
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/flags/newCheckout") {
                    controlHeaders(session)
                    setBody("true")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("FEATURE_FLAGS_DISABLED"))
            assertTrue("the flag must not be mutated when the capability is off", flags.overrides().isEmpty())
        }

    @Test
    fun `a successful override audits the real prior and new value`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            application {
                devConsoleModule(sessions, sessionCodes) {
                    featureFlags = SessionFeatureFlags(listOf(FeatureFlag.ofBoolean("newCheckout", false)))
                    featureFlagsEditable = true
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/flags/newCheckout") {
                    controlHeaders(session)
                    setBody("true")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val event = audit.events().single { it.commandType == "flag.override" }
            assertEquals("false", event.parameters["before"])
            assertEquals("true", event.parameters["after"])
            assertEquals("true", event.parameters["changed"])
        }

    @Test
    fun `a whole-session export bundle carries network har and postman entries`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val exportDirectory = Files.createTempDirectory("devconsole-flag-export").toFile()
            val timeline =
                InMemoryTimeline(
                    listOf(StoredEvent("event-1", "current", 1, "system", "system.event", 1, 1, 1, "ready")),
                    CursorCodec(ByteArray(16)),
                )
            val networkStore = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16)))
            networkStore.record(networkTransaction("tx-1").withSessionId("current"))
            application {
                devConsoleModule(sessions, sessionCodes) {
                    this.timeline = timeline
                    this.networkTransactions = networkStore
                    this.exportDirectory = exportDirectory
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.post("/api/v1/exports") {
                    controlHeaders(session)
                    setBody("scope=WHOLE_SESSION")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val entries = response.bodyAsBytes().zipEntries()
            assertTrue("bundle must include network.har", entries.containsKey("network.har"))
            assertTrue(
                "bundle must include network.postman_collection.json",
                entries.containsKey("network.postman_collection.json"),
            )
            assertTrue("bundle must include metadata.json", entries.containsKey("metadata.json"))
            assertTrue(entries.getValue("network.har").decodeToString().contains("api.test/orders"))
            // The enrichment lives alongside the timeline payloads, never instead of them.
            assertTrue(entries.containsKey("timeline.jsonl"))
            assertTrue(exportDirectory.listFiles().isNullOrEmpty())
            exportDirectory.deleteRecursively()
        }

    @Test
    fun `network har export re-runs redaction with the current policy, not just the capture-time one`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            // Captured under a permissive policy (nothing to redact), so the raw secret header
            // survives into the stored capture exactly as sent.
            val permissivePolicy = RedactionPolicy(sensitiveFieldNames = emptySet(), textPatterns = emptyList())
            val networkStore = InMemoryNetworkTransactionStore(NetworkCursorCodec(ByteArray(16)))
            networkStore.record(
                NetworkTransaction(
                    id = "tx-secret",
                    startedAtEpochMs = 1,
                    completedAtEpochMs = 2,
                    capture =
                        NetworkCaptureFactory(RedactionEngine(permissivePolicy)).capture(
                            NetworkRequestInput(
                                "GET",
                                "https://api.test/orders",
                                headers = mapOf("Authorization" to "Bearer super-secret-token"),
                            ),
                            NetworkResponseInput(200),
                        ),
                ).withSessionId("current"),
            )
            application {
                // redactionPolicy defaults to RedactionPolicy.default(), which masks the
                // Authorization header the permissive capture-time policy above let through.
                devConsoleModule(sessions, sessionCodes) {
                    this.networkTransactions = networkStore
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.get("/api/v1/network/har") {
                    header(HttpHeaders.Host, "localhost")
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                "export must re-run the current redaction policy, not just the capture-time one",
                !response.bodyAsText().contains("super-secret-token"),
            )
        }
}

private fun networkTransaction(id: String): NetworkTransaction =
    NetworkTransaction(
        id = id,
        startedAtEpochMs = 1,
        completedAtEpochMs = 2,
        capture =
            NetworkCaptureFactory(RedactionEngine(RedactionPolicy.default())).capture(
                NetworkRequestInput("GET", "https://api.test/orders"),
                NetworkResponseInput(200),
            ),
    )

private fun ByteArray.zipEntries(): Map<String, ByteArray> =
    buildMap {
        ZipInputStream(ByteArrayInputStream(this@zipEntries)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                put(entry.name, zip.readBytes())
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
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

private fun HttpRequestBuilder.controlHeaders(session: BrowserSession) {
    header(HttpHeaders.Host, "localhost")
    header(HttpHeaders.Authorization, "Bearer ${session.token}")
    header(HttpHeaders.Origin, "http://localhost")
    header("X-DevConsole-CSRF", session.csrfToken)
    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
}
