package com.jsoizo.ktor.server.lambda.runtime

internal expect fun getenv(name: String): String?

/** Publishes the trace id where the X-Ray SDK of the platform looks for it; `null` clears a stale one. */
internal expect fun publishTraceId(traceId: String?)

internal expect fun currentTimeMillis(): Long

internal expect fun exitProcess(status: Int): Nothing

/** `true` inside a Lambda execution environment, detected by `AWS_LAMBDA_RUNTIME_API`. */
public fun isRunningOnLambda(): Boolean = getenv("AWS_LAMBDA_RUNTIME_API") != null
