package com.jsoizo.ktor.server.lambda.runtime

import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/** In-process stand-in for the Lambda Runtime API, speaking the same HTTP contract. */
class FakeRuntimeApi {
    class Event(val requestId: String, val body: String, val deadlineEpochMillis: Long = Long.MAX_VALUE / 2, val nextDelayMillis: Long = 0)

    class Posted(val requestId: String, val kind: String, val body: String, val errorType: String?, val invocationId: String?)

    private val events = Channel<Event>(Channel.UNLIMITED)
    val posted = Channel<Posted>(Channel.UNLIMITED)
    private lateinit var server: EmbeddedServer<*, *>

    suspend fun start(): String {
        server = embeddedServer(CIO, port = 0) {
            routing {
                get("/2018-06-01/runtime/invocation/next") {
                    val event = events.receive()
                    delay(event.nextDelayMillis)
                    call.response.header("Lambda-Runtime-Aws-Request-Id", event.requestId)
                    call.response.header("Lambda-Runtime-Invocation-Id", "inv-${event.requestId}")
                    call.response.header("Lambda-Runtime-Deadline-Ms", event.deadlineEpochMillis.toString())
                    call.response.header("Lambda-Runtime-Trace-Id", "Root=1-abc")
                    call.respondText(event.body)
                }
                post("/2018-06-01/runtime/invocation/{id}/{kind}") {
                    posted.send(
                        Posted(
                            requestId = call.parameters["id"]!!,
                            kind = call.parameters["kind"]!!,
                            body = call.receiveText(),
                            errorType = call.request.header("Lambda-Runtime-Function-Error-Type"),
                            invocationId = call.request.header("Lambda-Runtime-Invocation-Id"),
                        ),
                    )
                    call.respond(HttpStatusCode.Accepted)
                }
            }
        }
        server.startSuspend(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        return "127.0.0.1:$port"
    }

    fun enqueue(event: Event) {
        events.trySend(event)
    }

    suspend fun stop() {
        server.stopSuspend(0, 0)
    }
}
