plugins {
    id("kmp-native-app")
}

kotlin {
    sourceSets {
        nativeMain.dependencies {
            implementation(projects.ktorServerLambdaRuntime)
        }
    }
}
