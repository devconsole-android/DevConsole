plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android {
    namespace = "io.devconsole.network.okhttp"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}

dependencies {
    implementation(project(":sdk:network"))
    // `api`, not `implementation`: MockEngine is named in installDevConsole's own signature (as the
    // type of its defaulted mockEngine parameter), so a consumer cannot resolve the call without it.
    api(project(":sdk:mocks"))
    implementation(project(":sdk:mocks-okhttp"))
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
}
