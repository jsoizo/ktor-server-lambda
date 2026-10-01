plugins {
    id("kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            implementation(libs.ktor.http)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
