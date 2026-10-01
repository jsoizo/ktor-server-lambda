package com.jsoizo.ktor.server.lambda.runtime

import com.jsoizo.ktor.server.lambda.LambdaInvocation
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [LambdaRuntimeClient] on Ktor's CIO HTTP client; the Runtime API is plain HTTP, so no TLS is needed.
 *
 * @param endpoint host and port of the Runtime API, from `AWS_LAMBDA_RUNTIME_API` by default
 * @param maxConnections connection limit; must exceed the number of workers polling `GET /next` concurrently
 */
public class CioLambdaRuntimeClient(
    endpoint: String = getenv("AWS_LAMBDA_RUNTIME_API")
        ?: error("AWS_LAMBDA_RUNTIME_API is not set; not running on AWS Lambda?"),
    maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
) : LambdaRuntimeClient {
    private val baseUrl = "http://$endpoint/2018-06-01/runtime"

    private val client = HttpClient(CIO) {
        expectSuccess = false
        engine {
            // The default 15 s would abort GET /next while it waits for the next event.
            requestTimeout = 0
            // Idle workers hold connections in GET /next; a lower limit would queue results behind them.
            maxConnectionsCount = maxConnections
            this.endpoint.maxConnectionsPerRoute = maxConnections
        }
    }

    override suspend fun next(): NextInvocation {
        val response = client.get("$baseUrl/invocation/next")
        if (response.status != HttpStatusCode.OK) throw rejected(response, "GET /next")
        val headers = response.headers
        val requestId = headers["Lambda-Runtime-Aws-Request-Id"]
            ?: throw RuntimeApiException(response.status.value, fatal = true, "GET /next returned no request id")
        return NextInvocation(
            invocation = LambdaInvocation(
                requestId = requestId,
                invocationId = headers["Lambda-Runtime-Invocation-Id"],
                deadlineEpochMillis = headers["Lambda-Runtime-Deadline-Ms"]?.toLongOrNull(),
                invokedFunctionArn = headers["Lambda-Runtime-Invoked-Function-Arn"],
                traceId = headers["Lambda-Runtime-Trace-Id"],
            ),
            event = response.bodyAsBytes(),
        )
    }

    override suspend fun respond(invocation: LambdaInvocation, body: ByteArray) {
        val response = client.post("$baseUrl/invocation/${invocation.requestId}/response") {
            invocation.invocationId?.let { header("Lambda-Runtime-Invocation-Id", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (response.status != HttpStatusCode.Accepted) throw rejected(response, "POST /response")
    }

    override suspend fun error(invocation: LambdaInvocation, error: LambdaError) {
        val response = client.post("$baseUrl/invocation/${invocation.requestId}/error") {
            invocation.invocationId?.let { header("Lambda-Runtime-Invocation-Id", it) }
            errorBody(error)
        }
        if (response.status != HttpStatusCode.Accepted) throw rejected(response, "POST /error")
    }

    override suspend fun initError(error: LambdaError) {
        val response = client.post("$baseUrl/init/error") { errorBody(error) }
        if (response.status != HttpStatusCode.Accepted) throw rejected(response, "POST /init/error")
    }

    override fun close() {
        client.close()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.errorBody(error: LambdaError) {
        header("Lambda-Runtime-Function-Error-Type", error.errorType)
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("errorMessage", error.errorMessage)
                put("errorType", error.errorType)
                put("stackTrace", JsonArray(error.stackTrace.map(::JsonPrimitive)))
            }.toString(),
        )
    }

    private suspend fun rejected(response: HttpResponse, call: String): RuntimeApiException {
        val status = response.status.value
        return RuntimeApiException(
            status,
            fatal = status >= HttpStatusCode.InternalServerError.value,
            "$call failed with $status: ${response.bodyAsText()}",
        )
    }
}

private const val DEFAULT_MAX_CONNECTIONS = 4
