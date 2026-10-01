package com.jsoizo.ktor.server.lambda.handler

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64

/**
 * A synthetic request sent through the pipeline before a SnapStart snapshot, so that the classes and
 * caches it touches are already loaded when the snapshot is restored.
 *
 * It travels as a payload v2 event, so event decoding and response encoding are warmed up too. The call
 * has no invocation deadline, function ARN or authorizer data; routes used for priming must not need them.
 *
 * See [Lambda SnapStart runtime hooks](https://docs.aws.amazon.com/lambda/latest/dg/snapstart-runtime-hooks-java.html).
 */
public class PrimingRequest(
    /** HTTP method. */
    public val method: String,
    /** Path including any query string, such as `/health?deep=true`. */
    public val uri: String,
    /** Header fields to send. */
    public val headers: List<Pair<String, String>> = emptyList(),
    /** Request body. */
    public val body: ByteArray = ByteArray(0),
) {
    internal fun toEvent(): JsonObject = buildJsonObject {
        put("version", "2.0")
        put("rawPath", uri.substringBefore('?'))
        put("rawQueryString", uri.substringAfter('?', ""))
        putJsonObject("headers") {
            if (headers.none { it.first.equals("Host", ignoreCase = true) }) put("host", "localhost")
            headers.forEach { (name, value) -> put(name.lowercase(), value) }
        }
        putJsonObject("requestContext") {
            put("stage", "\$default")
            putJsonObject("http") {
                put("method", method)
                put("sourceIp", "127.0.0.1")
            }
        }
        put("body", Base64.encode(body))
        put("isBase64Encoded", true)
    }

    /** Shorthands for common priming requests. */
    public companion object {
        /** A `GET` request for [uri]. */
        public fun get(uri: String): PrimingRequest = PrimingRequest("GET", uri)

        /** A `POST` request for [uri] with a JSON [body]. */
        public fun postJson(uri: String, body: String): PrimingRequest =
            PrimingRequest("POST", uri, listOf("Content-Type" to "application/json"), body.encodeToByteArray())
    }
}
