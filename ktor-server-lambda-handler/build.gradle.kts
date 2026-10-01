plugins {
    id("jvm-library")
}

dependencies {
    api(projects.ktorServerLambda)
    api(libs.aws.lambda.java.core)
    api(libs.crac)
    testImplementation(kotlin("test"))
}
