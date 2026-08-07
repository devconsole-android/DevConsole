plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android { namespace = "io.devconsole.mocks.okhttp.noop" }

dependencies {
    implementation(project(":sdk:mocks"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
