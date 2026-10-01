plugins {
    id("code-quality")
    id("published-library")
    id("org.jetbrains.kotlin.multiplatform")
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    explicitApi()
    jvmToolchain(21)

    jvm {
        // Built with JDK 21, but usable from JVM applications on 17 that drive the engine themselves.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }
    linuxX64()
    linuxArm64()
}
