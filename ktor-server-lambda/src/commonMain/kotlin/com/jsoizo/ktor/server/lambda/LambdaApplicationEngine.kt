package com.jsoizo.ktor.server.lambda

import com.jsoizo.ktor.server.lambda.events.BinaryBodyPolicy
import com.jsoizo.ktor.server.lambda.events.CodecConfig
import com.jsoizo.ktor.server.lambda.events.LambdaHttpCodecs
import com.jsoizo.ktor.server.lambda.events.LambdaHttpRequest
import com.jsoizo.ktor.server.lambda.events.LambdaHttpResponse
import io.ktor.events.Events
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.BaseApplicationEngine
import io.ktor.server.engine.EngineConnectorConfig
import io.ktor.server.engine.EnginePipeline
import io.ktor.server.engine.defaultEnginePipeline
import io.ktor.server.engine.defaultExceptionStatusCode
import io.ktor.server.engine.handleFailure
import io.ktor.server.engine.logError
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.execute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Socketless engine that runs the Ktor pipeline once per Lambda invocation via [handle]. */
public open class LambdaApplicationEngine(
    environment: ApplicationEnvironment,
    monitor: Events,
    developmentMode: Boolean,
    /** Settings the engine was created with. */
    public val configuration: Configuration,
    private val applicationProvider: () -> Application,
) : BaseApplicationEngine(
    environment,
    monitor,
    developmentMode,
    enginePipeline(environment, developmentMode, configuration),
) {

    /** Settings for [LambdaApplicationEngine]; connector settings inherited from Ktor are ignored. */
    public open class Configuration : ApplicationEngine.Configuration() {
        /** Strip the stage segment that payload v2 puts at the start of `rawPath` on execute-api hosts. */
        public var stripStage: Boolean = true

        /** Path prefix to strip, such as a custom domain base path mapping. */
        public var stripBasePath: String? = null

        /** Decides whether response bodies are sent base64-encoded. */
        public var binaryBodyPolicy: BinaryBodyPolicy = BinaryBodyPolicy.Default

        /** What happens when the application throws. */
        public var errorMode: ErrorMode = ErrorMode.HttpResponse
    }

    private val codecConfig: CodecConfig by lazy {
        CodecConfig(
            stripStage = configuration.stripStage,
            stripBasePath = configuration.stripBasePath,
            binaryBodyPolicy = configuration.binaryBodyPolicy,
            onWarning = { environment.log.warn(it) },
        )
    }

    override fun start(wait: Boolean): ApplicationEngine {
        resolvedConnectorsDeferred.complete(emptyList<EngineConnectorConfig>())
        return this
    }

    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
        // Nothing to release: no sockets, and each invocation completes inside handle().
    }

    /**
     * Decodes a Lambda HTTP event, runs the pipeline and returns the response in the event's format.
     *
     * @throws com.jsoizo.ktor.server.lambda.events.UnsupportedEventException if the event format is not supported
     * @throws com.jsoizo.ktor.server.lambda.events.InvalidEventException if the event is malformed
     */
    public suspend fun handle(event: JsonObject, invocation: LambdaInvocation): JsonObject {
        val decoded = LambdaHttpCodecs.decode(event, codecConfig)
        return decoded.encode(handle(decoded.request, invocation))
    }

    /**
     * Runs the pipeline for an already decoded request; use this with a codec of your own.
     *
     * Coroutines launched in the call's scope (`call.launch { }`) never delay the response, and their failures do
     * not affect it. They are cancelled once the response is built, because Lambda may freeze the environment as
     * soon as it is sent; one that ignores cancellation keeps running in the background.
     *
     * When the caller is cancelled, this function cancels the pipeline and waits for it to return before
     * rethrowing, so a caller that stops waiting for the response can also tell when the handler has let go.
     * A timeout around this call therefore cannot cut short a handler that blocks or ignores cancellation;
     * report the timeout from a separate coroutine instead, as the custom runtime does.
     *
     * @throws Throwable the application's exception when [Configuration.errorMode] is [ErrorMode.LambdaError], or
     * in any mode when the response failed after its status and headers were committed, which a socket-based engine
     * would surface by dropping the connection
     */
    public suspend fun handle(request: LambdaHttpRequest, invocation: LambdaInvocation): LambdaHttpResponse {
        val caller = currentCoroutineContext()
        // Detached from the caller so that waiting for the response never waits for the call's coroutines;
        // the caller's cancellation is still forwarded.
        val callJob = SupervisorJob()
        val forwardCancellation = caller[Job]?.invokeOnCompletion { callJob.cancel() }
        val callContext = caller.minusKey(Job) + callJob + invocation + CoroutineExceptionHandler { _, error ->
            // Kotlin/Native terminates the process on an unhandled coroutine exception.
            environment.log.error("Unhandled exception in a coroutine launched by a call", error)
        }
        try {
            val call = LambdaApplicationCall(applicationProvider(), request, invocation, callContext)
            val executed = CompletableDeferred<Unit>()
            // Routing gives handlers a call scoped to the coroutine running the pipeline, so `call.launch` children
            // belong to it. supervisorScope keeps their failures away from the pipeline, and completing `executed`
            // inside it lets the response go out before they finish.
            CoroutineScope(callContext).launch {
                supervisorScope { executed.completeWith(runCatching { pipeline.execute(call) }) }
            }.invokeOnCompletion { cause ->
                // A coroutine cancelled before it is dispatched never runs its body, which would leave `executed` open.
                if (cause != null) executed.completeExceptionally(cause)
            }
            awaitPipeline(executed, callJob)
            call.attributes.getOrNull(ApplicationErrorKey)?.let { throw it }
            call.response.failureAfterCommit?.let { throw it }
            return call.response.toLambdaResponse()
        } finally {
            forwardCancellation?.dispose()
            callJob.cancel()
        }
    }

    private suspend fun awaitPipeline(executed: CompletableDeferred<Unit>, callJob: Job) {
        try {
            executed.await()
        } catch (e: CancellationException) {
            callJob.cancel()
            // A handler blocking past cancellation still holds its thread; the caller needs to know when it is free.
            withContext(NonCancellable) { executed.join() }
            throw e
        }
    }

    private companion object {
        val ApplicationErrorKey = AttributeKey<Throwable>("LambdaApplicationError")

        // LambdaError mode must capture every application failure, whatever its type.
        @Suppress("TooGenericExceptionCaught")
        fun enginePipeline(environment: ApplicationEnvironment, developmentMode: Boolean, configuration: Configuration): EnginePipeline =
            when (configuration.errorMode) {
                ErrorMode.HttpResponse -> defaultEnginePipeline(environment.config, developmentMode)

                ErrorMode.LambdaError -> EnginePipeline(developmentMode).apply {
                    intercept(EnginePipeline.Call) {
                        // Record instead of throwing; the EnginePipeline.Before fallback would turn the exception into a 500.
                        try {
                            call.application.execute(call)
                        } catch (error: Throwable) {
                            if (defaultExceptionStatusCode(error) != null) {
                                // Client errors such as BadRequestException keep their 4xx response.
                                handleFailure(call, error)
                            } else {
                                logError(call, error)
                                // Rethrown as a CancellationException, it would read as the worker itself being cancelled.
                                val recorded = if (error is CancellationException) {
                                    IllegalStateException(
                                        "The call was cancelled",
                                        error,
                                    )
                                } else {
                                    error
                                }
                                call.attributes.put(ApplicationErrorKey, recorded)
                            }
                        }
                    }
                }
            }
    }
}
