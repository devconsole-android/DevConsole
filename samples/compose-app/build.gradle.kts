plugins {
    id("devconsole.android.application")
    id("devconsole.quality")
    id("io.github.devconsole-android")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.devconsole.sample.compose"
    buildFeatures { compose = true }

    defaultConfig {
        applicationId = "io.devconsole.sample.compose"
        versionCode = 1
        versionName = "0.1.0-SNAPSHOT"
    }
}

dependencies {
    debugImplementation(project(":sdk:full"))
    releaseImplementation(project(":sdk:noop"))
    debugImplementation(project(":sdk:network-okhttp"))
    releaseImplementation(project(":sdk:network-okhttp-noop"))
    debugImplementation(project(":sdk:mocks-okhttp"))
    releaseImplementation(project(":sdk:mocks-okhttp-noop"))
    debugImplementation(project(":sdk:socket-okhttp"))
    releaseImplementation(project(":sdk:socket-okhttp-noop"))
    debugImplementation(project(":sdk:socket-paho"))
    releaseImplementation(project(":sdk:socket-paho-noop"))
    // ui-compose has no release no-op counterpart, so it is debug-only; MainActivity never
    // references its types directly (see the local devConsoleStatusText() helper) so this stays
    // safe to flip without a release compile break.
    debugImplementation(project(":sdk:ui-compose"))
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation(libs.paho.mqttv3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
}

// No devConsole {} override here: this sample has no product flavors, so the plugin's own
// zero-config defaults already do the right thing -- debug gets the real runtime, every other
// variant (including release) is PROTECTED (verified free of the full runtime, not just
// no-op-wired) and fails the build if it isn't. An explicit
// `protectedVariantPatterns.set(listOf("release"))` full-matches only the literal variant name
// "release" (Regex.matches is a full match), so a flavored release variant like "prodRelease"
// would silently miss it; the plugin's own default pattern, `(?i).*release`, does not have that
// gap. See docs/BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md.
