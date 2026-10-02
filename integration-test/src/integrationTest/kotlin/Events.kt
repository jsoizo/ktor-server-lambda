import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64

/** Events shaped like the examples in the AWS documentation, trimmed to the fields a proxy integration relies on. */
object Events {
    private const val SOURCE_IP = "192.0.2.1"
    private const val API_HOST = "api.execute-api.us-east-1.amazonaws.com"

    const val SQS = """{"Records":[{"eventSource":"aws:sqs","body":"x"}]}"""

    fun httpApi(method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): String =
        buildJsonObject {
            put("version", "2.0")
            put("routeKey", "\$default")
            put("rawPath", path.substringBefore('?'))
            put("rawQueryString", path.substringAfter('?', ""))
            put("headers", strings(mapOf("host" to API_HOST) + headers))
            putJsonObject("requestContext") {
                put("apiId", "api")
                put("domainName", API_HOST)
                put("requestId", "request")
                put("stage", "\$default")
                putJsonObject("http") {
                    put("method", method)
                    put("path", path.substringBefore('?'))
                    put("protocol", "HTTP/1.1")
                    put("sourceIp", SOURCE_IP)
                }
            }
            putBody(body)
        }.toString()

    fun restApi(method: String, path: String): String = buildJsonObject {
        put("resource", "/{proxy+}")
        put("path", path)
        put("httpMethod", method)
        put("headers", strings(mapOf("Host" to API_HOST)))
        put("multiValueHeaders", multiStrings(mapOf("Host" to API_HOST)))
        putJsonObject("requestContext") {
            put("resourcePath", "/{proxy+}")
            put("httpMethod", method)
            put("path", "/prod$path")
            put("stage", "prod")
            put("requestId", "request")
            put("domainName", API_HOST)
            put("apiId", "api")
            putJsonObject("identity") { put("sourceIp", SOURCE_IP) }
        }
        putBody(null)
    }.toString()

    fun alb(method: String, path: String, multiValue: Boolean): String = buildJsonObject {
        putJsonObject("requestContext") {
            putJsonObject("elb") { put("targetGroupArn", "arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/tg/1") }
        }
        put("httpMethod", method)
        put("path", path)
        val headers = mapOf("host" to "alb.example.com", "x-forwarded-for" to SOURCE_IP)
        if (multiValue) {
            put("multiValueQueryStringParameters", JsonObject(emptyMap()))
            put("multiValueHeaders", multiStrings(headers))
        } else {
            put("queryStringParameters", JsonObject(emptyMap()))
            put("headers", strings(headers))
        }
        putBody(null)
    }.toString()

    private fun JsonObjectBuilder.putBody(body: ByteArray?) {
        put("body", body?.let { Base64.encode(it) } ?: "")
        put("isBase64Encoded", body != null)
    }

    private fun strings(values: Map<String, String>) = JsonObject(values.mapValues { JsonPrimitive(it.value) })

    private fun multiStrings(values: Map<String, String>) = JsonObject(values.mapValues { JsonArray(listOf(JsonPrimitive(it.value))) })
}
