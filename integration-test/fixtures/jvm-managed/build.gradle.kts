plugins {
    id("jvm-managed-app")
}

dependencies {
    implementation(projects.fixtureRoutes)
    implementation(projects.ktorServerLambdaHandler)
    runtimeOnly(libs.slf4j.simple)
}
