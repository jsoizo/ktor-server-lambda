package com.jsoizo.ktor.server.lambda.events

import com.jsoizo.ktor.server.lambda.events.internal.boolean
import com.jsoizo.ktor.server.lambda.events.internal.decodeBody
import com.jsoizo.ktor.server.lambda.events.internal.encodeBody
import com.jsoizo.ktor.server.lambda.events.internal.firstValue
import com.jsoizo.ktor.server.lambda.events.internal.groupByName
import com.jsoizo.ktor.server.lambda.events.internal.has
import com.jsoizo.ktor.server.lambda.events.internal.multiValueMap
import com.jsoizo.ktor.server.lambda.events.internal.normalizePath
import com.jsoizo.ktor.server.lambda.events.internal.objOrNull
import com.jsoizo.ktor.server.lambda.events.internal.portFrom
import com.jsoizo.ktor.server.lambda.events.internal.requireString
import com.jsoizo.ktor.server.lambda.events.internal.sanitized
import com.jsoizo.ktor.server.lambda.events.internal.schemeFrom
import com.jsoizo.ktor.server.lambda.events.internal.singleValueMap
import com.jsoizo.ktor.server.lambda.events.internal.stringOrNull
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * API Gateway REST API proxy integration and HTTP API payload format 1.0.
 *
 * See [input format](https://docs.aws.amazon.com/apigateway/latest/developerguide/set-up-lambda-proxy-integrations.html#api-gateway-simple-proxy-for-lambda-input-format)
 * and [output format](https://docs.aws.amazon.com/apigateway/latest/developerguide/set-up-lambda-proxy-integrations.html#api-gateway-simple-proxy-for-lambda-output-format).
 */
public object ApiGatewayV1Codec : LambdaHttpCodec<Unit> {
    override val source: EventSource = EventSource.ApiGatewayV1

    override fun matches(event: JsonObject): Boolean = event.has("httpMethod") && event.has("resource")

    override fun decode(event: JsonObject, config: CodecConfig): Decoded<Unit> {
        val requestContext = event.objOrNull("requestContext")
        val headers = mergeHeaders(
            multi = event.multiValueMap("multiValueHeaders"),
            single = event.singleValueMap("headers"),
        ).sanitized()
        val query = event.multiValueMap("multiValueQueryStringParameters")
            ?: event.singleValueMap("queryStringParameters")
        val scheme = schemeFrom(headers)
        val request = LambdaHttpRequest(
            method = event.requireString("httpMethod"),
            scheme = scheme,
            host = headers.firstValue("Host") ?: requestContext?.stringOrNull("domainName"),
            port = portFrom(headers, scheme),
            // Payload v1 `path` never contains the stage.
            path = normalizePath(event.requireString("path"), stage = null, applyStage = false, basePath = config.stripBasePath),
            rawQuery = query.joinToString("&") { (k, v) -> "${k.encodeURLParameter()}=${v.encodeURLParameter()}" },
            headers = headers,
            body = decodeBody(event.stringOrNull("body"), event.boolean("isBase64Encoded")),
            remoteAddress = requestContext?.objOrNull("identity")?.stringOrNull("sourceIp"),
            source = source,
            isFunctionUrl = false,
            requestContext = requestContext,
            rawEvent = event,
        )
        return Decoded(request, Unit)
    }

    override fun encode(response: LambdaHttpResponse, state: Unit, config: CodecConfig): JsonObject {
        val body = encodeBody(response.body, config.binaryBodyPolicy.shouldEncode(response.headers, response.body))
        return buildJsonObject {
            put("statusCode", response.status)
            put(
                "multiValueHeaders",
                JsonObject(response.headers.groupByName().associate { (name, values) -> name to JsonArray(values.map(::JsonPrimitive)) }),
            )
            put("body", body.body)
            put("isBase64Encoded", body.isBase64Encoded)
        }
    }

    /** `multiValueHeaders` is authoritative; `headers` only fills in names it lacks. */
    private fun mergeHeaders(multi: List<Pair<String, String>>?, single: List<Pair<String, String>>): List<Pair<String, String>> {
        if (multi == null) return single
        val names = multi.mapTo(HashSet()) { it.first.lowercase() }
        return multi + single.filter { it.first.lowercase() !in names }
    }
}
