plugins {
    id("kmp-native-app")
}

kotlin {
    sourceSets {
        nativeMain.dependencies {
            implementation(projects.ktorServerLambdaRuntime)
            // Serves the same module locally when not running on Lambda.
            implementation(libs.ktor.server.cio)
        }
    }
}
