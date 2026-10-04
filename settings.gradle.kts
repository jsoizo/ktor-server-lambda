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
        if (providers.gradleProperty("usePublishedSnapshot").isPresent) {
            maven("https://central.sonatype.com/repository/maven-snapshots/") {
                mavenContent { snapshotsOnly() }
            }
        }
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
include(":jvm-managed")
project(":jvm-managed").projectDir = file("samples/jvm-managed")
include(":integration-test")
listOf("routes", "native", "jvm-managed").forEach { name ->
    include(":fixture-$name")
    project(":fixture-$name").projectDir = file("integration-test/fixtures/$name")
}
