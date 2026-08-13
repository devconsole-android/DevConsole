plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}
android { namespace = "io.devconsole.composer" }

dependencies {
    implementation(project(":sdk:security"))
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}
