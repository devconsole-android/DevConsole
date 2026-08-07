plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}
android {
    namespace = "io.devconsole.mocks.okhttp"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}
dependencies {
    implementation(project(":sdk:mocks"))
    implementation(project(":sdk:network"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
