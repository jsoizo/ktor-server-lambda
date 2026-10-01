plugins {
    id("jvm-app")
}

dependencies {
    implementation(projects.ktorServerLambdaHandler)
}

// The layout the managed Java runtime expects: classes at the root, dependencies under lib/.
tasks.register<Zip>("lambdaZip") {
    group = "distribution"
    description = "Packages the handler for the managed java21 runtime."
    archiveFileName.set("jvm-managed.zip")
    destinationDirectory.set(layout.buildDirectory.dir("lambda"))
    from(sourceSets.main.map { it.output })
    from(configurations.named("runtimeClasspath")) { into("lib") }
}
