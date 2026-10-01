package com.jsoizo.ktor.server.lambda

import com.jsoizo.ktor.server.lambda.events.EventSource
import com.jsoizo.ktor.server.lambda.events.LambdaHttpRequest
import com.jsoizo.ktor.server.lambda.events.LambdaHttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LambdaApplicationEngineTest {
    private var server: EmbeddedServer<LambdaApplicationEngine, LambdaApplicationEngine.Configuration>? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
    }

    private fun start(
        configure: LambdaApplicationEngine.Configuration.() -> Unit = {},
        module: Application.() -> Unit,
    ): LambdaApplicationEngine {
        val s = embeddedServer(AwsLambdaHandler, configure = configure, module = module)
        s.start(wait = false)
        server = s
        return s.engine
    }

    @Test
    fun routesRequestAndPassesQueryHeadersAndBody() = runTest {
        val engine = start {
            routing {
                post("/echo") {
                    val name = call.request.queryParameters["name"]
                    call.respondText("${call.request.headers["X-Id"]}:$name:${call.receiveText()}")
                }
            }
        }
        val response = engine.handle(
            request("POST", "/echo", rawQuery = "name=a%20b", headers = listOf("X-Id" to "7"), body = "hi"),
            invocation,
        )
        assertEquals(200, response.status)
        assertEquals("7:a b:hi", response.body.decodeToString())
    }

    @Test
    fun unmatchedRouteFallsBackTo404() = runTest {
        val engine = start { routing { get("/") { call.respondText("x") } } }
        assertEquals(404, engine.handle(request("GET", "/missing"), invocation).status)
    }

    @Test
    fun collectsWriterBodyLargerThanChannelBuffer() = runTest {
        // ByteChannel suspends once ~1 MiB is buffered with no reader.
        val payload = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val engine = start {
            routing {
                get("/big") {
                    call.respondBytesWriter(ContentType.Application.OctetStream) { writeFully(payload) }
                }
            }
        }
        val response = engine.handle(request("GET", "/big"), invocation)
        assertContentEquals(payload, response.body)
        assertTrue(response.headers.none { it.first.equals("Transfer-Encoding", ignoreCase = true) })
    }

    @Test
    fun keepsEverySetCookieValue() = runTest {
        val engine = start {
            routing {
                get("/") {
                    call.response.cookies.append("a", "1")
                    call.response.cookies.append("b", "2")
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
        val response = engine.handle(request("GET", "/"), invocation)
        assertEquals(204, response.status)
        assertEquals(2, response.headers.count { it.first.equals("Set-Cookie", ignoreCase = true) })
    }

    @Test
    fun headDropsBodyButKeepsContentLength() = runTest {
        val engine = start {
            routing { head("/") { call.respondText("hello") } }
        }
        val response = engine.handle(request("HEAD", "/"), invocation)
        assertEquals(0, response.body.size)
        assertEquals("5", response.headers.single { it.first.equals("Content-Length", ignoreCase = true) }.second)
    }

    @Test
    fun applicationExceptionBecomes500ByDefault() = runTest {
        val engine = start { routing { get("/") { error("boom") } } }
        assertEquals(500, engine.handle(request("GET", "/"), invocation).status)
    }

    @Test
    fun lambdaErrorModeRethrowsApplicationException() = runTest {
        val engine = start(configure = { errorMode = ErrorMode.LambdaError }) {
            routing { get("/") { throw IllegalStateException("boom") } }
        }
        assertFailsWith<IllegalStateException> { engine.handle(request("GET", "/"), invocation) }
    }

    @Test
    fun coroutinesLaunchedInCallScopeDoNotDelayResponseAndAreCancelled() = runTest {
        val child = CompletableDeferred<Job>()
        val engine = start {
            routing {
                get("/") {
                    child.complete(call.launch { awaitCancellation() })
                    call.respondText("done")
                }
            }
        }
        val response = engine.handle(request("GET", "/"), invocation)
        assertEquals("done", response.body.decodeToString())
        assertTrue(child.await().isCancelled)
    }

    @Test
    fun lambdaErrorModeKeepsClientErrorStatus() = runTest {
        val engine = start(configure = { errorMode = ErrorMode.LambdaError }) {
            routing { get("/") { throw BadRequestException("bad input") } }
        }
        assertEquals(400, engine.handle(request("GET", "/"), invocation).status)
    }

    @Test
    fun splitsIpv6HostFromPort() = runTest {
        val engine = start {
            routing { get("/") { call.respondText("${call.request.local.serverHost} ${call.request.local.serverPort}") } }
        }
        val response = engine.handle(request("GET", "/", headers = emptyList(), host = "[::1]:8080"), invocation)
        assertEquals("[::1] 8080", response.body.decodeToString())
    }

    @Test
    fun exposesLambdaContextToHandlers() = runTest {
        val engine = start {
            routing {
                get("/") {
                    call.response.header("X-Request-Id", call.lambda.invocation.requestId)
                    call.respondText("${call.lambda.source}:${call.request.local.remoteAddress}")
                }
            }
        }
        val response = engine.handle(request("GET", "/"), invocation)
        assertEquals("ApiGatewayV2:198.51.100.7", response.body.decodeToString())
        assertEquals("req-1", response.headers.single { it.first == "X-Request-Id" }.second)
    }

    @Test
    fun handlesRawEventEndToEnd() = runTest {
        val engine = start(configure = { stripBasePath = "/api" }) {
            routing { get("/items") { call.respondText("""{"ok":true}""", ContentType.Application.Json) } }
        }
        val event = kotlinx.serialization.json.Json.parseToJsonElement(
            """
            {"version":"2.0","rawPath":"/prod/api/items","rawQueryString":"","headers":{"host":"h"},
             "requestContext":{"stage":"prod","domainName":"abc.execute-api.us-east-1.amazonaws.com","http":{"method":"GET","sourceIp":"1.2.3.4"}},"isBase64Encoded":false}
            """,
        ) as JsonObject
        val json = engine.handle(event, invocation)
        assertEquals("200", json["statusCode"].toString())
        assertEquals("\"{\\\"ok\\\":true}\"", json["body"].toString())
    }

    private val invocation = LambdaInvocation(requestId = "req-1")

    private fun request(
        method: String,
        path: String,
        rawQuery: String = "",
        headers: List<Pair<String, String>> = emptyList(),
        body: String = "",
        host: String = "example.com",
    ) = LambdaHttpRequest(
        method = method,
        scheme = "https",
        host = "example.com",
        port = 443,
        path = path,
        rawQuery = rawQuery,
        headers = listOf("Host" to host) + headers,
        body = body.encodeToByteArray(),
        remoteAddress = "198.51.100.7",
        source = EventSource.ApiGatewayV2,
        isFunctionUrl = false,
        requestContext = null,
        rawEvent = JsonObject(emptyMap()),
    )
}
