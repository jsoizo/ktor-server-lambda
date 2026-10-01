package com.jsoizo.ktor.server.lambda.events

import io.ktor.http.parseQueryString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApiGatewayV1CodecTest {
    private val event = LambdaHttpCodecs.decode(Fixtures.parse(Fixtures.REST_V1))
    private val request = event.request

    @Test
    fun decodesRequestLine() {
        assertEquals("POST", request.method)
        assertEquals("/users/42", request.path)
        assertEquals("abc.execute-api.ap-northeast-1.amazonaws.com", request.host)
        assertEquals("https", request.scheme)
        assertEquals("203.0.113.10", request.remoteAddress)
        assertEquals("hello", request.body.decodeToString())
    }

    @Test
    fun multiValueFieldsWinOverSingleValueFields() {
        assertEquals(listOf("text/html", "application/json"), request.headers.filter { it.first == "Accept" }.map { it.second })
        // Names missing from multiValueHeaders are filled in from headers.
        assertEquals(listOf("last"), request.headers.filter { it.first == "X-Single" }.map { it.second })
        assertEquals("q=a%20b&q=b&lang=ja", request.rawQuery)
    }

    @Test
    fun reencodesQueryValuesWithReservedCharacters() {
        val event = Fixtures.REST_V1.replace("\"q\": [\"a b\", \"b\"], \"lang\": [\"ja\"]", "\"q\": [\"a+b&c=d\"]")
        val query = LambdaHttpCodecs.decode(Fixtures.parse(event)).request.rawQuery
        assertEquals("q=a%2Bb%26c%3Dd", query)
        assertEquals("a+b&c=d", parseQueryString(query)["q"])
    }

    @Test
    fun doesNotDuplicateHeadersPresentInBothMaps() {
        // Ktor answers 400 when Host appears twice.
        assertEquals(1, request.headers.count { it.first.equals("Host", ignoreCase = true) })
    }

    @Test
    fun acceptsNullMapsAndBodyFromTestConsoleEvents() {
        val event = Fixtures.parse(
            """
            {"resource":"/","path":"/","httpMethod":"GET","headers":null,"multiValueHeaders":null,
             "queryStringParameters":null,"multiValueQueryStringParameters":null,
             "requestContext":{},"body":null,"isBase64Encoded":false}
            """,
        )
        val decoded = LambdaHttpCodecs.decode(event).request
        assertEquals("", decoded.rawQuery)
        assertEquals(0, decoded.body.size)
    }

    @Test
    fun fallsBackToSingleValueQueryWhenMultiValueMapIsEmpty() {
        val event = Fixtures.REST_V1.replace(
            "\"multiValueQueryStringParameters\": { \"q\": [\"a b\", \"b\"], \"lang\": [\"ja\"] }",
            "\"multiValueQueryStringParameters\": {}",
        )
        assertEquals("q=b", LambdaHttpCodecs.decode(Fixtures.parse(event)).request.rawQuery)
    }

    @Test
    fun rejectsInvalidBase64BodyAsInvalidEvent() {
        val event = Fixtures.REST_V1.replace("\"aGVsbG8=\"", "\"not*base64\"")
        assertFailsWith<InvalidEventException> { LambdaHttpCodecs.decode(Fixtures.parse(event)) }
    }

    @Test
    fun stripsConfiguredBasePathOnSegmentBoundary() {
        val config = CodecConfig(stripBasePath = "/users")
        assertEquals("/42", LambdaHttpCodecs.decode(Fixtures.parse(Fixtures.REST_V1), config).request.path)
        val other = CodecConfig(stripBasePath = "/use")
        assertEquals("/users/42", LambdaHttpCodecs.decode(Fixtures.parse(Fixtures.REST_V1), other).request.path)
    }

    @Test
    fun encodesEverySetCookieAsSeparateMultiValueEntry() {
        val json = event.encode(
            LambdaHttpResponse(
                status = 201,
                headers = listOf(
                    "Content-Type" to "text/plain",
                    "Set-Cookie" to "a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT",
                    "Set-Cookie" to "b=2",
                ),
                body = "ok".encodeToByteArray(),
            ),
        )
        assertEquals(201, json["statusCode"]!!.jsonPrimitive.content.toInt())
        val headers = json["multiValueHeaders"]!!.jsonObject
        assertEquals(
            JsonArray(listOf(JsonPrimitive("a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT"), JsonPrimitive("b=2"))),
            headers["Set-Cookie"]!!.jsonArray,
        )
        assertEquals("ok", json["body"]!!.jsonPrimitive.content)
        assertEquals("false", json["isBase64Encoded"]!!.jsonPrimitive.content)
    }
}
