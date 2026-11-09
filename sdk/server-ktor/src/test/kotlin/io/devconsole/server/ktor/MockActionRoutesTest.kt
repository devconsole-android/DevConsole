/**
 * @author Shakib
 * @since 03/11/26
 */
@file:Suppress("MatchingDeclarationName")

package io.devconsole.server.ktor

import io.devconsole.mocks.MockAction
import io.devconsole.mocks.MockEngine
import io.devconsole.mocks.MockRule
import io.devconsole.server.api.BrowserSession
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.SessionCodeAuthority
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockActionRoutesTest {
    @Test
    fun `every non-static action type round-trips into the engine`() =
        testApplication {
            val env = MockRoutes()
            installMockRoutes(env)
            val session = mockApprovedSession(env)

            val cases =
                listOf(
                    Triple(
                        "tpl",
                        "id=tpl&priority=1&actionType=template&status=201&body=%7B%7Bpath%7D%7D",
                        MockAction.TemplateResponse(201, "{{path}}"),
                    ),
                    Triple(
                        "status",
                        "id=status&priority=1&actionType=status_override&status=204",
                        MockAction.StatusOverride(204),
                    ),
                    Triple(
                        "body",
                        "id=body&priority=1&actionType=body_replacement&body=patched",
                        MockAction.BodyReplacement("patched"),
                    ),
                    Triple(
                        "fail",
                        "id=fail&priority=1&actionType=connection_failure&failureMessage=offline",
                        MockAction.ConnectionFailure("offline"),
                    ),
                    Triple(
                        "timeout",
                        "id=timeout&priority=1&actionType=timeout&timeoutMs=1500",
                        MockAction.Timeout(1500),
                    ),
                    Triple(
                        "remote",
                        "id=remote&priority=1&actionType=map_remote&remoteHost=staging.api&remotePath=%2Fv2",
                        MockAction.MapRemote(host = "staging.api", path = "/v2"),
                    ),
                    Triple(
                        "throttle",
                        "id=throttle&priority=1&actionType=throttle&bytesPerSecond=50000&latencyMs=200",
                        MockAction.Throttle(50_000, 200),
                    ),
                    Triple("pass", "id=pass&priority=1&actionType=passthrough", MockAction.Passthrough),
                )
            cases.forEach { (id, body, expected) ->
                assertEquals(HttpStatusCode.Created, postMockRule(session, body).status)
                assertEquals(expected, env.mocks.actionOf(id))
            }
        }

    @Test
    fun `a looping two-step sequence builds nested actions`() =
        testApplication {
            val env = MockRoutes()
            installMockRoutes(env)
            val session = mockApprovedSession(env)

            val created =
                postMockRule(
                    session,
                    "id=seq&priority=1&actionType=sequence&loop=true" +
                        "&stepType=static&stepStatus=200&stepBody=first&stepDelayMs=100&stepTimeoutMs=" +
                        "&stepType=connection_failure&stepStatus=&stepBody=down&stepDelayMs=&stepTimeoutMs=",
                )

            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(
                MockAction.Sequence(
                    listOf(
                        MockAction.Delay(100, MockAction.StaticResponse(200, "first")),
                        MockAction.ConnectionFailure("down"),
                    ),
                    loop = true,
                ),
                env.mocks.actionOf("seq"),
            )
        }

    @Test
    fun `invalid map remote and throttle forms are rejected`() =
        testApplication {
            val env = MockRoutes()
            installMockRoutes(env)
            val session = mockApprovedSession(env)

            val remote = postMockRule(session, "id=remote&priority=1&actionType=map_remote")
            val throttle =
                postMockRule(session, "id=slow&priority=1&actionType=throttle&bytesPerSecond=999999999")

            assertEquals(HttpStatusCode.BadRequest, remote.status)
            assertEquals(HttpStatusCode.BadRequest, throttle.status)
            assertTrue(env.mocks.rules().isEmpty())
        }

    @Test
    fun `rule payloads include actionDetail for a delayed static and a sequence`() =
        testApplication {
            val env =
                MockRoutes(
                    mocks =
                        MockEngine(
                            listOf(
                                MockRule(
                                    id = "delayed",
                                    priority = 0,
                                    action = MockAction.Delay(300, MockAction.StaticResponse(200, "hi")),
                                ),
                                MockRule(
                                    id = "seq",
                                    priority = 0,
                                    action =
                                        MockAction.Sequence(
                                            listOf(MockAction.StaticResponse(200, "a"), MockAction.Passthrough),
                                        ),
                                ),
                            ),
                        ),
                )
            installMockRoutes(env)
            val session = mockApprovedSession(env)

            val body = client.mockRulesBody(session)

            assertTrue(
                body.contains(
                    "\"actionDetail\":{\"type\":\"delay\",\"durationMs\":300," +
                        "\"next\":{\"type\":\"static\",\"statusCode\":200,\"body\":\"hi\"," +
                        "\"bodyTruncated\":false,\"headers\":{}}}",
                ),
            )
            assertTrue(
                body.contains(
                    "\"actionDetail\":{\"type\":\"sequence\",\"loop\":false,\"steps\":[" +
                        "{\"type\":\"static\",\"statusCode\":200,\"body\":\"a\",\"bodyTruncated\":false," +
                        "\"headers\":{}},{\"type\":\"passthrough\"}]}",
                ),
            )
        }

    @Test
    fun `a request without actionType still creates a static response`() =
        testApplication {
            val env = MockRoutes()
            installMockRoutes(env)
            val session = mockApprovedSession(env)

            val created = postMockRule(session, "id=plain&priority=1&status=201&body=mocked")

            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(MockAction.StaticResponse(201, "mocked"), env.mocks.actionOf("plain"))
        }
}

private class MockRoutes(
    val sessions: SessionAuthority = SessionAuthority(),
    val mocks: MockEngine = MockEngine(emptyList()),
) {
    val codes = SessionCodeAuthority(sessions)
}

private fun ApplicationTestBuilder.installMockRoutes(env: MockRoutes) {
    application {
        protectedDevConsoleModule(env.sessions, env.codes) {
            mockEngine = env.mocks
            mocksEditable = true
        }
    }
}

private fun HttpRequestBuilder.mockControlHeaders(session: BrowserSession) {
    header(HttpHeaders.Host, "localhost")
    header(HttpHeaders.Authorization, "Bearer ${session.token}")
    header(HttpHeaders.Origin, "http://localhost")
    header("X-DevConsole-CSRF", session.csrfToken)
}

private suspend fun ApplicationTestBuilder.postMockRule(
    session: BrowserSession,
    body: String,
): HttpResponse =
    client.post("/api/v1/mocks/rules") {
        mockControlHeaders(session)
        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
        setBody(body)
    }

private suspend fun HttpClient.mockRulesBody(session: BrowserSession): String =
    get("/api/v1/mocks/rules") {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Authorization, "Bearer ${session.token}")
    }.bodyAsText()

private fun MockEngine.actionOf(id: String): MockAction = rules().first { it.id == id }.action

private suspend fun ApplicationTestBuilder.mockApprovedSession(env: MockRoutes): BrowserSession {
    val code = env.codes.issueCode().code
    val response =
        client.post("/api/v1/auth/session-code/exchange") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            setBody("code=$code")
        }
    val token = Regex("\"accessToken\":\"([^\"]+)\"").find(response.bodyAsText())!!.groupValues[1]
    return env.sessions.sessionForToken(token)!!
}
