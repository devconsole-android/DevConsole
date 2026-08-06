plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android {
    namespace = "io.devconsole.socket.okhttp"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}

dependencies {
    implementation(project(":sdk:socket"))
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
}
