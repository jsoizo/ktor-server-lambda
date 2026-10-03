plugins {
    id("kmp-native-library")
}

kotlin {
    jvmToolchain(21)
    jvm {
        // Built with JDK 21, but usable from JVM applications on 17 that drive the engine themselves.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }
}
