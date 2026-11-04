plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android {
    namespace = "io.devconsole.ui.views"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}

dependencies {
    api(project(":sdk:api"))
}
