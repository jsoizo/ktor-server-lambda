package com.jsoizo.ktor.server.lambda.handler

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.jsoizo.ktor.server.lambda.AwsLambdaHandler
import com.jsoizo.ktor.server.lambda.LambdaApplicationEngine
import com.jsoizo.ktor.server.lambda.LambdaInvocation
import com.jsoizo.ktor.server.lambda.events.LambdaHttpCodecs
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.crac.Core
import org.crac.Resource
import java.io.InputStream
import java.io.OutputStream

/**
 * Base class for running a Ktor application on the managed Java runtimes (`java21`, `java25`).
 *
 * The application is built in the constructor, so module initialization happens during the Lambda INIT
 * phase. Subclass it with a no-argument constructor and set the subclass as the function handler:
 *
 * ```kotlin
 * class Handler : KtorRequestStreamHandler({ module() })
 * ```
 *
 * Differences from the custom runtime engine: the managed runtime owns the invocation loop, so response
 * streaming is unavailable, and it enforces the function timeout itself instead of the engine cancelling
 * the call at the deadline. See [Java handlers](https://docs.aws.amazon.com/lambda/latest/dg/java-handler.html).
 *
 * @param module the Ktor application module
 * @param configure engine settings, such as [LambdaApplicationEngine.Configuration.stripBasePath]
 */
// Abstract so that users subclass it: the runtime instantiates the handler through a no-argument constructor.
@Suppress("AbstractClassCanBeConcreteClass")
public abstract class KtorRequestStreamHandler(
    module: Application.() -> Unit,
    configure: LambdaApplicationEngine.Configuration.() -> Unit = {},
) : RequestStreamHandler,
    Resource {
    private val server: EmbeddedServer<LambdaApplicationEngine, LambdaApplicationEngine.Configuration> =
        embeddedServer(AwsLambdaHandler, configure = configure, module = module).apply { start(wait = false) }

    /**
     * Requests sent through the pipeline before a SnapStart snapshot is taken. Empty disables priming.
     *
     * Priming runs application code, whose state then ends up in the snapshot shared by every restored
     * environment: open connections, random generators and pre-generated nonces among them.
     */
    protected open val primingRequests: List<PrimingRequest> get() = emptyList()

    init {
        // CRaC keeps resources weakly; the runtime holds the handler for the life of the environment.
        Core.getGlobalContext().register(this)
    }

    /**
     * @throws com.jsoizo.ktor.server.lambda.events.InvalidEventException if the input is not a JSON object
     * @throws com.jsoizo.ktor.server.lambda.events.UnsupportedEventException if the event is not an HTTP event
     */
    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        val event = LambdaHttpCodecs.parse(input.readBytes())
        val invocation = LambdaInvocation(
            requestId = context.awsRequestId,
            deadlineEpochMillis = System.currentTimeMillis() + context.remainingTimeInMillis,
            invokedFunctionArn = context.invokedFunctionArn,
            // Context is per invocation; the system property is shared by concurrent invocations on Managed Instances.
            traceId = context.xrayTraceId ?: System.getProperty("com.amazonaws.xray.traceHeader"),
        )
        val response = runBlocking { server.engine.handle(event, invocation) }
        output.write(response.toString().encodeToByteArray())
    }

    /** Sends [primingRequests], then calls [onBeforeCheckpoint]. Override that instead. */
    final override fun beforeCheckpoint(context: org.crac.Context<out Resource>) {
        prime()
        onBeforeCheckpoint(context)
    }

    /** Calls [onAfterRestore]. Override that instead. */
    final override fun afterRestore(context: org.crac.Context<out Resource>) {
        onAfterRestore(context)
    }

    /**
     * Runs after priming, before the snapshot. CRaC calls resources in reverse registration order, so a
     * resource registered later (for example one closing a connection pool) runs before priming does.
     */
    protected open fun onBeforeCheckpoint(context: org.crac.Context<out Resource>) {
        // No-op by default.
    }

    /** Runs after the snapshot is restored, for example to reopen connections. */
    protected open fun onAfterRestore(context: org.crac.Context<out Resource>) {
        // No-op by default.
    }

    // A failed priming request must not fail the deployment; it only means a colder start.
    @Suppress("TooGenericExceptionCaught")
    private fun prime() {
        val log = server.application.environment.log
        runBlocking {
            primingRequests.forEach { request ->
                try {
                    val response = server.engine.handle(request.toEvent(), LambdaInvocation(requestId = "snapstart-priming"))
                    val status = response["statusCode"]?.jsonPrimitive?.int ?: 0
                    if (status >= BAD_REQUEST) log.warn("Priming ${request.method} ${request.uri} answered $status")
                } catch (e: Exception) {
                    log.warn("Priming ${request.method} ${request.uri} failed", e)
                }
            }
        }
    }

    private companion object {
        const val BAD_REQUEST = 400
    }
}
