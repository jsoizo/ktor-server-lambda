plugins {
    id("code-quality")
    id("org.jetbrains.kotlin.jvm")
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    explicitApi()
    jvmToolchain(21)
}
