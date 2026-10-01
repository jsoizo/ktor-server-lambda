package com.jsoizo.ktor.server.lambda.runtime

import com.jsoizo.ktor.server.lambda.LambdaApplicationEngine
import com.jsoizo.ktor.server.lambda.LambdaInvocation
import com.jsoizo.ktor.server.lambda.events.InvalidEventException
import com.jsoizo.ktor.server.lambda.events.LambdaHttpCodecs
import com.jsoizo.ktor.server.lambda.events.UnsupportedEventException
import io.ktor.events.Events
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.engine.ApplicationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/**
 * Polls the [Lambda Runtime API](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html) with one or more
 * workers and serves each invocation through [handle].
 */
public class LambdaRuntimeEngine(
    environment: ApplicationEnvironment,
    monitor: Events,
    developmentMode: Boolean,
    configuration: Configuration,
    applicationProvider: () -> Application,
) : LambdaApplicationEngine(environment, monitor, developmentMode, configuration, applicationProvider) {

    /** Settings for [LambdaRuntimeEngine]. */
    public class Configuration : LambdaApplicationEngine.Configuration() {
        /** Number of concurrent workers. Defaults to `AWS_LAMBDA_MAX_CONCURRENCY`, or 1 when unset. */
        public var concurrency: Int? = null

        /**
         * Creates the Runtime API client for the given number of workers; replaceable to talk to an emulator
         * or a custom endpoint. The client must allow more connections than workers, because every idle
         * worker holds one in `GET /next` while busy workers send their results.
         */
        public var runtimeClient: (workers: Int) -> LambdaRuntimeClient = { workers ->
            CioLambdaRuntimeClient(maxConnections = workers * 2)
        }
    }

    private val runtimeConfiguration = configuration

    // Handlers may block (JDBC and the like), as they may on Ktor's CIO server, which also runs them on IO.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busyWorkers = MutableStateFlow(0)

    @Volatile
    private var loop: Job? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var exiting = false

    override fun start(wait: Boolean): ApplicationEngine {
        super.start(wait)
        val job = loop ?: launchWorkers().also { loop = it }
        if (wait) runBlocking { job.join() }
        return this
    }

    private fun launchWorkers(): Job {
        val workers = runtimeConfiguration.concurrency
            ?: getenv("AWS_LAMBDA_MAX_CONCURRENCY")?.toIntOrNull()
            ?: 1
        require(workers >= 1) { "concurrency must be at least 1, was $workers" }
        val client = runtimeConfiguration.runtimeClient(workers)
        val job = scope.launch {
            repeat(workers) { launch { work(client, publishTrace = workers == 1) } }
        }
        job.invokeOnCompletion { client.close() }
        return job
    }

    /** Stops polling, lets in-flight invocations send their result for up to [gracePeriodMillis], then cancels them. */
    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
        // fatal() exits from inside a worker, and exit runs the shutdown hook that calls stop();
        // joining that worker here would deadlock the process instead of letting it exit.
        if (exiting) return
        val job = loop ?: return
        stopping = true
        runBlocking {
            withTimeoutOrNull(gracePeriodMillis) { busyWorkers.first { it == 0 } }
            job.cancel()
            withTimeoutOrNull((timeoutMillis - gracePeriodMillis).coerceAtLeast(0)) { job.join() }
        }
    }

    // Any failure to reach the Runtime API is fatal, whatever its type.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun work(client: LambdaRuntimeClient, publishTrace: Boolean) {
        while (!stopping) {
            val next = try {
                client.next()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Closing the client during stop() can surface as an I/O error rather than a cancellation.
                currentCoroutineContext().ensureActive()
                if (stopping) return
                fatal("Lambda Runtime API is unreachable", e)
            }
            busyWorkers.update { it + 1 }
            try {
                send(client, next.invocation, serve(next, publishTrace))
            } finally {
                busyWorkers.update { it - 1 }
            }
        }
    }

    // Every application failure must become an /error report so the worker keeps serving.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun serve(next: NextInvocation, publishTrace: Boolean): Outcome {
        val invocation = next.invocation
        // A process-wide variable cannot hold per-invocation values once invocations run concurrently.
        if (publishTrace) publishTraceId(invocation.traceId)
        return try {
            val response = withDeadline(invocation) { handle(LambdaHttpCodecs.parse(next.event), invocation) }
            Outcome.Response(response.toString().encodeToByteArray())
        } catch (e: TimeoutCancellationException) {
            Outcome.Failure(LambdaError("Runtime.Timeout", "Invocation exceeded its deadline: ${e.message}"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsupportedEventException) {
            Outcome.Failure(LambdaError("Runtime.UnsupportedEvent", e.message.orEmpty()))
        } catch (e: InvalidEventException) {
            Outcome.Failure(LambdaError("Runtime.InvalidEvent", e.message.orEmpty()))
        } catch (e: Throwable) {
            Outcome.Failure(e.toFunctionError())
        }
    }

    // A worker that cannot deliver a result must not silently stop; the environment is broken, so exit.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun send(client: LambdaRuntimeClient, invocation: LambdaInvocation, outcome: Outcome) {
        try {
            when (outcome) {
                is Outcome.Response -> client.respond(invocation, outcome.body)
                is Outcome.Failure -> client.error(invocation, outcome.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeApiException) {
            if (e.fatal) fatal("Lambda Runtime API rejected the result", e)
            environment.log.error("Lambda Runtime API rejected the result for ${invocation.requestId}", e)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            fatal("Lambda Runtime API is unreachable", e)
        }
    }

    private suspend fun <T> withDeadline(invocation: LambdaInvocation, block: suspend CoroutineScope.() -> T): T {
        val deadline = invocation.deadlineEpochMillis ?: return kotlinx.coroutines.coroutineScope(block)
        // Lambda Managed Instances keep running timed-out invocations, so cancel them ourselves.
        return withTimeout((deadline - currentTimeMillis()).coerceAtLeast(1), block)
    }

    private fun fatal(message: String, cause: Throwable): Nothing {
        exiting = true
        environment.log.error(message, cause)
        exitProcess(1)
    }

    private sealed interface Outcome {
        class Response(val body: ByteArray) : Outcome

        class Failure(val error: LambdaError) : Outcome
    }
}
