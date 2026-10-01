import com.jsoizo.ktor.server.lambda.handler.KtorRequestStreamHandler
import com.jsoizo.ktor.server.lambda.handler.PrimingRequest
import com.jsoizo.ktor.server.lambda.lambdaOrNull
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

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
    }
}
