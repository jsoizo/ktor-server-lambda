plugins {
    id("code-quality")
    id("org.jetbrains.kotlin.multiplatform")
    id("kmp-native-tests")
    id("published-library")
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    explicitApi()
    linuxX64()
    linuxArm64()
}
