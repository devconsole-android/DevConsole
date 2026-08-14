plugins {
    id("devconsole.kotlin.jvm")
    id("devconsole.quality")
    id("devconsole.publishing")
    alias(libs.plugins.dokka)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api(project(":sdk:network"))
    api(project(":sdk:socket"))
    api(project(":sdk:push"))
    api(project(":sdk:state"))
    api(project(":sdk:remote-config"))
    api(project(":sdk:security"))
    api(project(":sdk:mocks"))
    api(project(":sdk:logs"))
    compileOnly("com.google.android:android:4.1.1.4")
}
