package com.jsoizo.ktor.server.lambda.events

import com.jsoizo.ktor.server.lambda.events.internal.boolean
import com.jsoizo.ktor.server.lambda.events.internal.decodeBody
import com.jsoizo.ktor.server.lambda.events.internal.encodeBody
import com.jsoizo.ktor.server.lambda.events.internal.firstValue
import com.jsoizo.ktor.server.lambda.events.internal.groupByName
import com.jsoizo.ktor.server.lambda.events.internal.normalizePath
import com.jsoizo.ktor.server.lambda.events.internal.objOrNull
import com.jsoizo.ktor.server.lambda.events.internal.partitionSetCookie
import com.jsoizo.ktor.server.lambda.events.internal.portFrom
import com.jsoizo.ktor.server.lambda.events.internal.requireString
import com.jsoizo.ktor.server.lambda.events.internal.sanitized
import com.jsoizo.ktor.server.lambda.events.internal.schemeFrom
import com.jsoizo.ktor.server.lambda.events.internal.singleValueMap
import com.jsoizo.ktor.server.lambda.events.internal.stringList
import com.jsoizo.ktor.server.lambda.events.internal.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * API Gateway HTTP API payload format 2.0 and Lambda Function URLs.
 *
 * See [HTTP API payload format](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html)
 * and [Function URL payloads](https://docs.aws.amazon.com/lambda/latest/dg/urls-invocation.html).
 */
public object ApiGatewayV2Codec : LambdaHttpCodec<Unit> {
    override val source: EventSource = EventSource.ApiGatewayV2

    private val functionUrlDomain = Regex("""^[^.]+\.lambda-url\.[^.]+\.on\.aws$""")

    override fun matches(event: JsonObject): Boolean =
        event.stringOrNull("version") == "2.0" && event.objOrNull("requestContext")?.objOrNull("http") != null

    override fun decode(event: JsonObject, config: CodecConfig): Decoded<Unit> {
        val requestContext = event.objOrNull("requestContext")
            ?: throw InvalidEventException("Missing requestContext")
        val http = requestContext.objOrNull("http") ?: throw InvalidEventException("Missing requestContext.http")
        val cookies = event.stringList("cookies")
        val headers = buildList {
            // API Gateway moves Cookie into `cookies`; emulators may send both, which would duplicate it.
            val fromHeaders = event.singleValueMap("headers")
            addAll(if (cookies.isEmpty()) fromHeaders else fromHeaders.filterNot { it.first.equals("cookie", ignoreCase = true) })
            if (cookies.isNotEmpty()) add("cookie" to cookies.joinToString("; "))
        }.sanitized()
        val scheme = schemeFrom(headers)
        val domainName = requestContext.stringOrNull("domainName")
        val request = LambdaHttpRequest(
            method = http.requireString("method"),
            scheme = scheme,
            host = headers.firstValue("Host") ?: domainName,
            port = portFrom(headers, scheme),
            path = normalizePath(
                path = event.requireString("rawPath"),
                stage = requestContext.stringOrNull("stage"),
                // Custom domains map the stage away, so only execute-api hosts carry it in rawPath.
                applyStage = config.stripStage && domainName?.contains(".execute-api.") == true,
                basePath = config.stripBasePath,
            ),
            // The Function URL documentation shows rawQueryString with a leading '?' in one place.
            rawQuery = event.stringOrNull("rawQueryString").orEmpty().removePrefix("?"),
            headers = headers,
            body = decodeBody(event.stringOrNull("body"), event.boolean("isBase64Encoded")),
            remoteAddress = http.stringOrNull("sourceIp"),
            source = source,
            isFunctionUrl = domainName != null && functionUrlDomain.matches(domainName),
            requestContext = requestContext,
            rawEvent = event,
        )
        return Decoded(request, Unit)
    }

    override fun encode(response: LambdaHttpResponse, state: Unit, config: CodecConfig): JsonObject {
        val (setCookies, others) = response.headers.partitionSetCookie()
        val body = encodeBody(response.body, config.binaryBodyPolicy.shouldEncode(response.headers, response.body))
        return buildJsonObject {
            // Without statusCode, API Gateway treats the whole output as the body.
            put("statusCode", response.status)
            put(
                "headers",
                JsonObject(others.groupByName().associate { (name, values) -> name to JsonPrimitive(values.joinToString(",")) }),
            )
            if (setCookies.isNotEmpty()) put("cookies", JsonArray(setCookies.map(::JsonPrimitive)))
            put("body", body.body)
            put("isBase64Encoded", body.isBase64Encoded)
        }
    }
}
