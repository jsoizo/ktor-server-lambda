import java.time.Duration
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// Only the host's architecture runs; the other one would need emulation, which is slow.
val arm64Host = providers.systemProperty("os.arch").get() in setOf("aarch64", "arm64")
val hostTarget = if (arm64Host) "linuxArm64" else "linuxX64"
val testSourceSets = listOf("commonTest", "nativeTest", "linuxTest", "${hostTarget}Test")
val hasTests = testSourceSets.any { layout.projectDirectory.dir("src/$it").asFile.isDirectory }

// Kotlin's test tasks only run on a Linux host; nativeContainerTest replaces them on every host.
tasks.withType<KotlinNativeTest>().configureEach { enabled = false }

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        val target = name
        binaries.withType<TestExecutable>().configureEach {
            // The tests run on provided.al2023, which has no libcrypt.so.1.
            linkerOpts("--as-needed", "-Bstatic", "-lcrypt", "-Bdynamic")
            // The disabled test tasks still pull in their link task; skip linking a binary that never runs.
            if (target != hostTarget || !hasTests) linkTaskProvider.configure { enabled = false }
        }
    }
    targets.withType<KotlinNativeTarget>().matching { it.name == hostTarget && hasTests }.configureEach {
        binaries.withType<TestExecutable>().matching { it.name == "debugTest" }.configureEach {
            val link = linkTaskProvider
            val test = tasks.register<NativeContainerTest>("nativeContainerTest") {
                group = "verification"
                timeout.set(Duration.ofMinutes(15))
                description = "Runs the $hostTarget tests in the provided.al2023 Lambda image."
                // The output file provider carries no task dependency, so without this a stale binary gets tested.
                dependsOn(link)
                executable.set(link.flatMap { it.outputFile }.let { layout.file(it) })
                image.set("public.ecr.aws/lambda/provided:al2023")
                platform.set(if (arm64Host) "linux/arm64" else "linux/amd64")
                results.set(layout.buildDirectory.dir("test-results/nativeContainerTest"))
            }
            tasks.named("check") { dependsOn(test) }
            tasks.named("allTests") { dependsOn(test) }
        }
    }
}
