plugins {
    id("kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            implementation(libs.ktor.http)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

mavenPublishing {
    pom {
        name.set("ktor-server-lambda-events")
        description.set("Codecs between AWS Lambda HTTP events (API Gateway, Function URL, ALB) and a normalized HTTP model.")
    }
}
