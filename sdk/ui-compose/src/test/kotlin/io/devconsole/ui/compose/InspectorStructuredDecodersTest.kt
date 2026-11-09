/**
 * @author Shakib
 * @since 03/11/26
 */
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class InspectorStructuredDecodersTest {
    private fun base64Url(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun sampleJwt(
        headerJson: String = """{"alg":"HS256","typ":"JWT"}""",
        payloadJson: String,
        signature: String = "sig123",
    ): String = "${base64Url(headerJson)}.${base64Url(payloadJson)}.$signature"

    // --- JWT decode ----------------------------------------------------------------------------

    @Test
    fun `decodes valid jwt from authorization header and formats header, payload and valid exp`() {
        val nowEpochMs = 1_700_000_000_000L // 1700000000s
        val expSeconds = 1_700_000_900L // +15 min
        val iatSeconds = 1_699_999_700L // -5 min
        val token =
            sampleJwt(
                payloadJson = """{"sub":"user-42","exp":$expSeconds,"iat":$iatSeconds}""",
            )
        val transaction =
            InspectorTransactionUi(
                id = "tx-jwt-valid",
                method = "GET",
                host = "api.example.test",
                path = "/v1/profile",
                statusCode = 200,
                durationMs = 42,
                requestHeaders = mapOf("authorization" to "Bearer $token"),
            )

        val jwts = extractTransactionJwts(transaction, nowEpochMs = nowEpochMs)

        assertEquals(1, jwts.size)
        val jwt = jwts.single()
        assertEquals("Authorization", jwt.source)
        assertEquals(
            """
            {
              "alg": "HS256",
              "typ": "JWT"
            }
            """.trimIndent(),
            jwt.headerJson,
        )
        assertEquals(
            """
            {
              "sub": "user-42",
              "exp": 1700000900,
              "iat": 1699999700
            }
            """.trimIndent(),
            jwt.payloadJson,
        )
        val expClaim = jwt.timestamps.firstOrNull { it.claim == "exp" }
        assertNotNull(expClaim)
        assertFalse(expClaim!!.isExpired)
        assertEquals("valid for 15 min", expClaim.relativeStatus)
        assertTrue(expClaim.humanTime.contains("2023-11-14"))
    }

    @Test
    fun `marks jwt as expired relative to injected clock and extracts response json token fields`() {
        val nowEpochMs = 1_700_001_000_000L
        val expSeconds = 1_700_000_000L // in the past relative to nowEpochMs
        val accessToken = sampleJwt(payloadJson = """{"sub":"u-1","exp":$expSeconds}""")
        val idToken = sampleJwt(payloadJson = """{"sub":"u-1","nbf":1700000400}""")
        val transaction =
            InspectorTransactionUi(
                id = "tx-jwt-expired",
                method = "POST",
                host = "auth.example.test",
                path = "/oauth/token",
                statusCode = 200,
                durationMs = 95,
                responsePreview =
                    """{"access_token":"$accessToken","nested":{"id_token":"$idToken"},"token":"not-a-jwt"}""",
            )

        val jwts = extractTransactionJwts(transaction, nowEpochMs = nowEpochMs)

        assertEquals(2, jwts.size)
        val decodedAccess = jwts.first { it.source == "response.access_token" }
        val expClaim = decodedAccess.timestamps.single { it.claim == "exp" }
        assertTrue(expClaim.isExpired)
        assertEquals("expired 17 min ago", expClaim.relativeStatus)
        assertTrue(expClaim.humanTime.endsWith("UTC"))

        val decodedId = jwts.first { it.source == "response.id_token" }
        val nbfClaim = decodedId.timestamps.single { it.claim == "nbf" }
        assertEquals("active since 10 min ago", nbfClaim.relativeStatus)
    }

    @Test
    fun `jwt spans read at the coarsest precise unit`() {
        assertEquals("30 s", formatJwtSpan(30))
        assertEquals("1 min", formatJwtSpan(60))
        assertEquals("15 min", formatJwtSpan(900))
        assertEquals("2 h 5 min", formatJwtSpan(2 * 3_600 + 300))
        assertEquals("3 h", formatJwtSpan(3 * 3_600))
        assertEquals("1 day", formatJwtSpan(86_400))
        assertEquals("45 days", formatJwtSpan(45 * 86_400L))
        assertEquals("about 5 months", formatJwtSpan(150 * 86_400L))
        assertEquals("about 3 years", formatJwtSpan(1_154 * 86_400L))
    }

    @Test
    fun `rejects malformed jwt tokens cleanly`() {
        assertNull(decodeJwt("not.a"))
        assertNull(decodeJwt("a.b.c.d"))
        assertNull(decodeJwt(".."))
        assertNull(decodeJwt("${base64Url("not-json")}.${base64Url("""{"sub":"1"}""")}.sig"))
        assertNull(decodeJwt("${base64Url("[1,2,3]")}.${base64Url("""{"sub":"1"}""")}.sig"))
        assertNull(decodeJwt("!!!invalid-base64!!!.${base64Url("""{"sub":"1"}""")}.sig"))
    }

    @Test
    fun `never decodes redacted jwt values in headers or response fields`() {
        val validLookingJwtWithRedactedPayload =
            sampleJwt(payloadJson = """{"sub":"<redacted>"}""")
        assertNull(decodeJwt("<redacted>"))
        assertNull(decodeJwt("Bearer <redacted>"))
        assertNull(decodeJwt("Bearer ***"))
        assertNull(decodeJwt(validLookingJwtWithRedactedPayload))

        val transaction =
            InspectorTransactionUi(
                id = "tx-jwt-redacted",
                method = "POST",
                host = "auth.example.test",
                path = "/oauth/token",
                statusCode = 200,
                durationMs = 50,
                requestHeaders = mapOf("Authorization" to "Bearer <redacted>"),
                responsePreview =
                    """
                    {"access_token":"<redacted>","id_token":"***","token":"$validLookingJwtWithRedactedPayload"}
                    """.trimIndent(),
            )

        assertTrue(extractTransactionJwts(transaction).isEmpty())
    }

    // --- Form-urlencoded parse -----------------------------------------------------------------

    @Test
    fun `parses urlencoded form fields with percent encoding, plus spaces, repeated keys and empty values`() {
        val raw = "user%20name= Jane+Doe &tag=alpha&tag=beta&empty=&flag& filter%5B0%5D =a%26b%3Dc"

        val fields = parseFormUrlEncoded(raw)

        assertEquals(
            listOf(
                DecodedFormField("user name", " Jane Doe "),
                DecodedFormField("tag", "alpha"),
                DecodedFormField("tag", "beta"),
                DecodedFormField("empty", ""),
                DecodedFormField("flag", ""),
                DecodedFormField(" filter[0] ", "a&b=c"),
            ),
            fields,
        )
    }

    @Test
    fun `detects form urlencoded requests from content type header or lookalike body`() {
        val headersWithCharset = mapOf(" content-type " to "application/x-www-form-urlencoded; charset=UTF-8")
        assertTrue(isFormUrlEncodedRequest(headersWithCharset.mapKeys { it.key.trim() }, "a=1&b=2"))
        assertFalse(isFormUrlEncodedRequest(mapOf("Content-Type" to "application/json"), """{"a":1}"""))
        assertFalse(isFormUrlEncodedRequest(mapOf("Content-Type" to "application/x-www-form-urlencoded"), "<redacted>"))
    }

    // --- GraphQL detection ---------------------------------------------------------------------

    @Test
    @Suppress("ktlint:standard:max-line-length") // Single JSON fixture line; wrapping would change the body.
    fun `detects graphql post with json query, operation name and pretty-printed variables`() {
        val body =
            """
            {
              "query": "mutation CreateOrder(${'$'}input: OrderInput!) {\n  createOrder(input: ${'$'}input) { id status }\n}",
              "operationName": "CreateOrder",
              "variables": {"input":{"sku":"ABC-1","qty":2}}
            }
            """.trimIndent()

        val decoded =
            detectGraphQlRequest(
                method = "POST",
                url = "https://api.example.test/v1/query",
                path = "/v1/query",
                body = body,
            )

        assertNotNull(decoded)
        assertEquals("mutation", decoded!!.operationType)
        assertEquals("CreateOrder", decoded.operationName)
        assertEquals(
            "mutation CreateOrder(\$input: OrderInput!) {\n  createOrder(input: \$input) { id status }\n}",
            decoded.query,
        )
        assertEquals(
            """
            {
              "input": {
                "sku": "ABC-1",
                "qty": 2
              }
            }
            """.trimIndent(),
            decoded.variablesJson,
        )
    }

    @Test
    fun `parses operation type and name from query text when operationName field is absent`() {
        val body = """{"query":"# comment\nsubscription OnOrderUpdated { orderUpdated { id } }"}"""

        val decoded =
            detectGraphQlRequest(
                method = "POST",
                url = "https://api.example.test/graphql",
                path = "/graphql",
                body = body,
            )

        assertNotNull(decoded)
        assertEquals("subscription", decoded!!.operationType)
        assertEquals("OnOrderUpdated", decoded.operationName)
        assertNull(decoded.variablesJson)
    }

    @Test
    fun `ignores non-graphql json bodies even on post requests`() {
        val ordinaryJson = """{"userId":42,"action":"checkout"}"""
        assertNull(
            detectGraphQlRequest(
                method = "POST",
                url = "https://api.example.test/v1/checkout",
                path = "/v1/checkout",
                body = ordinaryJson,
            ),
        )
        assertNull(
            detectGraphQlRequest(
                method = "POST",
                url = "https://api.example.test/graphql",
                path = "/graphql",
                body = ordinaryJson,
            ),
        )
    }
}
