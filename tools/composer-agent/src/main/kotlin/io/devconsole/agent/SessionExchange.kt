package io.devconsole.agent

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal object SessionExchange {
    fun exchange(
        deviceUrl: URI,
        code: String,
        label: String,
    ): String {
        val form = "code=${code.urlEncode()}&browserLabel=${label.urlEncode()}"
        val request =
            HttpRequest
                .newBuilder(URI("${deviceUrl.scheme}://${deviceUrl.authority}/api/v1/auth/session-code/exchange"))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != HTTP_OK) {
            val reason = CODE_FIELD.find(response.body())?.groupValues?.get(1) ?: "HTTP ${response.statusCode()}"
            throw AgentFatalException(
                "The device rejected the session code ($reason). Get a fresh one from the dashboard.",
            )
        }
        return TOKEN_FIELD.find(response.body())?.groupValues?.get(1)
            ?: throw AgentFatalException("The device answered without an access token.")
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")

    private const val HTTP_OK = 200
    private const val REQUEST_TIMEOUT_SECONDS = 15L
    private val TOKEN_FIELD = Regex("\"accessToken\"\\s*:\\s*\"([^\"]+)\"")
    private val CODE_FIELD = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"")
}
