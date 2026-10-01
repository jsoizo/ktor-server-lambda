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
        val request = decode(Fixtures.albMulti)
        assertEquals("q=a%20b&q=c%2Bd", request.rawQuery)
        assertEquals("q=a%20b", decode(Fixtures.albSingle).rawQuery)
    }

    @Test
    fun takesRemoteAddressAndSchemeFromForwardedHeaders() {
        val request = decode(Fixtures.albMulti)
        assertEquals("10.0.0.5", request.remoteAddress)
        assertEquals("http", request.scheme)
        assertEquals(80, request.port)
        assertEquals("alb.example.com", request.host)
    }

    @Test
    fun takesRemoteAddressFromLastForwardedForEntry() {
        // A client can send its own X-Forwarded-For; ALB appends the real peer to the last one.
        val event = Fixtures.albMulti.replace(
            "\"x-forwarded-for\": [\"198.51.100.1, 10.0.0.5\"]",
            "\"x-forwarded-for\": [\"6.6.6.6\", \"198.51.100.1, 10.0.0.5\"]",
        )
        assertEquals("10.0.0.5", decode(event).remoteAddress)
    }

    @Test
    fun joinsRepeatedHeadersInSingleValueMode() {
        val json = LambdaHttpCodecs.decode(Fixtures.parse(Fixtures.albSingle)).encode(
            LambdaHttpResponse(200, listOf("Vary" to "Accept", "Vary" to "Origin"), ByteArray(0)),
        )
        assertEquals("Accept, Origin", json["headers"]!!.jsonObject["Vary"]!!.jsonPrimitive.content)
    }

    @Test
    fun answersInMultiValueModeWhenRequestUsedIt() {
        val json = LambdaHttpCodecs.decode(Fixtures.parse(Fixtures.albMulti)).encode(response())
        assertEquals("200 OK", json["statusDescription"]!!.jsonPrimitive.content)
        assertNull(json["headers"])
        assertEquals(2, json["multiValueHeaders"]!!.jsonObject["Set-Cookie"]!!.jsonArray.size)
    }

    @Test
    fun answersInSingleValueModeAndWarnsAboutDroppedCookies() {
        val warnings = mutableListOf<String>()
        val config = CodecConfig(onWarning = { warnings += it })
        val json = LambdaHttpCodecs.decode(Fixtures.parse(Fixtures.albSingle), config).encode(response())
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
