package io.devconsole.server.ktor

import io.devconsole.security.RedactionPolicy
import io.devconsole.server.api.BindingMode
import io.devconsole.server.api.ServerStartResult
import io.devconsole.server.api.StartRequest
import io.devconsole.state.SessionFeatureFlags
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerHostSharingTest {
    private fun KtorLocalServerEngine.reconfigureHosts(
        hosts: Set<String>,
        editable: Boolean,
    ) = reconfigure(
        featureFlags = SessionFeatureFlags(emptyList()),
        composerEnabled = true,
        composerAllowedHosts = hosts,
        stateMutationsEnabled = false,
        redactionPolicy = RedactionPolicy.default(),
        composerHostsEditable = editable,
    )

    @Test
    fun `the engine answers for configured and dashboard-added hosts and keeps them across an identical reconfigure`() =
        runBlocking {
            val engine =
                KtorLocalServerEngine(
                    composerEnabled = true,
                    composerAllowedHosts = setOf("fixed.example.test"),
                    composerHostsEditable = true,
                )
            val started = engine.start(StartRequest(BindingMode.LOOPBACK, 8540..8559))
            check(started is ServerStartResult.Started) { "server did not start: $started" }
            val client = HttpClient(CIO)
            val exchange =
                client
                    .post(
                        "http://${started.endpoint.host}:${started.endpoint.port}/api/v1/auth/session-code/exchange",
                    ) {
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                        setBody("code=${started.sessionCode.code}&browserLabel=Chrome")
                    }.bodyAsText()
            val token = Regex("\"accessToken\":\"([^\"]+)\"").find(exchange)!!.groupValues[1]
            val csrf = Regex("\"csrfToken\":\"([^\"]+)\"").find(exchange)!!.groupValues[1]

            try {
                assertTrue(engine.permitsComposerHost("https://fixed.example.test/a"))
                assertFalse(engine.permitsComposerHost("https://added.example.test/a"))

                client.post("http://${started.endpoint.host}:${started.endpoint.port}/api/v1/composer/hosts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Origin, "http://${started.endpoint.host}:${started.endpoint.port}")
                    header("X-DevConsole-CSRF", csrf)
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    setBody("host=added.example.test")
                }

                assertTrue(engine.permitsComposerHost("https://added.example.test/a"))
                engine.reconfigureHosts(setOf("fixed.example.test"), editable = true)
                assertTrue(engine.permitsComposerHost("https://added.example.test/a"))
                engine.reconfigureHosts(setOf("another.example.test"), editable = true)
                assertFalse(engine.permitsComposerHost("https://added.example.test/a"))
                assertTrue(engine.permitsComposerHost("https://another.example.test/a"))
            } finally {
                client.close()
                engine.stop()
            }
        }
}
