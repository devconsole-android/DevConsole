/**
 * @author Zakaria
 * @since 26/10/26
 */
package io.devconsole.server.ktor

import io.devconsole.locationping.LocationPingOutcome
import io.devconsole.locationping.LocationPingSchema
import io.devconsole.locationping.LocationPingStore
import io.devconsole.locationping.LocationPingStream
import io.devconsole.locationping.LocationPingTracking
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.CommandAuditResult
import io.devconsole.server.api.InMemoryCommandAuditLog
import io.devconsole.server.api.ServerMetadata
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocationPingRoutesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the overview requires an authenticated session`() =
        testApplication {
            val store = storeWithOneRidePing()
            application { protectedDevConsoleModule(SessionAuthority()) { locationPings = { store } } }

            val response = client.get("/api/v1/location-pings") { header(HttpHeaders.Host, "localhost") }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `the overview lists streams, schema, storage and captured tracks`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = storeWithOneRidePing()
            application { protectedDevConsoleModule(sessions, sessionCodes) { locationPings = { store } } }
            val session = approvedSession(sessions, sessionCodes)

            val body = client.get("/api/v1/location-pings") { authHeaders(session) }.bodyAsText()

            assertTrue(body.contains("\"configured\":true"))
            assertTrue(body.contains("{\"name\":\"ride\",\"path\":\"/v1/rides/pings\",\"enabled\":true}"))
            assertTrue(body.contains("\"groupLabel\":\"Order\""))
            assertTrue(body.contains("\"hasFixTime\":false"))
            assertTrue(body.contains("\"groupId\":\"order-1\""))
        }

    @Test
    fun `an unconfigured runtime reports that rather than failing`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            application { protectedDevConsoleModule(sessions, sessionCodes) }
            val session = approvedSession(sessions, sessionCodes)

            val response = client.get("/api/v1/location-pings") { authHeaders(session) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"configured\":false"))
        }

    @Test
    fun `a track returns its points with a status the map can colour`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = storeWithOneRidePing()
            application { protectedDevConsoleModule(sessions, sessionCodes) { locationPings = { store } } }
            val session = approvedSession(sessions, sessionCodes)
            val file = store.tracks().single().file

            val body =
                client
                    .get(
                        "/api/v1/location-pings/track?stream=ride&file=$file",
                    ) { authHeaders(session) }
                    .bodyAsText()

            assertTrue(body.contains("\"lat\":23.79,\"lng\":90.41"))
            assertTrue(body.contains("\"status\":\"failed\",\"statusCode\":401"))
            assertTrue(body.contains("\"fields\":{\"order_id\":\"order-1\"}"))
        }

    @Test
    fun `an unknown track is not found`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = storeWithOneRidePing()
            application { protectedDevConsoleModule(sessions, sessionCodes) { locationPings = { store } } }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.get(
                    "/api/v1/location-pings/track?stream=ride&file=../x.jsonl",
                ) { authHeaders(session) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `toggling a stream needs csrf and is audited`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val audit = InMemoryCommandAuditLog()
            val store = storeWithOneRidePing()
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    locationPings = { store }
                    commandAuditLog = audit
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val withoutCsrf =
                client.put("/api/v1/location-pings/streams/ride") {
                    authHeaders(session)
                    setBody("false")
                }
            val withCsrf =
                client.put("/api/v1/location-pings/streams/ride") {
                    controlHeaders(session)
                    setBody("false")
                }

            assertEquals(HttpStatusCode.Forbidden, withoutCsrf.status)
            assertEquals(HttpStatusCode.OK, withCsrf.status)
            assertFalse(store.isEnabled("ride"))
            assertTrue(
                audit.events().any {
                    it.commandType == "location-pings.toggle" &&
                        it.result == CommandAuditResult.SUCCESS
                },
            )
        }

    @Test
    fun `toggling an unknown stream is a validation failure`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = storeWithOneRidePing()
            application { protectedDevConsoleModule(sessions, sessionCodes) { locationPings = { store } } }
            val session = approvedSession(sessions, sessionCodes)

            val response =
                client.put("/api/v1/location-pings/streams/idle") {
                    controlHeaders(session)
                    setBody("true")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `deleting removes the captured files`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = storeWithOneRidePing()
            application { protectedDevConsoleModule(sessions, sessionCodes) { locationPings = { store } } }
            val session = approvedSession(sessions, sessionCodes)

            val body = client.delete("/api/v1/location-pings?stream=ride") { controlHeaders(session) }.bodyAsText()

            assertEquals("{\"filesDeleted\":1,\"filesLeft\":0}", body)
            assertTrue(store.tracks().isEmpty())
        }

    @Test
    fun `every location ping route is refused while network capture is disabled`() =
        testApplication {
            val sessions = SessionAuthority()
            val sessionCodes = SessionCodeAuthority(sessions)
            val store = storeWithOneRidePing()
            application {
                protectedDevConsoleModule(sessions, sessionCodes) {
                    locationPings = { store }
                    metadata = ServerMetadata(captureCategories = listOf("logs"))
                }
            }
            val session = approvedSession(sessions, sessionCodes)

            val read = client.get("/api/v1/location-pings") { authHeaders(session) }
            val delete = client.delete("/api/v1/location-pings") { controlHeaders(session) }

            assertEquals(HttpStatusCode.Forbidden, read.status)
            assertEquals(HttpStatusCode.Forbidden, delete.status)
            assertEquals(1, store.tracks().size)
        }

    private fun storeWithOneRidePing(): LocationPingStore =
        LocationPingStore(folder.newFolder(), writer = { it.run() }).apply {
            configure(
                LocationPingTracking(
                    streams = listOf(LocationPingStream("ride", "/v1/rides/pings")),
                    schema = LocationPingSchema(groupIdKey = "order_id", groupLabel = "Order"),
                ),
            )
            setEnabled("ride", true)
            record(
                "https://api.test/v1/rides/pings",
                """{"latitude":23.79,"longitude":90.41,"timestamp":1793016000000,"order_id":"order-1"}""",
                LocationPingOutcome.Answered(401),
            )
        }
}

private fun HttpRequestBuilder.controlHeaders(session: BrowserSession) {
    header(HttpHeaders.Host, "localhost")
    header(HttpHeaders.Authorization, "Bearer ${session.token}")
    header(HttpHeaders.Origin, "http://localhost")
    header("X-DevConsole-CSRF", session.csrfToken)
}

private fun HttpRequestBuilder.authHeaders(session: BrowserSession) {
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
