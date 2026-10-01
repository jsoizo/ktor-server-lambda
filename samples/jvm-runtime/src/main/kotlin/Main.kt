import com.jsoizo.ktor.server.lambda.lambdaOrNull
import com.jsoizo.ktor.server.lambda.runtime.AwsLambda
import com.jsoizo.ktor.server.lambda.runtime.isRunningOnLambda
import com.jsoizo.ktor.server.lambda.runtime.lambdaMain
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main() = lambdaMain {
    if (isRunningOnLambda()) {
        embeddedServer(AwsLambda) { module() }
    } else {
        embeddedServer(CIO, port = 8080) { module() }
    }
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
