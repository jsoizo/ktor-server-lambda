package com.jsoizo.ktor.server.lambda.events

import com.jsoizo.ktor.server.lambda.events.internal.has
import com.jsoizo.ktor.server.lambda.events.internal.objOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Bundles the decoded request with the state its encoder needs, hiding the codec's type parameter from callers. */
public class DecodedEvent internal constructor(
    public val request: LambdaHttpRequest,
    private val encoder: (LambdaHttpResponse) -> JsonObject,
) {
    /** Encodes [response] into the format of the event [request] was decoded from. */
    public fun encode(response: LambdaHttpResponse): JsonObject = encoder(response)
}

/** Entry point that picks the codec for an event and decodes it. */
public object LambdaHttpCodecs {
    /**
     * Detects the format by discriminator fields; trial deserialization into each shape misclassifies events.
     *
     * @throws UnsupportedEventException if the event format is not supported
     * @throws InvalidEventException if the format was detected but the event is malformed
     */
    public fun decode(event: JsonObject, config: CodecConfig = CodecConfig()): DecodedEvent {
        val codec = detect(event)
        return decodeWith(codec, event, config)
    }

    /**
     * Returns the codec for [event], checking discriminator fields in a fixed order.
     *
     * @throws UnsupportedEventException if the event is Lambda@Edge, VPC Lattice, WebSocket or not HTTP at all
     */
    public fun detect(event: JsonObject): LambdaHttpCodec<*> {
        val requestContext = event.objOrNull("requestContext")
        return when {
            (event["Records"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.has("cf") } == true ->
                throw UnsupportedEventException("Lambda@Edge")
            AlbCodec.matches(event) -> AlbCodec
            requestContext?.has("serviceArn") == true || requestContext?.has("serviceNetworkArn") == true ->
                throw UnsupportedEventException("VPC Lattice")
            ApiGatewayV2Codec.matches(event) -> ApiGatewayV2Codec
            requestContext?.has("connectionId") == true -> throw UnsupportedEventException("API Gateway WebSocket")
            ApiGatewayV1Codec.matches(event) -> ApiGatewayV1Codec
            else -> throw UnsupportedEventException("Non-HTTP event")
        }
    }

    private fun <S : Any> decodeWith(codec: LambdaHttpCodec<S>, event: JsonObject, config: CodecConfig): DecodedEvent {
        val decoded = try {
            codec.decode(event, config)
        } catch (e: InvalidEventException) {
            throw e
        } catch (e: RuntimeException) {
            throw InvalidEventException("Failed to decode ${codec.source} event", e)
        }
        return DecodedEvent(decoded.request) { response -> codec.encode(response, decoded.state, config) }
    }
}
