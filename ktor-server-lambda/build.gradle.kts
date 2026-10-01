plugins {
    id("kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.ktorServerLambdaEvents)
            api(libs.ktor.server.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

mavenPublishing {
    pom {
        name.set("ktor-server-lambda")
        description.set("Ktor server engine that runs the pipeline once per AWS Lambda invocation.")
    }
}
