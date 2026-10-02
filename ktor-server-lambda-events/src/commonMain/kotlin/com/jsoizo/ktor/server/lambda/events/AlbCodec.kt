package com.jsoizo.ktor.server.lambda.events

import com.jsoizo.ktor.server.lambda.events.internal.boolean
import com.jsoizo.ktor.server.lambda.events.internal.decodeBody
import com.jsoizo.ktor.server.lambda.events.internal.encodeBody
import com.jsoizo.ktor.server.lambda.events.internal.firstValue
import com.jsoizo.ktor.server.lambda.events.internal.groupByName
import com.jsoizo.ktor.server.lambda.events.internal.has
import com.jsoizo.ktor.server.lambda.events.internal.lastValue
import com.jsoizo.ktor.server.lambda.events.internal.multiValueMap
import com.jsoizo.ktor.server.lambda.events.internal.normalizePath
import com.jsoizo.ktor.server.lambda.events.internal.objOrNull
import com.jsoizo.ktor.server.lambda.events.internal.portFrom
import com.jsoizo.ktor.server.lambda.events.internal.requireString
import com.jsoizo.ktor.server.lambda.events.internal.sanitized
import com.jsoizo.ktor.server.lambda.events.internal.schemeFrom
import com.jsoizo.ktor.server.lambda.events.internal.singleValueMap
import com.jsoizo.ktor.server.lambda.events.internal.splitForwardedFor
import com.jsoizo.ktor.server.lambda.events.internal.stringOrNull
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Per-request state for [AlbCodec]: ALB rejects a response whose header format differs from the request's. */
public class AlbState(
    /** `true` when the target group has `lambda.multi_value_headers.enabled`. */
    public val multiValueHeaders: Boolean,
)

/**
 * Application Load Balancer events, with and without multi-value headers.
 *
 * See [Lambda functions as targets](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/lambda-functions.html).
 */
public object AlbCodec : LambdaHttpCodec<AlbState> {
    override val source: EventSource = EventSource.Alb

    override fun matches(event: JsonObject): Boolean = event.objOrNull("requestContext")?.has("elb") == true

    override fun decode(event: JsonObject, config: CodecConfig): Decoded<AlbState> {
        val multiValue = event.has("multiValueHeaders")
        val headers = (if (multiValue) event.multiValueMap("multiValueHeaders").orEmpty() else event.singleValueMap("headers")).sanitized()
        val query = if (multiValue) {
            event.multiValueMap("multiValueQueryStringParameters").orEmpty()
        } else {
            event.singleValueMap("queryStringParameters")
        }
        val scheme = schemeFrom(headers)
        // ALB appends the peer address to the last X-Forwarded-For; earlier entries come from the client.
        val client = headers.lastValue("X-Forwarded-For")?.substringAfterLast(',')?.trim()?.let(::splitForwardedFor)
        val request = LambdaHttpRequest(
            method = event.requireString("httpMethod"),
            scheme = scheme,
            host = headers.firstValue("Host"),
            port = portFrom(headers, scheme),
            path = normalizePath(event.requireString("path"), stage = null, applyStage = false, basePath = config.stripBasePath),
            // ALB passes the query string undecoded; decoding and re-encoding it would double-encode.
            rawQuery = query.joinToString("&") { (k, v) -> "$k=$v" },
            headers = headers,
            body = decodeBody(event.stringOrNull("body"), event.boolean("isBase64Encoded")),
            remoteAddress = client?.first,
            remotePort = client?.second,
            source = source,
            isFunctionUrl = false,
            requestContext = event.objOrNull("requestContext"),
            rawEvent = event,
        )
        return Decoded(request, AlbState(multiValue))
    }

    override fun encode(response: LambdaHttpResponse, state: AlbState, config: CodecConfig): JsonObject {
        val body = encodeBody(response.body, config.binaryBodyPolicy.shouldEncode(response.headers, response.body))
        val grouped = response.headers.groupByName()
        return buildJsonObject {
            put("statusCode", response.status)
            put("statusDescription", statusDescription(response.status))
            if (state.multiValueHeaders) {
                put("multiValueHeaders", JsonObject(grouped.associate { (name, values) -> name to JsonArray(values.map(::JsonPrimitive)) }))
            } else {
                grouped.firstOrNull { (name, values) -> name.equals("Set-Cookie", ignoreCase = true) && values.size > 1 }?.let {
                    config.onWarning(
                        "ALB single-value header mode can return only one Set-Cookie; dropped ${it.second.size - 1}. " +
                            "Enable lambda.multi_value_headers.enabled on the target group.",
                    )
                }
                // Repeated fields are equivalent to one comma-separated field (RFC 9110 §5.3), except Set-Cookie.
                put(
                    "headers",
                    JsonObject(
                        grouped.associate { (name, values) ->
                            val value = if (name.equals("Set-Cookie", ignoreCase = true)) values.last() else values.joinToString(", ")
                            name to JsonPrimitive(value)
                        },
                    ),
                )
            }
            put("body", body.body)
            put("isBase64Encoded", body.isBase64Encoded)
        }
    }

    // ALB requires "<code> <reason>" and answers 502 to a malformed one, so a code Ktor does not know gets the
    // RFC 9110 name of its class rather than Ktor's "Unknown Status Code".
    private fun statusDescription(status: Int): String {
        val known = HttpStatusCode.allStatusCodes.firstOrNull { it.value == status }
        val reason = known?.description ?: when (status / 100) {
            1 -> "Informational"
            2 -> "Successful"
            3 -> "Redirection"
            4 -> "Client Error"
            5 -> "Server Error"
            else -> "Unknown"
        }
        return "$status $reason"
    }
}
