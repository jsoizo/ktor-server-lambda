plugins {
    id("code-quality")
    id("org.jetbrains.kotlin.multiplatform")
    id("kmp-native-tests")
}

kotlin {
    jvmToolchain(21)
    jvm()
    linuxX64()
    linuxArm64()
}
