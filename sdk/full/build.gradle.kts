plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android {
    namespace = "io.devconsole.full"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            kotlin.directories += "../facade-shared/src/main/kotlin"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":sdk:api"))
    implementation(project(":sdk:ui-compose"))
    // This module's own manifest declares an androidx.core.content.FileProvider, so the class has
    // to be a declared dependency rather than one inherited by luck through ui-compose's Compose
    // graph. A manifest-referenced provider whose class goes missing fails in the host app at
    // instantiation, not here.
    implementation("androidx.core:core-ktx:1.19.0")
    // OkHttp adapters stay on the umbrella: OkHttp (and Retrofit, which delegates to it) is the
    // integration the overwhelming majority of hosts need, so it belongs in the two-coordinate story.
    api(project(":sdk:network-okhttp"))
    api(project(":sdk:mocks-okhttp"))
    api(project(":sdk:socket-okhttp"))
    // ui-views, ui-compose, and push-firebase are deliberately NOT re-exported: they would drag the
    // Compose runtime and Firebase Messaging onto every consumer, used or not. Hosts that want the
    // launcher panel or the Firebase push adapter add that one coordinate themselves. Zero-config
    // startup logs the pairing URL, so neither UI module is needed for the common path.
    implementation(project(":sdk:core"))
    implementation(project(":sdk:composer"))
    implementation(project(":sdk:storage-api"))
    implementation(project(":sdk:storage-room"))
    implementation("androidx.room:room-runtime:2.8.4")
    implementation(project(":sdk:timeline"))
    implementation(project(":sdk:export"))
    implementation(project(":sdk:server-api"))
    implementation(project(":sdk:server-ktor"))
    implementation(project(":sdk:push"))
    implementation(project(":sdk:state"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
