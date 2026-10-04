package com.jsoizo.ktor.server.lambda.codec

import com.jsoizo.ktor.server.lambda.BinaryBodyPolicy
import com.jsoizo.ktor.server.lambda.EventSource
import com.jsoizo.ktor.server.lambda.InvalidEventException
import kotlinx.serialization.json.JsonObject

/**
 * Converts one Lambda event format to [LambdaHttpRequest] and a [LambdaHttpResponse] back to that format.
 *
 * @param S per-request information that [encode] needs from [decode], such as ALB's header mode.
 */
internal interface LambdaHttpCodec<S : Any> {
    /** The event source this codec handles. */
    val source: EventSource

    /** Returns `true` when [event] carries this format's discriminator fields. */
    fun matches(event: JsonObject): Boolean

    /**
     * Decodes [event] into a request.
     *
     * @throws InvalidEventException if a required field is missing or malformed
     */
    fun decode(event: JsonObject, config: CodecConfig): Decoded<S>

    /** Encodes [response] into the JSON the event source expects in reply to the request [state] came from. */
    fun encode(response: LambdaHttpResponse, state: S, config: CodecConfig): JsonObject
}

/** Result of [LambdaHttpCodec.decode]: the request and the [state] its response encoder needs. */
internal class Decoded<S : Any>(
    /** The decoded request. */
    val request: LambdaHttpRequest,
    /** Information to pass back to [LambdaHttpCodec.encode]. */
    val state: S,
)

/** Options shared by all codecs. */
internal class CodecConfig(
    /**
     * Strip a leading named-stage segment from HTTP API paths (payload 1.0 and 2.0) on execute-api hosts;
     * REST API paths never carry it.
     */
    val stripStage: Boolean = true,
    /** Path prefix to strip on a segment boundary, such as a custom domain base path mapping. */
    val stripBasePath: String? = null,
    /** Decides whether response bodies must be base64-encoded. */
    val binaryBodyPolicy: BinaryBodyPolicy = BinaryBodyPolicy.Default,
    /** Receives warnings about information the target format cannot carry, such as extra cookies on ALB. */
    val onWarning: (String) -> Unit = {},
)
