plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    listOf(linuxX64(), linuxArm64()).forEach { target ->
        target.binaries.executable {
            entryPoint = "main"
            baseName = "bootstrap"
            // The AL2023 Lambda environment has no libcrypt.so.1.
            linkerOpts("--as-needed", "-Bstatic", "-lcrypt", "-Bdynamic")
        }
    }
}
