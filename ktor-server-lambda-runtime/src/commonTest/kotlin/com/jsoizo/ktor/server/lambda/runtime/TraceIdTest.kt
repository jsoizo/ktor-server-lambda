package com.jsoizo.ktor.server.lambda.runtime

import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class TraceIdTest {
    @Test
    fun singleWorkerPublishesTheTraceIdWhereTheXRaySdkReadsIt() = runBlocking {
        val api = FakeRuntimeApi()
        val endpoint = api.start()
        val server = embeddedServer(
            AwsLambda,
            configure = {
                concurrency = 1
                runtimeClient = { workers -> CioLambdaRuntimeClient(endpoint, maxConnections = workers * 2) }
            },
        ) {
            routing { get("/") { call.respondText(getenv("_X_AMZN_TRACE_ID").orEmpty()) } }
        }
        try {
            server.start(wait = false)
            api.enqueue(
                FakeRuntimeApi.Event(
                    "req-trace",
                    """{"version":"2.0","rawPath":"/","rawQueryString":"","headers":{"host":"h"},
                     "requestContext":{"stage":"${'$'}default","http":{"method":"GET","sourceIp":"1.2.3.4"}}}""",
                ),
            )
            val posted = withTimeout(10_000) { api.posted.receive() }
            assertEquals(true, posted.body.contains("Root=1-abc"), posted.body)
        } finally {
            server.stop()
            api.stop()
        }
    }
}
