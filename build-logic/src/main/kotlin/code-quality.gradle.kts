import dev.detekt.gradle.Detekt

plugins {
    id("com.diffplug.spotless")
    id("dev.detekt")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion
// Declared as an input so that changing it reruns ktlint instead of reusing cached results.
val editorConfig = layout.settingsDirectory.file(".editorconfig")

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(layout.settingsDirectory.file("config/detekt/detekt.yml"))
}

// On multiplatform projects the plain `detekt` task (run by `check`) analyses nothing; the per source set tasks do the work.
// JVM projects are left alone because their `detekt` task already covers the main sources.
pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    tasks.named("detekt") {
        dependsOn(tasks.withType<Detekt>().named { it.endsWith("SourceSet") })
    }
}
