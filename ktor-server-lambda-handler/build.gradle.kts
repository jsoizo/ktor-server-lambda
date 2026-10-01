plugins {
    id("jvm-library")
}

dependencies {
    api(projects.ktorServerLambda)
    api(libs.aws.lambda.java.core)
    api(libs.crac)
    testImplementation(kotlin("test"))
}

mavenPublishing {
    pom {
        name.set("ktor-server-lambda-handler")
        description.set("RequestStreamHandler for running Ktor on the managed AWS Lambda Java runtimes, with SnapStart priming.")
    }
}
