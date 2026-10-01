package com.jsoizo.ktor.server.lambda.events

import com.jsoizo.ktor.server.lambda.events.internal.has
import com.jsoizo.ktor.server.lambda.events.internal.objOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
     * Parses a raw event payload as received from Lambda.
     *
     * @throws InvalidEventException if [payload] is not a JSON object
     */
    public fun parse(payload: ByteArray): JsonObject = try {
        Json.parseToJsonElement(payload.decodeToString()) as? JsonObject
            ?: throw InvalidEventException("Event is not a JSON object")
    } catch (e: SerializationException) {
        throw InvalidEventException("Event is not valid JSON", e)
    }

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
    public fun detect(event: JsonObject): LambdaHttpCodec<*> = when (val detected = classify(event)) {
        is Detected.Supported -> detected.codec
        is Detected.Unsupported -> throw UnsupportedEventException(detected.kind)
    }

    // The order matters: e.g. WebSocket $connect events also carry the v1 fields.
    private fun classify(event: JsonObject): Detected {
        val requestContext = event.objOrNull("requestContext")
        return when {
            (event["Records"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.has("cf") } == true ->
                Detected.Unsupported("Lambda@Edge")

            AlbCodec.matches(event) -> Detected.Supported(AlbCodec)

            requestContext?.has("serviceArn") == true || requestContext?.has("serviceNetworkArn") == true ->
                Detected.Unsupported("VPC Lattice")

            // VPC Lattice v1 events have no requestContext and use snake_case fields.
            event.has("raw_path") && event.has("method") -> Detected.Unsupported("VPC Lattice")

            ApiGatewayV2Codec.matches(event) -> Detected.Supported(ApiGatewayV2Codec)

            requestContext?.has("connectionId") == true -> Detected.Unsupported("API Gateway WebSocket")

            ApiGatewayV1Codec.matches(event) -> Detected.Supported(ApiGatewayV1Codec)

            else -> Detected.Unsupported("Non-HTTP event")
        }
    }

    private sealed interface Detected {
        class Supported(val codec: LambdaHttpCodec<*>) : Detected

        class Unsupported(val kind: String) : Detected
    }

    // A codec may hit any runtime exception on an unexpected JSON shape; report it as a malformed event.
    @Suppress("TooGenericExceptionCaught")
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
