plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android {
    namespace = "io.devconsole.network.ktor"
}

dependencies {
    api(project(":sdk:network"))
    implementation("io.ktor:ktor-client-core:3.5.1")
    testImplementation("io.ktor:ktor-client-mock:3.5.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
