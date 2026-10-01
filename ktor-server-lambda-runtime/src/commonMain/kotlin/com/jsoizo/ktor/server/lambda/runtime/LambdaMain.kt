package com.jsoizo.ktor.server.lambda.runtime

import io.ktor.server.engine.EmbeddedServer
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.runBlocking

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
        runCatching {
            CioLambdaRuntimeClient().use { client ->
                runBlocking {
                    client.initError(e.toFunctionError())
                }
            }
        }
        KtorSimpleLogger("com.jsoizo.ktor.server.lambda.runtime").error("Failed to initialize the application", e)
        exitProcess(1)
    }
}
