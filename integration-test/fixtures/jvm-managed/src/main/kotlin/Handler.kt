import com.jsoizo.ktor.server.lambda.handler.KtorRequestStreamHandler
import fixture.fixtureModule

class Handler : KtorRequestStreamHandler({ fixtureModule() })
