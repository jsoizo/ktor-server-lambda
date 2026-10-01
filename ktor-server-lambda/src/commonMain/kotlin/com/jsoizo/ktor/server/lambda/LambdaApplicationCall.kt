package com.jsoizo.ktor.server.lambda

import com.jsoizo.ktor.server.lambda.events.LambdaHttpRequest
import io.ktor.server.application.Application
import io.ktor.server.engine.BaseApplicationCall
import kotlin.coroutines.CoroutineContext

internal class LambdaApplicationCall(
    application: Application,
    lambdaRequest: LambdaHttpRequest,
    invocation: LambdaInvocation,
    override val coroutineContext: CoroutineContext,
) : BaseApplicationCall(application) {
    override val request: LambdaApplicationRequest = LambdaApplicationRequest(this, lambdaRequest)
    override val response: LambdaApplicationResponse = LambdaApplicationResponse(this)

    init {
        putResponseAttribute()
        attributes.put(LambdaCallContextKey, LambdaCallContext(invocation, lambdaRequest))
    }
}
