package com.jsoizo.ktor.server.lambda.runtime

internal actual fun getenv(name: String): String? = System.getenv(name)

// The JVM cannot set environment variables; the AWS X-Ray SDK for Java reads this property instead.
internal actual fun publishTraceId(traceId: String?) {
    if (traceId == null) System.clearProperty(TRACE_PROPERTY) else System.setProperty(TRACE_PROPERTY, traceId)
}

private const val TRACE_PROPERTY = "com.amazonaws.xray.traceHeader"

internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()

internal actual fun exitProcess(status: Int): Nothing = kotlin.system.exitProcess(status)
