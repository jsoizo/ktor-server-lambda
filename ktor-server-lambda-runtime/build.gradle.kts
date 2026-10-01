plugins {
    id("kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.ktorServerLambda)
            implementation(libs.ktor.client.cio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.server.cio)
        }
    }
}

mavenPublishing {
    pom {
        name.set("ktor-server-lambda-runtime")
        description.set(
            "AWS Lambda Runtime API client and invocation loop for the Ktor Lambda engine, for Kotlin/Native and JVM custom runtimes.",
        )
    }
}
