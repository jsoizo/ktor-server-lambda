import com.jsoizo.ktor.server.lambda.lambdaOrNull
import com.jsoizo.ktor.server.lambda.runtime.AwsLambda
import com.jsoizo.ktor.server.lambda.runtime.isRunningOnLambda
import com.jsoizo.ktor.server.lambda.runtime.lambdaMain
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

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
    }
}
