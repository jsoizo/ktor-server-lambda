package com.jsoizo.ktor.server.lambda.events

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlbCodecTest {
    @Test
    fun keepsQueryPercentEncodedAsAlbSendsIt() {
        // ALB does not decode the query; decoding it again would double-encode.
        val request = decode(Fixtures.ALB_MULTI)
        assertEquals("q=a%20b&q=c%2Bd", request.rawQuery)
        assertEquals("q=a%20b", decode(Fixtures.ALB_SINGLE).rawQuery)
    }

    @Test
    fun takesRemoteAddressAndSchemeFromForwardedHeaders() {
        val request = decode(Fixtures.ALB_MULTI)
        assertEquals("10.0.0.5", request.remoteAddress)
        assertEquals("http", request.scheme)
        assertEquals(80, request.port)
        assertEquals("alb.example.com", request.host)
    }

    @Test
    fun takesRemoteAddressFromLastForwardedForEntry() {
        // A client can send its own X-Forwarded-For; ALB appends the real peer to the last one.
        val event = Fixtures.ALB_MULTI.replace(
            "\"x-forwarded-for\": [\"198.51.100.1, 10.0.0.5\"]",
            "\"x-forwarded-for\": [\"6.6.6.6\", \"198.51.100.1, 10.0.0.5\"]",
        )
        assertEquals("10.0.0.5", decode(event).remoteAddress)
    }

    @Test
    fun splitsClientPortThatAlbAppendsToForwardedFor() {
        val cases = mapOf(
            "198.51.100.1, 10.0.0.5:8080" to ("10.0.0.5" to 8080),
            "198.51.100.1, [2001:db8::7348]:8080" to ("2001:db8::7348" to 8080),
            "198.51.100.1, 2001:db8::7348" to ("2001:db8::7348" to null),
        )
        for ((forwardedFor, expected) in cases) {
            val event = Fixtures.ALB_MULTI.replace("\"198.51.100.1, 10.0.0.5\"", "\"$forwardedFor\"")
            val request = decode(event)
            assertEquals(expected, request.remoteAddress to request.remotePort, forwardedFor)
        }
    }

    @Test
    fun trustsTheLastForwardedProtoAndPort() {
        val event = Fixtures.ALB_MULTI
            .replace("\"x-forwarded-proto\": [\"http\"]", "\"x-forwarded-proto\": [\"http\", \"https\"]")
            .replace("\"x-forwarded-port\": [\"80\"]", "\"x-forwarded-port\": [\"80\", \"443\"]")
        val request = decode(event)
        assertEquals("https" to 443, request.scheme to request.port)
    }

    @Test
    fun alwaysSendsAReasonPhrase() {
        val event = LambdaHttpCodecs.decode(Fixtures.parse(Fixtures.ALB_SINGLE))
        assertEquals(
            "299 Successful",
            event.encode(LambdaHttpResponse(299, emptyList(), ByteArray(0)))["statusDescription"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "404 Not Found",
            event.encode(LambdaHttpResponse(404, emptyList(), ByteArray(0)))["statusDescription"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun joinsRepeatedHeadersInSingleValueMode() {
        val json = LambdaHttpCodecs.decode(Fixtures.parse(Fixtures.ALB_SINGLE)).encode(
            LambdaHttpResponse(200, listOf("Vary" to "Accept", "Vary" to "Origin"), ByteArray(0)),
        )
        assertEquals("Accept, Origin", json["headers"]!!.jsonObject["Vary"]!!.jsonPrimitive.content)
    }

    @Test
    fun answersInMultiValueModeWhenRequestUsedIt() {
        val json = LambdaHttpCodecs.decode(Fixtures.parse(Fixtures.ALB_MULTI)).encode(response())
        assertEquals("200 OK", json["statusDescription"]!!.jsonPrimitive.content)
        assertNull(json["headers"])
        assertEquals(2, json["multiValueHeaders"]!!.jsonObject["Set-Cookie"]!!.jsonArray.size)
    }

    @Test
    fun answersInSingleValueModeAndWarnsAboutDroppedCookies() {
        val warnings = mutableListOf<String>()
        val config = CodecConfig(onWarning = { warnings += it })
        val json = LambdaHttpCodecs.decode(Fixtures.parse(Fixtures.ALB_SINGLE), config).encode(response())
        assertNull(json["multiValueHeaders"])
        assertEquals("b=2", json["headers"]!!.jsonObject["Set-Cookie"]!!.jsonPrimitive.content)
        assertEquals(1, warnings.size)
    }

    private fun response() = LambdaHttpResponse(
        status = 200,
        headers = listOf("Set-Cookie" to "a=1", "Set-Cookie" to "b=2"),
        body = ByteArray(0),
    )

    private fun decode(json: String) = LambdaHttpCodecs.decode(Fixtures.parse(json)).request
}
