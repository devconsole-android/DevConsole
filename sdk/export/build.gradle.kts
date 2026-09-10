plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android { namespace = "io.devconsole.export" }

dependencies {
    api(project(":sdk:timeline"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation(project(":sdk:security"))
    implementation(project(":sdk:storage-api"))
}
