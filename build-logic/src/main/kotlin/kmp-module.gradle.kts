plugins {
    id("code-quality")
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvmToolchain(21)
    jvm()
    linuxX64()
    linuxArm64()
}
