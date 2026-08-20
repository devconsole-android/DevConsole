plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android {
    namespace = "io.devconsole.noop"

    sourceSets {
        getByName("main") {
            kotlin.directories += "../facade-shared/src/main/kotlin"
        }
    }
}

dependencies {
    api(project(":sdk:api"))
    // Mirrors sdk:full's OkHttp adapter re-exports (see sdk/full/build.gradle.kts) so a host using
    // the documented debugImplementation(full) / releaseImplementation(noop) pattern keeps
    // resolving adapter classes -- constructed in debug against sdk:full's real adapters -- in a
    // release build too, instead of hitting Unresolved reference.
    api(project(":sdk:network-okhttp-noop"))
    api(project(":sdk:mocks-okhttp-noop"))
    api(project(":sdk:socket-okhttp-noop"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
