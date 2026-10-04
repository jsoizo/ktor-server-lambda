package com.jsoizo.ktor.server.lambda

import com.jsoizo.ktor.server.lambda.codec.LambdaHttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.engine.BaseApplicationResponse
import io.ktor.server.response.ResponseHeaders
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal class LambdaApplicationResponse(private val lambdaCall: LambdaApplicationCall) : BaseApplicationResponse(lambdaCall) {
    private val headerList = mutableListOf<Pair<String, String>>()
    private var body: ByteArray? = null

    /** A failure after the status and headers were committed, when no error response can replace them. */
    var failureAfterCommit: Throwable? = null
        private set

    override val headers: ResponseHeaders = object : ResponseHeaders() {
        // API Gateway and ALB rebuild the HTTP response, so connection-level headers are meaningless here.
        override val managedByEngineHeaders: Set<String> = setOf(HttpHeaders.TransferEncoding, HttpHeaders.Connection)

        override fun engineAppendHeader(name: String, value: String) {
            headerList += name to value
        }

        override fun getEngineHeaderNames(): List<String> = headerList.map { it.first }.distinctBy { it.lowercase() }

        override fun getEngineHeaderValues(name: String): List<String> =
            headerList.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
    }

    override fun setStatus(statusCode: HttpStatusCode) {
        // BaseApplicationResponse keeps the status; toLambdaResponse() reads it from there.
    }

    // Ktor's own failure handling cannot respond once headers are committed; without this the call would
    // come back as a successful, truncated response.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun respondOutgoingContent(content: OutgoingContent) {
        try {
            super.respondOutgoingContent(content)
        } catch (error: Throwable) {
            if (isCommitted) failureAfterCommit = error
            throw error
        }
    }

    override suspend fun respondFromBytes(bytes: ByteArray) {
        ensureContentLength(bytes)
        body = bytes
    }

    private fun ensureContentLength(bytes: ByteArray) {
        val length = headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: return
        when {
            length < bytes.size -> throw BodyLengthIsTooLong(length)
            length > bytes.size -> throw BodyLengthIsTooSmall(length, bytes.size.toLong())
        }
    }

    override suspend fun respondNoContent(content: OutgoingContent.NoContent) {
        body = ByteArray(0)
    }

    override suspend fun respondFromChannel(readChannel: ByteReadChannel) {
        val bytes = readChannel.toByteArray()
        ensureContentLength(bytes)
        body = bytes
    }

    // Start the reader first: ByteChannel.flush() suspends once ~1 MiB is buffered with no reader.
    override suspend fun respondWriteChannelContent(content: OutgoingContent.WriteChannelContent) {
        val bytes = coroutineScope {
            val channel = ByteChannel()
            val reader = async { channel.toByteArray() }
            try {
                content.writeTo(channel)
            } finally {
                channel.flushAndClose()
            }
            reader.await()
        }
        body = bytes
    }

    // Every caller in BaseApplicationResponse is overridden above, so nothing should reach this.
    override suspend fun responseChannel(): ByteWriteChannel =
        throw UnsupportedOperationException("The AWS Lambda engine collects responses without a response channel")

    override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade): Unit =
        throw UnsupportedOperationException("Protocol upgrade is not supported on AWS Lambda")

    fun toLambdaResponse(): LambdaHttpResponse {
        val bytes = body ?: ByteArray(0)
        val isHead = lambdaCall.request.local.method == HttpMethod.Head
        return LambdaHttpResponse(
            status = status()?.value ?: HttpStatusCode.OK.value,
            headers = headerList.toList(),
            body = if (isHead) ByteArray(0) else bytes,
        )
    }
}
