plugins {
    id("kmp-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.ktorServerLambda)
        }
    }
}
