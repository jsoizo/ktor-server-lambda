package com.jsoizo.ktor.server.lambda.events.internal

import com.jsoizo.ktor.server.lambda.events.InvalidEventException
import kotlin.io.encoding.Base64

internal fun decodeBody(body: String?, isBase64Encoded: Boolean): ByteArray = when {
    body.isNullOrEmpty() -> ByteArray(0)

    !isBase64Encoded -> body.encodeToByteArray()

    else -> try {
        Base64.decode(body)
    } catch (e: IllegalArgumentException) {
        throw InvalidEventException("Invalid base64 body", e)
    }
}

internal class EncodedBody(val body: String, val isBase64Encoded: Boolean)

internal fun encodeBody(body: ByteArray, base64: Boolean): EncodedBody {
    // A custom policy may call binary data text; replacing invalid bytes with U+FFFD would corrupt it.
    val text = if (base64) null else body.decodeUtf8OrNull()
    return when {
        body.isEmpty() -> EncodedBody("", false)
        text != null -> EncodedBody(text, false)
        else -> EncodedBody(Base64.encode(body), true)
    }
}

private fun ByteArray.decodeUtf8OrNull(): String? = try {
    decodeToString(throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    null
}
