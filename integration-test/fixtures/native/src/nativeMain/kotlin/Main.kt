import com.jsoizo.ktor.server.lambda.runtime.AwsLambda
import com.jsoizo.ktor.server.lambda.runtime.lambdaMain
import fixture.fixtureModule
import io.ktor.server.engine.embeddedServer

fun main() = lambdaMain { embeddedServer(AwsLambda) { fixtureModule() } }
