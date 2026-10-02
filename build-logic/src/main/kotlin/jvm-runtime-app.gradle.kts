plugins {
    id("jvm-app")
    application
}

// lambdaMain() lives in a top-level main() in Main.kt; the container image starts this class directly.
application {
    mainClass.set("MainKt")
}
