plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android {
    namespace = "io.devconsole.logs.timber"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":sdk:logs"))
    api("com.jakewharton.timber:timber:5.0.1")
}
