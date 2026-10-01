rootProject.name = "ktor-server-lambda-root"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include(
    ":ktor-server-lambda-events",
    ":ktor-server-lambda",
    ":ktor-server-lambda-runtime",
    ":ktor-server-lambda-handler",
)

// Flat names avoid an empty intermediate `:samples` project.
include(":native-hello")
project(":native-hello").projectDir = file("samples/native-hello")
include(":jvm-runtime")
project(":jvm-runtime").projectDir = file("samples/jvm-runtime")
include(":jvm-managed")
project(":jvm-managed").projectDir = file("samples/jvm-managed")
include(":integration-test")
