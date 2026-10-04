package com.jsoizo.ktor.server.lambda.runtime

import io.ktor.events.Events
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.engine.ApplicationEngineFactory

/** Engine factory that serves invocations from the Lambda Runtime API until the process ends. */
public object AwsLambda : ApplicationEngineFactory<LambdaRuntimeEngine, LambdaRuntimeEngine.Configuration> {
    override fun configuration(configure: LambdaRuntimeEngine.Configuration.() -> Unit): LambdaRuntimeEngine.Configuration =
        LambdaRuntimeEngine.Configuration().apply(configure)

    override fun create(
        environment: ApplicationEnvironment,
        monitor: Events,
        developmentMode: Boolean,
        configuration: LambdaRuntimeEngine.Configuration,
        applicationProvider: () -> Application,
    ): LambdaRuntimeEngine = LambdaRuntimeEngine(environment, monitor, configuration, applicationProvider)
}
