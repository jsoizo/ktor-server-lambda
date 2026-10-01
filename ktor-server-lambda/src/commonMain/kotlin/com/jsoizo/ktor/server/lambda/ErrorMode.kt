package com.jsoizo.ktor.server.lambda

public enum class ErrorMode {
    /** Let Ktor's default failure handling answer with HTTP 500. */
    HttpResponse,

    /** Rethrow out of the engine so the invocation is reported as a Lambda error. */
    LambdaError,
}
