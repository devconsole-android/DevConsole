plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android { namespace = "io.devconsole.server.api" }

dependencies {
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
