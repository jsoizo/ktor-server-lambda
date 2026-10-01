package com.jsoizo.ktor.server.lambda

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-invocation data from the Runtime API.
 *
 * Kept in the coroutine context rather than process-wide state so invocations can run concurrently.
 * Code outside a route can read it with `coroutineContext[LambdaInvocation]`.
 *
 * See [Runtime API: next invocation](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html#runtimes-api-next).
 */
public class LambdaInvocation(
    /** `Lambda-Runtime-Aws-Request-Id`. */
    public val requestId: String,
    /** `Lambda-Runtime-Invocation-Id`, which identifies one attempt of the request. */
    public val invocationId: String? = null,
    /** `Lambda-Runtime-Deadline-Ms`: the time the invocation times out, in milliseconds since the epoch. */
    public val deadlineEpochMillis: Long? = null,
    /** `Lambda-Runtime-Invoked-Function-Arn`. */
    public val invokedFunctionArn: String? = null,
    /** `Lambda-Runtime-Trace-Id`, the X-Ray tracing header. */
    public val traceId: String? = null,
) : AbstractCoroutineContextElement(Key) {
    /** Key for looking the invocation up in a coroutine context. */
    public companion object Key : CoroutineContext.Key<LambdaInvocation>

    override fun toString(): String = "LambdaInvocation(requestId=$requestId)"
}
