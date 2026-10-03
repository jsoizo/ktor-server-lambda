package com.jsoizo.ktor.server.lambda.runtime

internal expect fun getenv(name: String): String?

/** Publishes the trace id in `_X_AMZN_TRACE_ID`, where the X-Ray SDKs look for it; `null` clears a stale one. */
internal expect fun publishTraceId(traceId: String?)

internal expect fun currentTimeMillis(): Long

internal expect fun exitProcess(status: Int): Nothing

/** `true` inside a Lambda execution environment, detected by `AWS_LAMBDA_RUNTIME_API`. */
public fun isRunningOnLambda(): Boolean = getenv("AWS_LAMBDA_RUNTIME_API") != null
