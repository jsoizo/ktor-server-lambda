package com.jsoizo.ktor.server.lambda.handler

import com.amazonaws.services.lambda.runtime.ClientContext
import com.amazonaws.services.lambda.runtime.CognitoIdentity
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.jsoizo.ktor.server.lambda.events.InvalidEventException
import com.jsoizo.ktor.server.lambda.lambda
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.crac.Resource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class Recorder {
    val primed = mutableListOf<String>()
}

private fun Application.testModule(recorder: Recorder) {
    routing {
        get("/hello") {
            val invocation = call.lambda.invocation
            call.respondText("${invocation.requestId} ${invocation.traceId} ${invocation.invokedFunctionArn}")
        }
        get("/deadline") { call.respondText(call.lambda.invocation.deadlineEpochMillis.toString()) }
        get("/broken") {
            recorder.primed += "broken"
            error("priming must survive this")
        }
        get("/health") {
            recorder.primed += "GET ${call.request.queryParameters["deep"]}"
            call.respondText("ok")
        }
        post("/warm") {
            recorder.primed += "POST ${call.receiveText()}"
            call.respondText("ok")
        }
    }
}

private class TestHandler(
    val recorder: Recorder = Recorder(),
    private val priming: List<PrimingRequest> = listOf(
        PrimingRequest.get("/health?deep=true"),
        PrimingRequest.postJson("/warm", """{"a":1}"""),
    ),
) : KtorRequestStreamHandler({ testModule(recorder) }) {
    override val primingRequests get() = priming

    fun prime() = beforeCheckpoint(NoopCracContext)
}

class KtorRequestStreamHandlerTest {
    @Test
    fun answersEventWithResponseInTheEventsFormat() {
        val json = invoke(TestHandler(), EVENT)
        assertEquals("200", json["statusCode"]!!.jsonPrimitive.content)
        assertEquals("req-9 Root=1-trace arn:aws:lambda:us-east-1:123456789012:function:f", json["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun rejectsInputThatIsNotAJsonObjectAsInvalidEvent() {
        assertFailsWith<InvalidEventException> { invoke(TestHandler(), "[1, 2]") }
        assertFailsWith<InvalidEventException> { invoke(TestHandler(), "{not json") }
    }

    @Test
    fun primingSendsEachRequestThroughThePipeline() {
        val handler = TestHandler()
        handler.prime()
        assertEquals(listOf("GET true", """POST {"a":1}"""), handler.recorder.primed)
    }

    @Test
    fun failingPrimingRequestDoesNotFailTheSnapshot() {
        // ErrorMode.HttpResponse turns the exception into a 500, which priming only logs.
        val handler =
            TestHandler(priming = listOf(PrimingRequest.get("/broken"), PrimingRequest.get("/missing"), PrimingRequest.get("/health")))
        handler.prime()
        assertEquals(listOf("broken", "GET null"), handler.recorder.primed)
    }

    @Test
    fun deadlineComesFromTheRemainingTime() {
        val before = System.currentTimeMillis()
        val deadline = invoke(TestHandler(), EVENT.replace("/hello", "/deadline"))["body"]!!.jsonPrimitive.content.toLong()
        assertTrue(deadline in before + 30_000..System.currentTimeMillis() + 30_000)
    }

    @Test
    fun fallsBackToTheTracePropertyWhenContextHasNoTraceId() {
        System.setProperty("com.amazonaws.xray.traceHeader", "Root=1-property")
        try {
            val json = invoke(TestHandler(), EVENT, FakeContext(traceId = null))
            assertEquals("req-9 Root=1-property arn:aws:lambda:us-east-1:123456789012:function:f", json["body"]!!.jsonPrimitive.content)
        } finally {
            System.clearProperty("com.amazonaws.xray.traceHeader")
        }
    }

    private fun invoke(handler: TestHandler, input: String, context: Context = FakeContext()) = ByteArrayOutputStream().let { output ->
        handler.handleRequest(ByteArrayInputStream(input.encodeToByteArray()), output, context)
        Json.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonObject
    }

    private companion object {
        const val EVENT = """
            {"version":"2.0","rawPath":"/hello","rawQueryString":"","headers":{"host":"h"},
             "requestContext":{"stage":"${'$'}default","http":{"method":"GET","sourceIp":"1.2.3.4"}},"isBase64Encoded":false}
        """
    }
}

private object NoopCracContext : org.crac.Context<Resource>() {
    override fun beforeCheckpoint(context: org.crac.Context<out Resource>) {
        // Not used by the test.
    }

    override fun afterRestore(context: org.crac.Context<out Resource>) {
        // Not used by the test.
    }

    override fun register(resource: Resource) {
        // Not used by the test.
    }
}

private class FakeContext(private val traceId: String? = "Root=1-trace") : Context {
    override fun getAwsRequestId(): String = "req-9"

    override fun getXrayTraceId(): String? = traceId

    override fun getLogGroupName(): String = "log-group"

    override fun getLogStreamName(): String = "log-stream"

    override fun getFunctionName(): String = "f"

    override fun getFunctionVersion(): String = "\$LATEST"

    override fun getInvokedFunctionArn(): String = "arn:aws:lambda:us-east-1:123456789012:function:f"

    override fun getIdentity(): CognitoIdentity? = null

    override fun getClientContext(): ClientContext? = null

    override fun getRemainingTimeInMillis(): Int = 30_000

    override fun getMemoryLimitInMB(): Int = 512

    override fun getLogger(): LambdaLogger = object : LambdaLogger {
        override fun log(message: String) = println(message)

        override fun log(message: ByteArray) = println(message.decodeToString())
    }
}
