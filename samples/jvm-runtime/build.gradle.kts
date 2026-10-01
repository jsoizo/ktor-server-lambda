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
}
