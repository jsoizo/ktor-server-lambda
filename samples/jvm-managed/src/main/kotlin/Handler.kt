import com.jsoizo.ktor.server.lambda.handler.KtorRequestStreamHandler
import com.jsoizo.ktor.server.lambda.handler.PrimingRequest
import com.jsoizo.ktor.server.lambda.lambdaOrNull
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Set `Handler` as the function handler on the java21 runtime. */
class Handler : KtorRequestStreamHandler({ module() }) {
    override val primingRequests get() = listOf(PrimingRequest.get("/"))
}

fun Application.module() {
    routing {
        get("/") {
            val source = call.lambdaOrNull?.source?.name ?: "local"
            call.respondText("""{"message":"hello","source":"$source"}""", ContentType.Application.Json)
        }
        // Diagnostic route: shows how each event source encodes paths and queries (see infra/README.md).
        get("/echo/{path...}") {
            val echo = buildJsonObject {
                put("uri", call.request.uri)
                put("segments", JsonArray(call.parameters.getAll("path").orEmpty().map(::JsonPrimitive)))
                put(
                    "query",
                    JsonObject(call.request.queryParameters.entries().associate { (k, v) -> k to JsonArray(v.map(::JsonPrimitive)) }),
                )
                call.lambdaOrNull?.let { put("rawEvent", it.rawEvent) }
            }
            call.respondText(echo.toString(), ContentType.Application.Json)
        }
    }
}
