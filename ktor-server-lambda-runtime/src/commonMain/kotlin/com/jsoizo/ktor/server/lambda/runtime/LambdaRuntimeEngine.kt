package com.jsoizo.ktor.server.lambda.runtime

import com.jsoizo.ktor.server.lambda.LambdaApplicationEngine
import com.jsoizo.ktor.server.lambda.LambdaInvocation
import com.jsoizo.ktor.server.lambda.events.InvalidEventException
import com.jsoizo.ktor.server.lambda.events.LambdaHttpCodecs
import com.jsoizo.ktor.server.lambda.events.UnsupportedEventException
import io.ktor.events.Events
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.ApplicationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
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
    private val pendingPolls = MutableStateFlow(emptySet<Job>())
    private var publishedTraceId: String? = null

    @Volatile
    private var loop: Job? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var exiting = false

    init {
        // Kotlin/Native's EmbeddedServer.stop() destroys the application before stopping the engine, so in-flight
        // invocations are drained here, ahead of the application's own ApplicationStopping subscribers.
        monitor.subscribe(ApplicationStopping) { drain(configuration.shutdownGracePeriod) }
    }

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

    /**
     * Stops polling, lets in-flight invocations send their result for up to [gracePeriodMillis], then cancels them.
     * `ApplicationEngine.stop()` without arguments uses Ktor's defaults (500 ms each), which leave no time to wait
     * for the cancellation to complete; `EmbeddedServer.stop()` allows more.
     */
    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
        // fatal() exits from inside a worker, and exit runs the shutdown hook that calls stop();
        // joining that worker here would deadlock the process instead of letting it exit.
        if (exiting) return
        val job = loop ?: return
        drain(gracePeriodMillis)
        job.cancel()
        runBlocking { withTimeoutOrNull((timeoutMillis - gracePeriodMillis).coerceAtLeast(0)) { job.join() } }
    }

    private fun drain(gracePeriodMillis: Long) {
        if (exiting) return
        stopping = true
        // Idle workers would otherwise accept a new invocation during the grace period and lose it on cancel.
        pendingPolls.value.forEach { it.cancel() }
        runBlocking { withTimeoutOrNull(gracePeriodMillis) { busyWorkers.first { it == 0 } } }
    }

    // Any failure to reach the Runtime API is fatal, whatever its type.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun work(client: LambdaRuntimeClient, publishTrace: Boolean) {
        while (!stopping) {
            val poll = scope.async { client.next() }
            pendingPolls.update { it + poll }
            val next = try {
                poll.await()
            } catch (e: CancellationException) {
                if (stopping) return
                throw e
            } catch (e: Throwable) {
                // Closing the client during stop() can surface as an I/O error rather than a cancellation.
                currentCoroutineContext().ensureActive()
                if (stopping) return
                fatal("Lambda Runtime API is unreachable", e)
            } finally {
                pendingPolls.update { it - poll }
            }
            busyWorkers.update { it + 1 }
            try {
                serve(client, next, publishTrace)
            } finally {
                busyWorkers.update { it - 1 }
            }
        }
    }

    private suspend fun serve(client: LambdaRuntimeClient, next: NextInvocation, publishTrace: Boolean) {
        val invocation = next.invocation
        // A process-wide variable cannot hold per-invocation values once invocations run concurrently.
        if (publishTrace) publishTrace(invocation.traceId)
        // Detached from the worker so that a handler blocking past the deadline cannot hold back the report.
        val work = scope.async { outcomeOf { handle(LambdaHttpCodecs.parse(next.event), invocation) } }
        val outcome = try {
            awaitWithin(invocation, work)
        } catch (e: CancellationException) {
            work.cancel()
            throw e
        }
        if (outcome == null) {
            // Lambda Managed Instances keep running timed-out invocations, so report and cancel them ourselves.
            work.cancel()
            send(client, invocation, Outcome.Failure(LambdaError("Runtime.Timeout", "Invocation exceeded its deadline")))
            // Taking another invocation while this one still runs would exceed the configured concurrency.
            work.join()
        } else {
            send(client, invocation, outcome)
        }
    }

    private suspend fun awaitWithin(invocation: LambdaInvocation, work: Deferred<Outcome>): Outcome? {
        val deadline = invocation.deadlineEpochMillis ?: return work.await()
        return withTimeoutOrNull((deadline - currentTimeMillis()).coerceAtLeast(1)) { work.await() }
    }

    // Every application failure must become an /error report so the worker keeps serving.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun outcomeOf(block: suspend () -> JsonObject): Outcome = try {
        Outcome.Response(block().toString().encodeToByteArray())
    } catch (e: UnsupportedEventException) {
        Outcome.Failure(LambdaError("Runtime.UnsupportedEvent", e.message.orEmpty()))
    } catch (e: InvalidEventException) {
        Outcome.Failure(LambdaError("Runtime.InvalidEvent", e.message.orEmpty()))
    } catch (e: Throwable) {
        // A cancellation of this coroutine propagates; one thrown by the application is just a failure.
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        Outcome.Failure(e.toFunctionError())
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
            // A rejected response (413 for one over 6 MB, for example) would otherwise leave the invocation open.
            if (outcome is Outcome.Response) {
                send(client, invocation, Outcome.Failure(LambdaError("Runtime.ResponseRejected", e.message.orEmpty())))
            }
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            fatal("Lambda Runtime API is unreachable", e)
        }
    }

    private fun publishTrace(traceId: String?) {
        if (traceId == publishedTraceId) return
        publishedTraceId = traceId
        publishTraceId(traceId)
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
