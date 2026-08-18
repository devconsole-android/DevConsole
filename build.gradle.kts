plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.dokka)
    // Loaded (not applied) here so every sdk module shares one classloader copy of the plugin.
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

// Every module under sdk/ is published to Maven, so every one carries a committed ABI, and only the
// sample apps are exempt here. The binary-compatibility-validator detects targets via the Kotlin
// Gradle plugin; because the Android library/application convention plugins now apply
// `org.jetbrains.kotlin.android` explicitly (AGP 8.x has no built-in Kotlin), bcv sees a Kotlin
// target on every Android module and gates all of them -- including the `DevConsole` facade in
// sdk:full and sdk:noop -- not just the Kotlin/JVM modules. Each gated module therefore has a
// committed sdk/<module>/api/<module>.api baseline that `apiCheck` verifies against.
apiValidation {
    ignoredProjects.addAll(subprojects.filter { it.path.startsWith(":samples:") }.map { it.name })
}

// Aggregates the generated API reference for public API modules into one browsable site: ./gradlew dokkaGenerate
dependencies {
    dokka(project(":sdk:api"))
    dokka(project(":sdk:full"))
    dokka(project(":sdk:noop"))
}

tasks.register("verifyProjectStructure") {
    group = "verification"
    description = "Verifies that the documented Milestone 0 modules are present."
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
