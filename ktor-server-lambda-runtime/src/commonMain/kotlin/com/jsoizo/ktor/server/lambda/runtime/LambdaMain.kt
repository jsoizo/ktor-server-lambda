package com.jsoizo.ktor.server.lambda.runtime

import io.ktor.server.engine.EmbeddedServer
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private val log = KtorSimpleLogger("com.jsoizo.ktor.server.lambda.runtime")

// Lambda allows 10 seconds for INIT; reporting must not use all of it when the Runtime API stops answering.
private const val INIT_ERROR_TIMEOUT_MILLIS = 3_000L

/**
 * Starts [server] and blocks. On Lambda, a failure while loading application modules is reported to
 * `/runtime/init/error` before exiting; `EmbeddedServer.start` runs modules before the engine starts,
 * so the engine itself never sees that failure.
 */
// Every failure, whatever its type, must reach /runtime/init/error or Lambda only sees a crashed process.
@Suppress("TooGenericExceptionCaught")
public fun lambdaMain(server: () -> EmbeddedServer<*, *>) {
    try {
        server().start(wait = true)
    } catch (e: Throwable) {
        if (!isRunningOnLambda()) throw e
        log.error("Failed to initialize the application", e)
        try {
            CioLambdaRuntimeClient().use { client ->
                runBlocking { withTimeout(INIT_ERROR_TIMEOUT_MILLIS) { client.initError(e.toFunctionError()) } }
            }
        } catch (reportFailure: Throwable) {
            log.error("Could not report the initialization failure to the Lambda Runtime API", reportFailure)
        }
        exitProcess(1)
    }
    // The invocation loop only returns once the engine is stopped; on Lambda that means the runtime is going away.
    if (isRunningOnLambda()) log.warn("The Lambda invocation loop has ended")
}
