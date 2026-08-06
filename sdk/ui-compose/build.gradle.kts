plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.devconsole.ui.compose"
    buildFeatures { compose = true }
}

dependencies {
    api(project(":sdk:api"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // FileProvider, for the Files screen's Share action (androidx.core.content.FileProvider).
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(libs.kotlinx.coroutines.test)
}
