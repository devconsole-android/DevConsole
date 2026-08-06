plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.dokka)
    // Loaded (not applied) here so every sdk module shares one classloader copy of the plugin —
    // its MavenCentralBuildService is a shared build service, and per-module loading puts the
    // service class in sibling classloaders that cannot exchange it.
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

// Every module under sdk/ is published to Maven, so every one should carry a committed ABI, and
// only the sample apps are exempt here. In practice the binary-compatibility-validator only emits
// dumps for the Kotlin/JVM modules: it detects targets via the Kotlin Gradle plugin, which AGP 9
// no longer applies separately for Android libraries, so it skips them silently rather than
// failing. That leaves 8 of 25 modules gated -- including, notably, not the `DevConsole` facade in
// sdk:full. Removing a module from this list is therefore not enough to guarantee it is covered;
// check that a matching sdk/<module>/api/<module>.api file actually exists.
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
