package com.jsoizo.ktor.server.lambda.events

import kotlin.test.Test
import kotlin.test.assertEquals

class BinaryBodyPolicyTest {
    private val text = "héllo".encodeToByteArray()
    private val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)

    @Test
    fun decidesBase64FromContentTypeEncodingAndBytes() {
        val cases = listOf(
            Triple(listOf("Content-Type" to "text/html; charset=utf-8"), text, false),
            Triple(listOf("Content-Type" to "application/problem+json"), text, false),
            Triple(listOf("content-type" to "Application/JSON"), text, false),
            Triple(listOf("Content-Type" to "image/png"), text, true),
            Triple(listOf("Content-Type" to "application/json", "Content-Encoding" to "gzip"), text, true),
            Triple(listOf("Content-Type" to "text/plain"), invalidUtf8, true),
            Triple(emptyList(), text, false),
            Triple(listOf("Content-Type" to "image/png"), ByteArray(0), false),
        )
        for ((headers, body, expected) in cases) {
            assertEquals(expected, BinaryBodyPolicy.Default.shouldEncode(headers, body), headers.toString())
        }
    }
}

class BodyEncodingTest {
    @Test
    fun neverSendsInvalidUtf8AsTextEvenIfPolicySaysText() {
        val event = LambdaHttpCodecs.decode(
            Fixtures.parse(Fixtures.HTTP_V2),
            CodecConfig(binaryBodyPolicy = { _, _ -> false }),
        )
        val json = event.encode(LambdaHttpResponse(200, emptyList(), byteArrayOf(0xC3.toByte(), 0x28)))
        assertEquals("true", json["isBase64Encoded"].toString())
    }
}
