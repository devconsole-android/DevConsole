plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android { namespace = "io.devconsole.network.okhttp.noop" }

dependencies {
    implementation(project(":sdk:network"))
    // Mirrors the enabled module: MockEngine is named in installDevConsole's signature on both sides,
    // so it has to be visible to consumers here too or the release variant would not compile.
    api(project(":sdk:mocks"))
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
}
