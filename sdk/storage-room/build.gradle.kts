plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android {
    namespace = "io.devconsole.storage.room"
    defaultConfig {
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += "room.schemaLocation" to "$projectDir/schemas"
            }
        }
    }
    sourceSets {
        // MigrationTestHelper resolves each version's exported schema JSON from test assets.
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }
}

dependencies {
    implementation(project(":sdk:storage-api"))
    api(project(":sdk:timeline"))
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    annotationProcessor("androidx.room:room-compiler:2.7.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
