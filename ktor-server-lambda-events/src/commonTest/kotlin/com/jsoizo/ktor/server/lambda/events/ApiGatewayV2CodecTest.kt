package com.jsoizo.ktor.server.lambda.events

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiGatewayV2CodecTest {
    @Test
    fun decodesRawQueryAndCookies() {
        val request = decode(Fixtures.HTTP_V2)
        assertEquals("GET", request.method)
        assertEquals("a=1&a=2&b=%20x", request.rawQuery)
        assertEquals("c1=v1; c2=v2", request.headers.single { it.first.equals("cookie", ignoreCase = true) }.second)
        assertEquals("198.51.100.7", request.remoteAddress)
        assertFalse(request.isFunctionUrl)
    }

    @Test
    fun stripsStageOnlyWhenWholeSegmentMatches() {
        assertEquals("/items", decode(Fixtures.HTTP_V2).path)
        assertEquals("/production/x", decode(Fixtures.HTTP_V2.replace("/prod/items", "/production/x")).path)
        assertEquals("/", decode(Fixtures.HTTP_V2.replace("/prod/items", "/prod")).path)
        assertEquals("/prod/items", decode(Fixtures.HTTP_V2, CodecConfig(stripStage = false)).path)
    }

    @Test
    fun recognizesFunctionUrlAndKeepsDefaultStagePath() {
        val request = decode(Fixtures.functionUrl)
        assertTrue(request.isFunctionUrl)
        assertEquals("/hello", request.path)
        assertEquals(0, request.body.size)
    }

    @Test
    fun encodesSetCookieIntoCookiesArrayAndJoinsOtherDuplicates() {
        val json = LambdaHttpCodecs.decode(Fixtures.parse(Fixtures.HTTP_V2)).encode(
            LambdaHttpResponse(
                status = 200,
                headers = listOf(
                    "Vary" to "Accept",
                    "Vary" to "Origin",
                    "Set-Cookie" to "a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT",
                    "Set-Cookie" to "b=2",
                ),
                body = ByteArray(0),
            ),
        )
        assertEquals("200", json["statusCode"]!!.jsonPrimitive.content)
        val headers = json["headers"]!!.jsonObject
        assertEquals("Accept,Origin", headers["Vary"]!!.jsonPrimitive.content)
        assertNull(headers["Set-Cookie"])
        assertEquals(
            JsonArray(listOf(JsonPrimitive("a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT"), JsonPrimitive("b=2"))),
            json["cookies"]!!.jsonArray,
        )
    }

    @Test
    fun keepsOneCookieHeaderWhenEventCarriesBothCookiesAndCookieHeader() {
        val event = Fixtures.HTTP_V2.replace("\"accept\": \"text/html,application/json\"", "\"cookie\": \"c1=v1; c2=v2\"")
        assertEquals(1, decode(event).headers.count { it.first.equals("cookie", ignoreCase = true) })
    }

    @Test
    fun acceptsRawQueryStringWithLeadingQuestionMark() {
        assertEquals(
            "a=1",
            decode(Fixtures.HTTP_V2.replace("\"rawQueryString\": \"a=1&a=2&b=%20x\"", "\"rawQueryString\": \"?a=1\"")).rawQuery,
        )
    }

    @Test
    fun keepsStageSegmentWhenRequestCameThroughCustomDomain() {
        // Custom domain mappings remove the stage, so a leading segment equal to it is a real path.
        val event = Fixtures.HTTP_V2
            .replace("/prod/items", "/prod/items")
            .replace("\"domainName\": \"xyz.execute-api.ap-northeast-1.amazonaws.com\"", "\"domainName\": \"api.example.com\"")
        assertEquals("/prod/items", decode(event).path)
    }

    private fun decode(json: String, config: CodecConfig = CodecConfig()) = LambdaHttpCodecs.decode(Fixtures.parse(json), config).request
}
