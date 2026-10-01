package com.jsoizo.ktor.server.lambda.events.internal

import com.jsoizo.ktor.server.lambda.events.InvalidEventException
import kotlin.io.encoding.Base64

internal fun decodeBody(body: String?, isBase64Encoded: Boolean): ByteArray {
    if (body.isNullOrEmpty()) return ByteArray(0)
    if (!isBase64Encoded) return body.encodeToByteArray()
    return try {
        Base64.decode(body)
    } catch (e: IllegalArgumentException) {
        throw InvalidEventException("Invalid base64 body", e)
    }
}

internal class EncodedBody(val body: String, val isBase64Encoded: Boolean)

internal fun encodeBody(body: ByteArray, base64: Boolean): EncodedBody {
    if (body.isEmpty()) return EncodedBody("", false)
    if (!base64) {
        // A custom policy may call binary data text; replacing invalid bytes with U+FFFD would corrupt it.
        try {
            return EncodedBody(body.decodeToString(throwOnInvalidSequence = true), false)
        } catch (_: CharacterCodingException) {
        }
    }
    return EncodedBody(Base64.encode(body), true)
}
