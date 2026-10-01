import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    id("code-quality")
    id("org.jetbrains.kotlin.multiplatform")
}

// Libraries present in the provided.al2023 image.
val al2023Libraries = listOf(
    "libc.so.6",
    "libm.so.6",
    "libpthread.so.0",
    "libdl.so.2",
    "librt.so.1",
    "libgcc_s.so.1",
    "libz.so.1",
    "libresolv.so.2",
    "libutil.so.1",
    "ld-linux-*",
)

kotlin {
    listOf(linuxX64(), linuxArm64()).forEach { target ->
        target.binaries.executable {
            entryPoint = "main"
            baseName = "bootstrap"
            // The AL2023 Lambda environment has no libcrypt.so.1.
            linkerOpts("--as-needed", "-Bstatic", "-lcrypt", "-Bdynamic")
        }

        val release = target.binaries.getExecutable(NativeBuildType.RELEASE)
        val suffix = target.name.replaceFirstChar { it.uppercase() }
        val check = tasks.register<NeededLibrariesCheck>("checkNeededLibraries$suffix") {
            group = "verification"
            // The output file provider carries no task dependency, so without this a stale binary gets checked.
            dependsOn(release.linkTaskProvider)
            description = "Checks that the $suffix bootstrap only needs libraries available on provided.al2023."
            executable.set(release.linkTaskProvider.flatMap { it.outputFile }.let { layout.file(it) })
            allowed.set(al2023Libraries)
            report.set(layout.buildDirectory.file("reports/needed-libraries/${target.name}.txt"))
        }
        tasks.named("check") { dependsOn(check) }

        tasks.register<Zip>("bootstrapZip$suffix") {
            group = "distribution"
            description = "Packages the $suffix release executable as bootstrap for provided.al2023."
            dependsOn(check, release.linkTaskProvider)
            archiveFileName.set("bootstrap-${target.name}.zip")
            destinationDirectory.set(layout.buildDirectory.dir("lambda"))
            from(release.linkTaskProvider.flatMap { it.outputFile }) {
                rename { "bootstrap" }
                // Gradle 9 archives default to 0644, and Lambda can only execute bootstrap with the x bit.
                filePermissions { unix("0755") }
            }
        }
    }
}
