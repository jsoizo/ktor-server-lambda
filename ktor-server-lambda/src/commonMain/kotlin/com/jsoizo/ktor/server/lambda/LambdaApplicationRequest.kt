package com.jsoizo.ktor.server.lambda

import com.jsoizo.ktor.server.lambda.events.LambdaHttpRequest
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.RequestConnectionPoint
import io.ktor.http.headers
import io.ktor.http.parseQueryString
import io.ktor.http.withEmptyStringForValuelessKeys
import io.ktor.server.engine.BaseApplicationRequest
import io.ktor.server.request.RequestCookies
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI

internal class LambdaApplicationRequest(call: LambdaApplicationCall, private val lambdaRequest: LambdaHttpRequest) :
    BaseApplicationRequest(call) {
    override val engineHeaders: Headers = headers {
        lambdaRequest.headers.forEach { (name, value) -> append(name, value) }
    }

    override val engineReceiveChannel: ByteReadChannel = ByteReadChannel(lambdaRequest.body)

    override val rawQueryParameters: Parameters by lazy(LazyThreadSafetyMode.NONE) {
        parseQueryString(lambdaRequest.rawQuery, decode = false)
    }

    @OptIn(InternalAPI::class)
    override val queryParameters: Parameters by lazy(LazyThreadSafetyMode.NONE) {
        parseQueryString(lambdaRequest.rawQuery, decode = true).withEmptyStringForValuelessKeys()
    }

    override val cookies: RequestCookies = RequestCookies(this)

    override val local: RequestConnectionPoint = LambdaConnectionPoint(lambdaRequest)

    private class LambdaConnectionPoint(private val request: LambdaHttpRequest) : RequestConnectionPoint {
        private val defaultPort = if (request.scheme == "http") 80 else 443
        private val hostHeader = request.headers.firstOrNull { it.first.equals(HttpHeaders.Host, ignoreCase = true) }?.second

        override val scheme: String get() = request.scheme
        override val version: String get() = "HTTP/1.1"
        override val uri: String
            get() = if (request.rawQuery.isEmpty()) request.path else "${request.path}?${request.rawQuery}"
        override val method: HttpMethod get() = HttpMethod.parse(request.method)

        override val localHost: String get() = request.host?.let(::withoutPort) ?: "localhost"
        override val localPort: Int get() = request.port ?: defaultPort
        override val localAddress: String get() = localHost
        override val serverHost: String get() = hostHeader?.let(::withoutPort) ?: localHost
        override val serverPort: Int get() = hostHeader?.let(::portOf) ?: localPort
        override val remoteHost: String get() = request.remoteAddress ?: "unknown"
        override val remotePort: Int get() = request.remotePort ?: 0
        override val remoteAddress: String get() = request.remoteAddress ?: "unknown"

        @Deprecated("Use localHost or serverHost instead", level = DeprecationLevel.ERROR)
        override val host: String get() = serverHost

        @Deprecated("Use localPort or serverPort instead", level = DeprecationLevel.ERROR)
        override val port: Int get() = serverPort

        override fun toString(): String = "LambdaConnectionPoint(uri=$uri, method=$method, remoteAddress=$remoteAddress)"
    }
}

// Host may be an IPv6 literal such as `[::1]:8080`, whose address itself contains colons.
private fun withoutPort(host: String): String = if (host.startsWith('[')) host.substringBefore(']') + "]" else host.substringBefore(':')

private fun portOf(host: String): Int? = host.substringAfter(if (host.startsWith('[')) "]:" else ":", "").toIntOrNull()
