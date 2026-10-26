package io.devconsole.server.ktor

import io.devconsole.agent.ComposerAgent
import io.devconsole.server.api.BindingMode
import io.devconsole.server.api.Endpoint
import io.devconsole.server.api.ServerStartResult
import io.devconsole.server.api.SessionAuthority
import io.devconsole.server.api.StartRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class ComposerAgentEndToEndTest {
    @Test
    fun `an API only the computer can reach is called through the agent and the result comes back`() =
        runBlocking {
            val received = CopyOnWriteArrayList<String>()
            val api = MockWebServer()
            api.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        received +=
                            "${request.method} auth=${request.getHeader(
                                "Authorization",
                            )} body=${request.body.readUtf8()}"
                        return MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"method\":\"${request.method}\"}")
                    }
                }
            api.start(InetAddress.getByName("127.0.0.1"), 0)
            val authority = SessionAuthority()
            val engine =
                KtorLocalServerEngine(
                    sessionAuthority = authority,
                    composerEnabled = true,
                    composerAllowedHosts = setOf("127.0.0.1"),
                )
            val started = engine.start(StartRequest(BindingMode.LOOPBACK, 8520..8539))
            check(started is ServerStartResult.Started) { "server did not start: $started" }
            val client = HttpClient(CIO)
            val session = client.exchangeSession(started.endpoint, started.sessionCode.code)
            val agent =
                ComposerAgent(
                    deviceUrl = URI(started.endpoint.url("")),
                    token = session.token,
                    name = "e2e computer",
                )
            val agentThread = thread(isDaemon = true) { runCatching { agent.run() } }

            try {
                val agentId = client.awaitAgentId(started.endpoint, session)
                val itemUrl = "http://127.0.0.1:${api.port}/items/42?verbose=1"

                val onDevice = client.execute(started.endpoint, session, listOf("method" to "GET", "url" to itemUrl))
                assertEquals(
                    "the phone's own transport refuses private targets",
                    HttpStatusCode.Forbidden,
                    onDevice.status,
                )

                val results =
                    listOf("GET" to null, "POST" to "{\"name\":\"Ada\"}", "DELETE" to null).map { (method, body) ->
                        val form =
                            listOf("method" to method, "url" to itemUrl, "executor" to "agent:$agentId") +
                                (if (body == null) emptyList() else listOf("bodyType" to "JSON", "body" to body))
                        method to client.execute(started.endpoint, session, form)
                    }

                results.forEach { (method, response) ->
                    val text = response.bodyAsText()
                    assertEquals(text, HttpStatusCode.OK, response.status)
                    assertTrue(text, text.contains("\"executor\":\"agent:e2e computer\""))
                    assertTrue(text, text.contains("\"statusCode\":200"))
                    assertTrue(text, text.contains("\\\"method\\\":\\\"$method\\\""))
                }
                assertEquals(3, received.size)
                assertTrue(received.all { it.contains("auth=Bearer api-access-token") })
                assertTrue(received.any { it == "POST auth=Bearer api-access-token body={\"name\":\"Ada\"}" })
            } finally {
                agent.stop()
                agentThread.join(5_000)
                client.close()
                engine.stop()
                api.shutdown()
            }
        }
}

private class E2eSession(
    val token: String,
    val csrfToken: String,
)

private suspend fun HttpClient.exchangeSession(
    endpoint: Endpoint,
    code: String,
): E2eSession {
    val response =
        post(endpoint.url("/api/v1/auth/session-code/exchange")) {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            setBody("code=$code&browserLabel=Chrome")
        }
    val body = response.bodyAsText()
    return E2eSession(
        token = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1],
        csrfToken = Regex("\"csrfToken\":\"([^\"]+)\"").find(body)!!.groupValues[1],
    )
}

private suspend fun HttpClient.awaitAgentId(
    endpoint: Endpoint,
    session: E2eSession,
): String {
    repeat(50) {
        val listed =
            get(endpoint.url("/api/v1/composer/agents")) {
                header(HttpHeaders.Authorization, "Bearer ${session.token}")
            }.bodyAsText()
        Regex("\"id\":\"([^\"]+)\"").find(listed)?.let { return it.groupValues[1] }
        delay(100)
    }
    error("the agent never registered with the device")
}

private suspend fun HttpClient.execute(
    endpoint: Endpoint,
    session: E2eSession,
    form: List<Pair<String, String>>,
): HttpResponse =
    post(endpoint.url("/api/v1/composer/execute")) {
        header(HttpHeaders.Authorization, "Bearer ${session.token}")
        header(HttpHeaders.Origin, "http://${endpoint.host}:${endpoint.port}")
        header("X-DevConsole-CSRF", session.csrfToken)
        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
        setBody((form + ("header" to "Authorization: Bearer api-access-token")).formUrlEncode())
    }

private fun Endpoint.url(path: String): String = "http://$host:$port$path"
