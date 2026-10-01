plugins {
    id("jvm-app")
    `jvm-test-suite`
}

// The samples run in the official Lambda base images for the host's architecture, unless -Prie.arch picks
// the other one (run under emulation, as CI does for arm64).
val arch = providers.gradleProperty("rie.arch")
    .orElse(providers.systemProperty("os.arch"))
    .map { if (it == "arm64" || it == "aarch64") "arm64" else "amd64" }
val nativeTarget = arch.map { if (it == "arm64") "LinuxArm64" else "LinuxX64" }

testing {
    suites {
        // Needs Docker, so it is kept out of `check`; run it with `./gradlew :integration-test:integrationTest`.
        register<JvmTestSuite>("integrationTest") {
            useKotlinTest(libs.versions.kotlin)
            dependencies {
                implementation(libs.testcontainers)
                implementation(libs.kotlinx.serialization.json)
            }
            targets.all {
                testTask.configure {
                    // Task paths must be plain strings: Gradle does not resolve a Provider<String> as a task path.
                    dependsOn(
                        ":native-hello:bootstrapZip${nativeTarget.get()}",
                        ":jvm-runtime:installDist",
                        ":jvm-managed:lambdaZip",
                    )
                    systemProperty("samples.dir", layout.settingsDirectory.dir("samples").asFile.path)
                    systemProperty("rie.arch", arch.get())
                    // The containers are the thing under test; a cached result would skip them.
                    outputs.upToDateWhen { false }
                }
            }
        }
    }
}
