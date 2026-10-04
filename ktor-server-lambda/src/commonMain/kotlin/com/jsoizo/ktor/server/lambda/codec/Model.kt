package com.jsoizo.ktor.server.lambda.codec

import com.jsoizo.ktor.server.lambda.EventSource
import kotlinx.serialization.json.JsonObject

/**
 * An HTTP request decoded from a Lambda event, with the differences between event formats removed.
 *
 * Cookies always arrive in the `Cookie` header, even when the event carried them separately
 * (payload v2 `cookies`).
 */
// One parameter per field the event formats carry; grouping them would only add types.
@Suppress("LongParameterList")
internal class LambdaHttpRequest(
    /** HTTP method, such as `GET`. */
    val method: String,
    /** `https` unless `X-Forwarded-Proto` says otherwise. */
    val scheme: String,
    /** From the `Host` header, falling back to `requestContext.domainName`. */
    val host: String?,
    /** From `X-Forwarded-Port`, falling back to the default port of [scheme]. */
    val port: Int?,
    /** Path after stripping the stage and base path, percent-encoded. */
    val path: String,
    /** Query string without the leading `?`, percent-encoded. */
    val rawQuery: String,
    /** Header fields in arrival order; names are case-insensitive and each entry holds one value. */
    val headers: List<Pair<String, String>>,
    /** Request body, already base64-decoded. Empty when the event has no body. */
    val body: ByteArray,
    /** Client address as reported by the event source, if any. */
    val remoteAddress: String?,
    /** Client port, when the event source reports it (ALB with `routing.http.xff_client_port.enabled`). */
    val remotePort: Int? = null,
    /** The service that sent the event. */
    val source: EventSource,
    /** `true` when a payload v2 event came through a Lambda Function URL rather than API Gateway. */
    val isFunctionUrl: Boolean,
    /** The event's `requestContext`, for values such as authorizer claims that have no HTTP equivalent. */
    val requestContext: JsonObject?,
    /** The event exactly as received. */
    val rawEvent: JsonObject,
)

/** An HTTP response to encode into the format the event source expects. */
internal class LambdaHttpResponse(
    /** HTTP status code. */
    val status: Int,
    /** Header fields in order; each `Set-Cookie` is its own entry. */
    val headers: List<Pair<String, String>>,
    /** Response body as raw bytes; the codec decides whether it must be base64-encoded. */
    val body: ByteArray,
)
