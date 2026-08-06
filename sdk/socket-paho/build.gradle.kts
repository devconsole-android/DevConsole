plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android {
    namespace = "io.devconsole.socket.paho"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}

dependencies {
    implementation(project(":sdk:socket"))
    implementation(libs.paho.mqttv3)
}
