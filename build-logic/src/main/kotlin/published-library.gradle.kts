import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.AbiValidationExtension
import org.jetbrains.kotlin.gradle.dsl.abi.AbiValidationMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("com.vanniktech.maven.publish")
}

// Coordinates and shared POM fields come from gradle.properties (GROUP, VERSION_NAME, POM_*);
// each module sets its name and description in its own build script through `pom {}`.
mavenPublishing {
    publishToMavenCentral()
    // Signing is skipped for -SNAPSHOT versions and required for releases, so a release without a key fails
    // here rather than at Maven Central's validation after the upload.
    signAllPublications()
}

// Fail `check` when the public API changes without an updated dump (`./gradlew updateKotlinAbi`).
// The Kotlin plugin registers the extension on `kotlin {}`, so this script has no typed accessor for it.
pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    val kotlin = extensions.getByType<KotlinMultiplatformExtension>() as ExtensionAware
    @OptIn(ExperimentalAbiValidation::class)
    kotlin.extensions.getByType<AbiValidationMultiplatformExtension>().enabled.set(true)
}
pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    val kotlin = extensions.getByType<KotlinJvmProjectExtension>() as ExtensionAware
    @OptIn(ExperimentalAbiValidation::class)
    kotlin.extensions.getByType<AbiValidationExtension>().enabled.set(true)
}
