package com.jsoizo.ktor.server.lambda

import com.jsoizo.ktor.server.lambda.codec.LambdaHttpRequest
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.JsonObject

/** Lambda-specific information about the current call, available through [ApplicationCall.lambda]. */
public class LambdaCallContext internal constructor(
    /** The Runtime API invocation this call serves. */
    public val invocation: LambdaInvocation,
    private val request: LambdaHttpRequest,
) {
    /** The service that sent the event. */
    public val source: EventSource get() = request.source

    /** `true` when the call came through a Lambda Function URL. */
    public val isFunctionUrl: Boolean get() = request.isFunctionUrl

    /** The event's `requestContext`, for values such as authorizer claims. */
    public val requestContext: JsonObject? get() = request.requestContext

    /** The event exactly as received. */
    public val rawEvent: JsonObject get() = request.rawEvent
}

internal val LambdaCallContextKey: AttributeKey<LambdaCallContext> = AttributeKey("LambdaCallContext")

/** `null` when the call is not served by the AWS Lambda engine. */
public val ApplicationCall.lambdaOrNull: LambdaCallContext? get() = attributes.getOrNull(LambdaCallContextKey)

/**
 * Lambda-specific information about this call.
 *
 * @throws IllegalStateException when the call is not served by the AWS Lambda engine; use [lambdaOrNull] in code that also runs elsewhere
 */
public val ApplicationCall.lambda: LambdaCallContext
    get() = lambdaOrNull ?: error("This call is not running on the AWS Lambda engine")
