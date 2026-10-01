package com.jsoizo.ktor.server.lambda

import com.jsoizo.ktor.server.lambda.events.EventSource
import com.jsoizo.ktor.server.lambda.events.LambdaHttpRequest
import com.jsoizo.ktor.server.lambda.events.LambdaHttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
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
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
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
    fun applicationExceptionBecomes500WithKtorsDefaultBody() = runTest {
        // Ktor's own failure handling puts the message in the body, as on every engine; see the README.
        val engine = start { routing { get("/") { error("boom") } } }
        val response = engine.handle(request("GET", "/"), invocation)
        assertEquals(500 to "boom", response.status to response.body.decodeToString())
    }

    @Test
    fun failureAfterHeadersAreCommittedIsRaisedInsteadOfAnEmptySuccess() = runTest {
        val engine = start {
            routing {
                get("/") {
                    call.respondBytesWriter {
                        writeFully("partial".encodeToByteArray())
                        error("broke mid-stream")
                    }
                }
            }
        }
        assertFailsWith<IllegalStateException> { engine.handle(request("GET", "/"), invocation) }
    }

    @Test
    fun contentLengthThatDoesNotMatchTheBodyIsRaised() = runTest {
        val engine = start {
            routing {
                get("/") {
                    call.respond(
                        object : OutgoingContent.ByteArrayContent() {
                            override val contentLength: Long = 10
                            override fun bytes(): ByteArray = "short".encodeToByteArray()
                        },
                    )
                }
            }
        }
        assertFails { engine.handle(request("GET", "/"), invocation) }
    }

    @Test
    fun collectsBodiesServedFromAReadChannel() = runTest {
        val engine = start {
            routing {
                get("/") {
                    call.respond(
                        object : OutgoingContent.ReadChannelContent() {
                            override fun readFrom(): ByteReadChannel = ByteReadChannel("from a channel")
                        },
                    )
                }
            }
        }
        assertEquals("from a channel", engine.handle(request("GET", "/"), invocation).body.decodeToString())
    }

    @Test
    fun failingBackgroundCoroutineDoesNotBreakTheResponse() = runTest {
        val engine = start {
            routing {
                get("/") {
                    call.launch { error("background failure") }
                    delay(50)
                    call.respondText("still fine")
                }
            }
        }
        val response = engine.handle(request("GET", "/"), invocation)
        assertEquals(200 to "still fine", response.status to response.body.decodeToString())
    }

    @Test
    fun exposesConnectionDetailsFromTheEvent() = runTest {
        val engine = start {
            routing {
                get("/") {
                    val local = call.request.local
                    call.respondText(
                        listOf(local.scheme, local.localHost, local.localPort, local.remoteHost, local.remotePort).joinToString(" "),
                    )
                }
            }
        }
        val http = engine.handle(request("GET", "/", host = "api.example.com", scheme = "http", port = null), invocation)
        assertEquals("http api.example.com 80 198.51.100.7 4321", http.body.decodeToString())
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
        scheme: String = "https",
        port: Int? = 443,
    ) = LambdaHttpRequest(
        method = method,
        scheme = scheme,
        host = host,
        port = port,
        path = path,
        rawQuery = rawQuery,
        headers = listOf("Host" to host) + headers,
        body = body.encodeToByteArray(),
        remoteAddress = "198.51.100.7",
        remotePort = 4321,
        source = EventSource.ApiGatewayV2,
        isFunctionUrl = false,
        requestContext = null,
        rawEvent = JsonObject(emptyMap()),
    )
}
