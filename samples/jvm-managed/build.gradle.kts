plugins {
    id("jvm-managed-app")
}

dependencies {
    if (providers.gradleProperty("usePublishedSnapshot").isPresent) {
        implementation("com.jsoizo:ktor-server-lambda-handler:0.1.0-SNAPSHOT")
    } else {
        implementation(projects.ktorServerLambdaHandler)
    }
    // Ktor logs through SLF4J; without a binding its warnings never reach CloudWatch.
    runtimeOnly(libs.slf4j.simple)
}
