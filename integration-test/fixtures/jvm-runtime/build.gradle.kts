plugins {
    id("jvm-runtime-app")
}

dependencies {
    implementation(projects.fixtureRoutes)
    implementation(projects.ktorServerLambdaRuntime)
    runtimeOnly(libs.slf4j.simple)
}
