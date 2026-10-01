package com.jsoizo.ktor.server.lambda.runtime

import com.jsoizo.ktor.server.lambda.LambdaInvocation

/** An invocation returned by `GET /runtime/invocation/next`. */
public class NextInvocation(
    /** Headers of the invocation. */
    public val invocation: LambdaInvocation,
    /** The event payload as received. */
    public val event: ByteArray,
)

/**
 * An error to report to the Runtime API.
 *
 * See [Runtime API: invocation error](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html#runtimes-api-invokeerror).
 */
public class LambdaError(
    /** Error type, sent as `Lambda-Runtime-Function-Error-Type`, such as `Runtime.Timeout`. */
    public val errorType: String,
    /** Human-readable description. */
    public val errorMessage: String,
    /** Stack trace lines, if any. */
    public val stackTrace: List<String> = emptyList(),
)

/**
 * Converts an application failure into an error report. The type follows the `Category.Reason` form that
 * Lambda expects; other values are reported as `Runtime.Unknown`.
 */
internal fun Throwable.toFunctionError(): LambdaError =
    LambdaError("Function.${this::class.simpleName ?: "UnknownReason"}", message.orEmpty(), stackTraceToString().lines())

/** The Runtime API rejected a request. [fatal] means the runtime must exit, as the API requires for HTTP 500. */
public class RuntimeApiException(
    /** HTTP status the Runtime API answered with. */
    public val status: Int,
    /** `true` when the runtime must exit. */
    public val fatal: Boolean,
    message: String,
) : RuntimeException(message)

/**
 * Client for the [Lambda Runtime API](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html).
 *
 * An interface so a streaming-capable HTTP/1.1 client can replace the default one later.
 */
public interface LambdaRuntimeClient : AutoCloseable {
    /** Waits for the next invocation, however long that takes. */
    public suspend fun next(): NextInvocation

    /** Sends the response [body] for [invocation]. */
    public suspend fun respond(invocation: LambdaInvocation, body: ByteArray)

    /** Reports that [invocation] failed. */
    public suspend fun error(invocation: LambdaInvocation, error: LambdaError)

    /** Reports that initialization failed; the runtime should exit afterwards. */
    public suspend fun initError(error: LambdaError)
}
