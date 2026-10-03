import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The use cases every packaging of the engine must keep working, run against the real Lambda base images.
 * Event shapes themselves are covered by the codec tests; this checks the runtime, the packaging and the wiring.
 */
abstract class LambdaFunctionContract {
    protected abstract val function: LambdaFunction

    /** How the runtime reports an event the engine cannot handle; the managed runtime names the exception class. */
    protected abstract val unsupportedEventError: String

    @Test
    fun respondsToHttpApiEvent() = invoking(Events.httpApi("GET", "/")) { response ->
        assertEquals(200, response.status)
        assertContains(response.text, "\"source\":\"ApiGatewayV2\"")
        assertEquals("application/json", response["headers"]!!.jsonObject["Content-Type"]?.jsonPrimitive?.content)
    }

    @Test
    fun respondsToRestApiEvent() = invoking(Events.restApi("GET", "/")) { response ->
        assertEquals(200, response.status)
        assertContains(response.text, "\"source\":\"ApiGatewayV1\"")
        assertTrue("multiValueHeaders" in response)
    }

    @Test
    fun respondsToAlbEventInItsHeaderMode() {
        invoking(Events.alb("GET", "/", multiValue = false)) { response ->
            assertEquals(200, response.status)
            assertEquals("200 OK", response["statusDescription"]?.jsonPrimitive?.content)
            assertTrue("headers" in response)
            assertFalse("multiValueHeaders" in response)
        }
        invoking(Events.alb("GET", "/", multiValue = true)) { response ->
            assertEquals(200, response.status)
            assertTrue("multiValueHeaders" in response)
            assertFalse("headers" in response)
        }
    }

    @Test
    fun keepsEveryCookie() {
        invoking(Events.httpApi("GET", "/cookies")) { response ->
            assertEquals(listOf("a=1", "b=2"), response["cookies"]!!.jsonArray.map { it.jsonPrimitive.content.substringBefore(';') })
        }
        for (event in listOf(Events.restApi("GET", "/cookies"), Events.alb("GET", "/cookies", multiValue = true))) {
            invoking(event) { response ->
                val cookies = response["multiValueHeaders"]!!.jsonObject["Set-Cookie"]!!.jsonArray
                assertEquals(listOf("a=1", "b=2"), cookies.map { it.jsonPrimitive.content.substringBefore(';') })
            }
        }
    }

    @Test
    fun returnsBinaryBodyAsBase64() {
        // Not valid UTF-8, so it must survive the trip only as base64.
        val bytes = byteArrayOf(0xFF.toByte(), 0x00, 0xC3.toByte(), 0x28, 0x7F, 0x80.toByte())
        val event = Events.httpApi("POST", "/binary", mapOf("content-type" to "application/octet-stream"), bytes)
        invoking(event) { response ->
            assertEquals(200, response.status)
            assertTrue(response["isBase64Encoded"]!!.jsonPrimitive.boolean)
            assertContentEquals(bytes, Base64.decode(response.text))
        }
    }

    @Test
    fun returnsBodyLargerThanTheChannelBuffer() {
        val size = 2 * 1024 * 1024
        invoking(Events.httpApi("GET", "/large?size=$size")) { response ->
            assertEquals(200, response.status)
            assertFalse(response["isBase64Encoded"]!!.jsonPrimitive.boolean)
            assertEquals(size, response.text.length)
        }
    }

    @Test
    fun keepsServingAfterAnApplicationException() {
        invoking(Events.httpApi("GET", "/boom")) { response -> assertEquals(500, response.status) }
        invoking(Events.httpApi("GET", "/")) { response -> assertEquals(200, response.status) }
    }

    @Test
    fun separatesConsecutiveInvocations() {
        val first = invoking(Events.httpApi("GET", "/invocation", mapOf("x-marker" to "first"))) { it.text }
        val second = invoking(Events.httpApi("GET", "/invocation", mapOf("x-marker" to "second"))) { it.text }
        assertContains(first, "\"marker\":\"first\"")
        assertContains(second, "\"marker\":\"second\"")
        assertTrue(requestId(first).isNotBlank(), "Missing request id")
        assertNotEquals(requestId(first), requestId(second))
    }

    @Test
    fun reportsNonHttpEventAsError() = invoking(Events.SQS) { response ->
        assertContains(response["errorType"]?.jsonPrimitive?.content.orEmpty(), unsupportedEventError)
    }

    private fun <T> invoking(event: String, assertions: (JsonObject) -> T): T = function.withLogs { assertions(function.invoke(event)) }

    private val JsonObject.status get() = this["statusCode"]?.jsonPrimitive?.content?.toInt()

    private val JsonObject.text get() = this["body"]!!.jsonPrimitive.content

    private fun requestId(body: String) = body.substringAfter("\"requestId\":\"").substringBefore('"')
}

class NativeFunctionTest : LambdaFunctionContract() {
    override val function get() = shared
    override val unsupportedEventError = "Runtime.UnsupportedEvent"

    // One container per packaging: starting it is the slow part, an invocation takes milliseconds.
    private companion object {
        val shared by lazy { LambdaFunction.nativeBootstrap() }
    }
}

class JvmManagedFunctionTest : LambdaFunctionContract() {
    override val function get() = shared
    override val unsupportedEventError = "UnsupportedEventException"

    private companion object {
        val shared by lazy { LambdaFunction.jvmManaged() }
    }
}
