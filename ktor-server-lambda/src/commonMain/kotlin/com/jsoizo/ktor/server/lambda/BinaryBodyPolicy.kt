package com.jsoizo.ktor.server.lambda

/**
 * Decides whether a response body must be base64-encoded.
 *
 * Bodies that are not valid UTF-8 are base64-encoded regardless of the policy. REST APIs only decode
 * base64 bodies whose `Accept` matches the API's binary media types; see
 * [binary media types](https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-payload-encodings.html).
 */
public fun interface BinaryBodyPolicy {
    /** Returns `true` to send [body] base64-encoded. [headers] are the response headers. */
    public fun shouldEncode(headers: List<Pair<String, String>>, body: ByteArray): Boolean

    public companion object {
        /** Text when `Content-Type` is textual and there is no `Content-Encoding`; binary otherwise. */
        public val Default: BinaryBodyPolicy = BinaryBodyPolicy { headers, body ->
            when {
                body.isEmpty() -> false

                headers.any { it.first.equals("Content-Encoding", ignoreCase = true) } -> true

                else -> {
                    val contentType = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
                    if (contentType != null && !isTextContentType(contentType)) true else !isValidUtf8(body)
                }
            }
        }

        private val textTypes = setOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-www-form-urlencoded",
            "application/yaml",
        )

        internal fun isTextContentType(contentType: String): Boolean {
            val mediaType = contentType.substringBefore(';').trim().lowercase()
            return mediaType.startsWith("text/") ||
                mediaType in textTypes ||
                mediaType.endsWith("+json") ||
                mediaType.endsWith("+xml")
        }

        private fun isValidUtf8(bytes: ByteArray): Boolean = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }
}
