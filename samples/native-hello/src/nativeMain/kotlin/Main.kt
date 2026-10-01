import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.module() {
    routing { get("/") { call.respondText("hello") } }
}

fun main() {
    println("native-hello")
}
