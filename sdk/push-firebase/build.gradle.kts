plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}
android {
    namespace = "io.devconsole.push.firebase"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}
dependencies { implementation(project(":sdk:push")) }
