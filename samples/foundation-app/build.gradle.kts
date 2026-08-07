plugins {
    id("devconsole.android.application")
    id("devconsole.quality")
    id("io.github.devconsole-android")
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
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
}

// No devConsole {} override here: this sample has no product flavors, so the plugin's own
// zero-config defaults already do the right thing -- debug gets the real runtime, every other
// variant (including release) is PROTECTED (verified free of the full runtime, not just
// no-op-wired) and fails the build if it isn't. An explicit
// `protectedVariantPatterns.set(listOf("release"))` full-matches only the literal variant name
// "release" (Regex.matches is a full match), so a flavored release variant like "prodRelease"
// would silently miss it; the plugin's own default pattern, `(?i).*release`, does not have that
// gap. See docs/BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md.

android {
    namespace = "io.devconsole.sample"

    defaultConfig {
        applicationId = "io.devconsole.sample"
        versionCode = 1
        versionName = "0.1.0-SNAPSHOT"
    }
}
