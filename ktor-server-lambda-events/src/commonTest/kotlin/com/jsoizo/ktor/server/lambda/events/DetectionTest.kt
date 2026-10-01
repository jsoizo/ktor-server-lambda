package com.jsoizo.ktor.server.lambda.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DetectionTest {
    @Test
    fun detectsEachSupportedFormat() {
        assertEquals(EventSource.ApiGatewayV1, decode(Fixtures.REST_V1).source)
        assertEquals(EventSource.ApiGatewayV2, decode(Fixtures.HTTP_V2).source)
        assertEquals(EventSource.ApiGatewayV2, decode(Fixtures.functionUrl).source)
        assertEquals(EventSource.Alb, decode(Fixtures.ALB_MULTI).source)
        assertEquals(EventSource.Alb, decode(Fixtures.ALB_SINGLE).source)
    }

    @Test
    fun restEventWithRawPathFromBasePathMappingStaysV1() {
        // Hono misclassified this case as v2 by checking rawPath alone.
        val event = Fixtures.REST_V1.replace("\"resource\"", "\"rawPath\": \"/users/42\", \"resource\"")
        assertEquals(EventSource.ApiGatewayV1, decode(event).source)
    }

    @Test
    fun restEventWithVersion10StaysV1() {
        val event = Fixtures.REST_V1.replace("\"resource\"", "\"version\": \"1.0\", \"resource\"")
        assertEquals(EventSource.ApiGatewayV1, decode(event).source)
    }

    @Test
    fun rejectsUnsupportedFormatsWithTheirKind() {
        val cases = mapOf(
            """{"Records":[{"cf":{"request":{}}}]}""" to "Lambda@Edge",
            """{"version":"2.0","method":"GET","path":"/","requestContext":{"serviceArn":"arn","serviceNetworkArn":"arn"}}""" to
                "VPC Lattice",
            """{"raw_path":"/","method":"GET","headers":{},"is_base64_encoded":false}""" to "VPC Lattice",
            """{"httpMethod":"GET","path":"/","resource":"/","requestContext":{"connectionId":"abc","eventType":"CONNECT"}}""" to
                "API Gateway WebSocket",
            """{"Records":[{"eventSource":"aws:sqs","body":"x"}]}""" to "Non-HTTP event",
        )
        for ((json, kind) in cases) {
            val e = assertFailsWith<UnsupportedEventException>(json) { LambdaHttpCodecs.decode(Fixtures.parse(json)) }
            assertEquals(kind, e.kind)
        }
    }

    @Test
    fun brokenDetectedEventIsInvalidNotUnsupported() {
        val event = """{"version":"2.0","requestContext":{"http":{}},"rawPath":"/"}"""
        assertFailsWith<InvalidEventException> { LambdaHttpCodecs.decode(Fixtures.parse(event)) }
    }

    private fun decode(json: String) = LambdaHttpCodecs.decode(Fixtures.parse(json)).request
}
