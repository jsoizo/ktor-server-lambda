package com.jsoizo.ktor.server.lambda.runtime

import com.jsoizo.ktor.server.lambda.ErrorMode
import com.jsoizo.ktor.server.lambda.lambda
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LambdaRuntimeEngineTest {
    private val api = FakeRuntimeApi()
    private var server: EmbeddedServer<LambdaRuntimeEngine, LambdaRuntimeEngine.Configuration>? = null
    private val arrivals = Channel<Unit>(Channel.UNLIMITED)
    private val gate = CompletableDeferred<Unit>()

    @AfterTest
    fun tearDown() = runBlocking {
        server?.stop()
        api.stop()
    }

    private suspend fun startEngine(concurrency: Int = 1, errorMode: ErrorMode = ErrorMode.HttpResponse) {
        val endpoint = api.start()
        val s = embeddedServer(
            AwsLambda,
            configure = {
                this.concurrency = concurrency
                this.errorMode = errorMode
                runtimeClient = { workers -> CioLambdaRuntimeClient(endpoint, maxConnections = workers * 2) }
            },
        ) {
            routing {
                get("/hello") { call.respondText("hello ${call.lambda.invocation.requestId}") }
                get("/slow") { delay(10_000) }
                get("/boom") { error("boom") }
                get("/held") {
                    arrivals.send(Unit)
                    gate.await()
                    call.respondText("released")
                }
                get("/parallel") {
                    arrivals.send(Unit)
                    gate.await()
                    call.respondText("ok")
                }
            }
        }
        s.start(wait = false)
        server = s
    }

    @Test
    fun servesInvocationAndPostsResponseWithInvocationId() = runBlocking {
        startEngine()
        api.enqueue(FakeRuntimeApi.Event("req-1", httpEvent("/hello")))
        val posted = withTimeout(10_000) { api.posted.receive() }
        assertEquals("response", posted.kind)
        assertEquals("inv-req-1", posted.invocationId)
        val json = Json.parseToJsonElement(posted.body).jsonObject
        assertEquals("200", json["statusCode"]!!.jsonPrimitive.content)
        assertEquals("hello req-1", json["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun reportsUnsupportedAndInvalidEventsAsErrors() = runBlocking {
        startEngine()
        api.enqueue(FakeRuntimeApi.Event("req-sqs", """{"Records":[{"eventSource":"aws:sqs"}]}"""))
        api.enqueue(FakeRuntimeApi.Event("req-bad", "not json"))
        val first = withTimeout(10_000) { api.posted.receive() }
        val second = withTimeout(10_000) { api.posted.receive() }
        assertEquals("error" to "Runtime.UnsupportedEvent", first.kind to first.errorType)
        assertEquals("error" to "Runtime.InvalidEvent", second.kind to second.errorType)
    }

    @Test
    fun cancelsInvocationAtDeadline() = runBlocking {
        startEngine()
        api.enqueue(FakeRuntimeApi.Event("req-slow", httpEvent("/slow"), deadlineEpochMillis = currentTimeMillis() + 300))
        val posted = withTimeout(5_000) { api.posted.receive() }
        assertEquals("error" to "Runtime.Timeout", posted.kind to posted.errorType)
    }

    @Test
    fun workersServeInvocationsConcurrently() = runBlocking {
        startEngine(concurrency = 2)
        api.enqueue(FakeRuntimeApi.Event("req-a", httpEvent("/parallel")))
        api.enqueue(FakeRuntimeApi.Event("req-b", httpEvent("/parallel")))
        // Both handlers must be in flight at once before either may finish; one worker would never get there.
        withTimeout(10_000) { repeat(2) { arrivals.receive() } }
        gate.complete(Unit)
        val results = withTimeout(10_000) { listOf(api.posted.receive(), api.posted.receive()) }
        assertEquals(setOf("req-a", "req-b"), results.map { it.requestId }.toSet())
        assertTrue(results.all { it.kind == "response" })
    }

    @Test
    fun reportsApplicationExceptionAsFunctionErrorInLambdaErrorMode() = runBlocking {
        startEngine(errorMode = ErrorMode.LambdaError)
        api.enqueue(FakeRuntimeApi.Event("req-boom", httpEvent("/boom")))
        val posted = withTimeout(10_000) { api.posted.receive() }
        // Lambda only accepts the Category.Reason form; anything else is reported as Runtime.Unknown.
        assertEquals("error" to "Function.IllegalStateException", posted.kind to posted.errorType)
    }

    @Test
    fun stopLetsInFlightInvocationSendItsResult() = runBlocking {
        startEngine()
        api.enqueue(FakeRuntimeApi.Event("req-held", httpEvent("/held")))
        withTimeout(10_000) { arrivals.receive() }
        val stopping = launch(Dispatchers.IO) { server!!.stop(gracePeriodMillis = 5_000, timeoutMillis = 6_000) }
        delay(200)
        gate.complete(Unit)
        val posted = withTimeout(10_000) { api.posted.receive() }
        stopping.join()
        server = null
        assertEquals("response", posted.kind)
    }

    @Test
    fun nextSurvivesIdleLongerThanCioDefaultRequestTimeout() = runBlocking {
        // CIO aborts requests after 15 s by default; GET /next must be able to wait longer.
        startEngine()
        api.enqueue(FakeRuntimeApi.Event("req-idle", httpEvent("/hello"), nextDelayMillis = 16_000))
        val posted = withTimeout(30_000) { api.posted.receive() }
        assertEquals("response", posted.kind)
    }

    private fun httpEvent(path: String) = """
        {"version":"2.0","rawPath":"$path","rawQueryString":"","headers":{"host":"h"},
         "requestContext":{"stage":"${'$'}default","http":{"method":"GET","sourceIp":"1.2.3.4"}},"isBase64Encoded":false}
    """.trimIndent()
}
