package com.jsoizo.ktor.server.lambda.runtime

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.setenv
import platform.posix.timespec
import platform.posix.unsetenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun getenv(name: String): String? = platform.posix.getenv(name)?.toKString()

@OptIn(ExperimentalForeignApi::class)
internal actual fun publishTraceId(traceId: String?) {
    if (traceId == null) unsetenv(TRACE_VARIABLE) else setenv(TRACE_VARIABLE, traceId, 1)
}

private const val TRACE_VARIABLE = "_X_AMZN_TRACE_ID"

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentTimeMillis(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * MILLIS_PER_SECOND + ts.tv_nsec / NANOS_PER_MILLI
}

private const val MILLIS_PER_SECOND = 1_000L
private const val NANOS_PER_MILLI = 1_000_000L

internal actual fun exitProcess(status: Int): Nothing = kotlin.system.exitProcess(status)
