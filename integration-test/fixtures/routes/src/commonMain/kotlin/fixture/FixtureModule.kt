package fixture

import com.jsoizo.ktor.server.lambda.lambda
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully

/** The routes the integration tests call in every packaging of the engine. */
fun Application.fixtureModule() {
    routing {
        get("/") {
            call.respondText("""{"message":"hello","source":"${call.lambda.source.name}"}""", ContentType.Application.Json)
        }
        get("/cookies") {
            call.response.cookies.append("a", "1")
            call.response.cookies.append("b", "2")
            call.respondText("cookies")
        }
        post("/binary") {
            call.respondBytes(call.receive<ByteArray>(), ContentType.Application.OctetStream)
        }
        // Streams through a channel, the path that once deadlocked on bodies over 1 MiB.
        get("/large") {
            val size = call.request.queryParameters["size"]!!.toInt()
            val chunk = ByteArray(CHUNK_SIZE) { 'x'.code.toByte() }
            call.respondBytesWriter(ContentType.Text.Plain) {
                var left = size
                while (left > 0) {
                    val length = minOf(left, CHUNK_SIZE)
                    writeFully(chunk, 0, length)
                    left -= length
                }
            }
        }
        get("/boom") {
            error("boom")
        }
        get("/invocation") {
            val requestId = call.lambda.invocation.requestId
            val marker = call.request.headers["X-Marker"].orEmpty()
            call.respondText("""{"requestId":"$requestId","marker":"$marker"}""", ContentType.Application.Json)
        }
    }
}

private const val CHUNK_SIZE = 8192
