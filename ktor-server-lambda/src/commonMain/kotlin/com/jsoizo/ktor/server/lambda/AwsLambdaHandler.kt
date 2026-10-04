package com.jsoizo.ktor.server.lambda

import io.ktor.events.Events
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.engine.ApplicationEngineFactory

/** Passive factory: runs no invocation loop; the caller drives [LambdaApplicationEngine.handle]. */
public object AwsLambdaHandler : ApplicationEngineFactory<LambdaApplicationEngine, LambdaApplicationEngine.Configuration> {
    override fun configuration(configure: LambdaApplicationEngine.Configuration.() -> Unit): LambdaApplicationEngine.Configuration =
        LambdaApplicationEngine.Configuration().apply(configure)

    override fun create(
        environment: ApplicationEnvironment,
        monitor: Events,
        developmentMode: Boolean,
        configuration: LambdaApplicationEngine.Configuration,
        applicationProvider: () -> Application,
    ): LambdaApplicationEngine = LambdaApplicationEngine(environment, monitor, configuration, applicationProvider)
}
