package com.jsoizo.ktor.server.lambda

import com.jsoizo.ktor.server.lambda.events.LambdaHttpResponse
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal class LambdaApplicationResponse(private val lambdaCall: LambdaApplicationCall) : BaseApplicationResponse(lambdaCall) {
    private val headerList = mutableListOf<Pair<String, String>>()
    private var body: Deferred<ByteArray>? = null

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

    override suspend fun respondFromBytes(bytes: ByteArray) {
        ensureContentLength(bytes)
        body = CompletableDeferred(bytes)
    }

    private fun ensureContentLength(bytes: ByteArray) {
        val length = headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: return
        when {
            length < bytes.size -> throw BodyLengthIsTooLong(length)
            length > bytes.size -> throw BodyLengthIsTooSmall(length, bytes.size.toLong())
        }
    }

    override suspend fun respondNoContent(content: OutgoingContent.NoContent) {
        body = CompletableDeferred(ByteArray(0))
    }

    override suspend fun respondFromChannel(readChannel: ByteReadChannel) {
        val bytes = readChannel.toByteArray()
        ensureContentLength(bytes)
        body = CompletableDeferred(bytes)
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
        body = CompletableDeferred(bytes)
    }

    override suspend fun responseChannel(): ByteWriteChannel {
        val channel = ByteChannel()
        body = lambdaCall.async { channel.toByteArray() }
        return channel
    }

    override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade): Unit =
        throw UnsupportedOperationException("Protocol upgrade is not supported on AWS Lambda")

    suspend fun toLambdaResponse(): LambdaHttpResponse {
        val bytes = body?.await() ?: ByteArray(0)
        val isHead = lambdaCall.request.local.method == HttpMethod.Head
        return LambdaHttpResponse(
            status = status()?.value ?: HttpStatusCode.OK.value,
            headers = headerList.toList(),
            body = if (isHead) ByteArray(0) else bytes,
        )
    }
}
