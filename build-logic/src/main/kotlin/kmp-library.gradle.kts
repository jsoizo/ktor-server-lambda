plugins {
    id("code-quality")
    id("org.jetbrains.kotlin.multiplatform")
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    explicitApi()
    jvmToolchain(21)

    jvm()
    linuxX64()
    linuxArm64()
}
