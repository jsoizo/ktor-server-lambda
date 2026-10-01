plugins {
    id("jvm-app")
    application
}

application {
    mainClass.set("MainKt")
}

dependencies {
    implementation(projects.ktorServerLambdaRuntime)
    // Serves the same module locally when not running on Lambda.
    implementation(libs.ktor.server.cio)
    // Ktor logs through SLF4J; without a binding its warnings never reach CloudWatch.
    runtimeOnly(libs.slf4j.simple)
}
