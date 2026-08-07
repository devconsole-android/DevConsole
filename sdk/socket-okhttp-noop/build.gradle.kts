plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android { namespace = "io.devconsole.socket.okhttp.noop" }

dependencies {
    implementation(project(":sdk:socket"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
