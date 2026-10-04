package com.jsoizo.ktor.server.lambda.events

import kotlinx.serialization.json.JsonObject

/** The AWS service that invoked the function, which determines the event and response format. */
public enum class EventSource {
    /**
     * API Gateway REST API proxy integration, or HTTP API with payload format 1.0.
     *
     * See [Lambda proxy integration input format](https://docs.aws.amazon.com/apigateway/latest/developerguide/set-up-lambda-proxy-integrations.html#api-gateway-simple-proxy-for-lambda-input-format).
     */
    ApiGatewayV1,

    /**
     * API Gateway HTTP API with payload format 2.0, or a Lambda Function URL.
     *
     * See [HTTP API payload format](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html)
     * and [Function URL payloads](https://docs.aws.amazon.com/lambda/latest/dg/urls-invocation.html).
     */
    ApiGatewayV2,

    /**
     * Application Load Balancer with a Lambda target group.
     *
     * See [Lambda functions as targets](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/lambda-functions.html).
     */
    Alb,
}

/**
 * An HTTP request decoded from a Lambda event, with the differences between event formats removed.
 *
 * Cookies always arrive in the `Cookie` header, even when the event carried them separately
 * (payload v2 `cookies`).
 */
// One parameter per field the event formats carry; grouping them would only add types to the public API.
@Suppress("LongParameterList")
public class LambdaHttpRequest(
    /** HTTP method, such as `GET`. */
    public val method: String,
    /** `https` unless `X-Forwarded-Proto` says otherwise. */
    public val scheme: String,
    /** From the `Host` header, falling back to `requestContext.domainName`. */
    public val host: String?,
    /** From `X-Forwarded-Port`, falling back to the default port of [scheme]. */
    public val port: Int?,
    /** Path after stripping the stage and base path, percent-encoded. */
    public val path: String,
    /** Query string without the leading `?`, percent-encoded. */
    public val rawQuery: String,
    /** Header fields in arrival order; names are case-insensitive and each entry holds one value. */
    public val headers: List<Pair<String, String>>,
    /** Request body, already base64-decoded. Empty when the event has no body. */
    public val body: ByteArray,
    /** Client address as reported by the event source, if any. */
    public val remoteAddress: String?,
    /** Client port, when the event source reports it (ALB with `routing.http.xff_client_port.enabled`). */
    public val remotePort: Int? = null,
    /** The service that sent the event. */
    public val source: EventSource,
    /** `true` when a payload v2 event came through a Lambda Function URL rather than API Gateway. */
    public val isFunctionUrl: Boolean,
    /** The event's `requestContext`, for values such as authorizer claims that have no HTTP equivalent. */
    public val requestContext: JsonObject?,
    /** The event exactly as received. */
    public val rawEvent: JsonObject,
)

/** An HTTP response to encode into the format the event source expects. */
public class LambdaHttpResponse(
    /** HTTP status code. */
    public val status: Int,
    /** Header fields in order; each `Set-Cookie` is its own entry. */
    public val headers: List<Pair<String, String>>,
    /** Response body as raw bytes; the codec decides whether it must be base64-encoded. */
    public val body: ByteArray,
)
