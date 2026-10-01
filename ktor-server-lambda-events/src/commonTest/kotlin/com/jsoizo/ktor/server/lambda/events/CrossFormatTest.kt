package com.jsoizo.ktor.server.lambda.events

import kotlin.test.Test
import kotlin.test.assertEquals

/** Rules that every event format must follow, checked against one fixture of each. */
class CrossFormatTest {
    private val fixtures = mapOf(
        "v1" to Fixtures.REST_V1,
        "v2" to Fixtures.HTTP_V2,
        "alb multi" to Fixtures.ALB_MULTI,
        "alb single" to Fixtures.ALB_SINGLE,
    )

    @Test
    fun dropsOnlyHeadersKtorWouldRejectInsteadOfFailingTheRequest() {
        for ((name, fixture) in fixtures) {
            val event = Fixtures.parse(withExtraHeaders(fixture))
            val names = LambdaHttpCodecs.decode(event).request.headers.map { it.first }
            assertEquals(listOf("x-tab"), names.filter { it in injected }, name)
        }
    }

    @Test
    fun encodesCharactersThatArrivedDecodedButKeepsEncodedOnes() {
        val decoded = Fixtures.HTTP_V2.replace("/prod/items", "/prod/files/a b#c")
        assertEquals("/files/a%20b%23c", LambdaHttpCodecs.decode(Fixtures.parse(decoded)).request.path)
        val encoded = Fixtures.REST_V1.replace("\"path\": \"/users/42\"", "\"path\": \"/a%2Fb/100%25\"")
        assertEquals("/a%2Fb/100%25", LambdaHttpCodecs.decode(Fixtures.parse(encoded)).request.path)
    }

    private val injected = setOf("x-crlf", "x-ctl", "x (bad)", "x-tab")

    // Adds a header with CR/LF, one with a control character, one with an invalid name and a valid one with a tab.
    private fun withExtraHeaders(fixture: String): String {
        val bad = mapOf("x-crlf" to "a\\r\\nInjected: 1", "x-ctl" to "a\\u0001b", "x (bad)" to "v", "x-tab" to "a\\tb")
        val single = bad.entries.joinToString(", ") { (k, v) -> "\"$k\": \"$v\"" }
        val multi = bad.entries.joinToString(", ") { (k, v) -> "\"$k\": [\"$v\"]" }
        return fixture
            .replaceFirst("\"multiValueHeaders\": {", "\"multiValueHeaders\": {$multi, ")
            .replaceFirst("\"headers\": {", "\"headers\": {$single, ")
    }
}
