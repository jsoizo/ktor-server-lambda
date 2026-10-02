package com.jsoizo.ktor.server.lambda.events

import kotlinx.serialization.json.JsonObject

/**
 * Converts one Lambda event format to [LambdaHttpRequest] and a [LambdaHttpResponse] back to that format.
 *
 * @param S per-request information that [encode] needs from [decode], such as ALB's header mode.
 */
public interface LambdaHttpCodec<S : Any> {
    /** The event source this codec handles. */
    public val source: EventSource

    /** Returns `true` when [event] carries this format's discriminator fields. */
    public fun matches(event: JsonObject): Boolean

    /**
     * Decodes [event] into a request.
     *
     * @throws InvalidEventException if a required field is missing or malformed
     */
    public fun decode(event: JsonObject, config: CodecConfig): Decoded<S>

    /** Encodes [response] into the JSON the event source expects in reply to the request [state] came from. */
    public fun encode(response: LambdaHttpResponse, state: S, config: CodecConfig): JsonObject

    /** Extension point for response streaming in a later release; `null` while only buffered responses exist. */
    public val streaming: StreamingPreludeEncoder<S>? get() = null
}

/** Result of [LambdaHttpCodec.decode]: the request and the [state] its response encoder needs. */
public class Decoded<S : Any>(
    /** The decoded request. */
    public val request: LambdaHttpRequest,
    /** Information to pass back to [LambdaHttpCodec.encode]. */
    public val state: S,
)

/**
 * Builds the metadata prelude of a streamed HTTP response.
 *
 * See [response streaming for custom runtimes](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html#runtimes-custom-response-streaming).
 */
public interface StreamingPreludeEncoder<S : Any> {
    /** Returns the JSON sent before the eight NUL delimiter bytes and the body. */
    public fun prelude(status: Int, headers: List<Pair<String, String>>, state: S): JsonObject
}

/** Options shared by all codecs. */
public class CodecConfig(
    /**
     * Strip a leading named-stage segment from HTTP API paths (payload 1.0 and 2.0) on execute-api hosts;
     * REST API paths never carry it.
     */
    public val stripStage: Boolean = true,
    /** Path prefix to strip on a segment boundary, such as a custom domain base path mapping. */
    public val stripBasePath: String? = null,
    /** Decides whether response bodies must be base64-encoded. */
    public val binaryBodyPolicy: BinaryBodyPolicy = BinaryBodyPolicy.Default,
    /** Receives warnings about information the target format cannot carry, such as extra cookies on ALB. */
    public val onWarning: (String) -> Unit = {},
)
