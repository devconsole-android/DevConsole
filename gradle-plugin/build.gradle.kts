plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("java-gradle-plugin")
    id("maven-publish")
    // Latest 1.x release: 2.x raises the minimum Gradle version this repo isn't ready to require yet.
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "io.devconsole"
version = "0.1.0-SNAPSHOT"

// Targets Java 17: this plugin JAR runs inside the Gradle daemon (Gradle 9.5
// requires JDK 17) and compiles against com.android.tools.build:gradle:9.3.0,
// whose published variants require org.gradle.jvm.version = 17. This is
// independent of the Android product modules' JVM 11 bytecode floor.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly("com.android.tools.build:gradle:9.3.0")
    // org.gradle.kotlin.dsl.create / findByType extension functions live here;
    // the plain kotlin.jvm plugin doesn't pull this in the way build-logic's
    // `kotlin-dsl` plugin does.
    compileOnly(gradleKotlinDsl())
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.10")
    testImplementation(gradleTestKit())
}

gradlePlugin {
    // Required by com.gradle.plugin-publish for Plugin Portal submission; matches the repo URL used
    // in build-logic/convention-publishing's PublishingConventionPlugin (PROJECT_URL) for the SDK
    // Maven POMs.
    website.set("https://github.com/Shakibuzzaman3104/DevConsole")
    vcsUrl.set("https://github.com/Shakibuzzaman3104/DevConsole")
    plugins {
        register("devConsoleVariantPolicy") {
            // Namespaced under the GitHub-based Maven group so it can be published to the Gradle
            // Plugin Portal; `io.devconsole` would require owning that domain.
            id = "io.github.shakibuzzaman3104.android"
            implementationClass = "io.devconsole.gradle.DevConsoleVariantPolicyPlugin"
            displayName = "DevConsole Variant Policy"
            description =
                "Verifies the DevConsole in-app debugging runtime (network inspector, mock " +
                    "responses, capture dashboard) never reaches a protected release build variant " +
                    "-- checking declared dependencies, the resolved transitive runtime classpath, " +
                    "and the final packaged APK/AAB/AAR bytes."
            tags.set(listOf("android", "debugging", "devtools", "network-inspector"))
        }
    }
}

// GradleRunner.withPluginClasspath() derives its classpath from
// pluginUnderTestMetadata, which defaults to runtimeClasspath and therefore
// omits compileOnly dependencies. Our plugin references AGP's DSL types
// (ApplicationExtension/LibraryExtension), so those classes must be added
// explicitly or the functional-test fixture fails with
// "Type com.android.build.api.dsl.ApplicationExtension not present".
//
// Only the narrow `gradle-api` artifact is added (not the full
// `com.android.tools.build:gradle` implementation jar pulled in by
// compileOnly): the full jar ships META-INF/gradle-plugins plugin-marker
// files, and injecting it alongside the fixture's own normally-resolved
// `com.android.library` plugin makes AGP think two different copies of
// itself are active in the same build, which it refuses to run under
// ("Using different versions of the Android Gradle plugin ... not
// allowed"). gradle-api ships only the DSL interfaces we reference, with no
// plugin registrations, so it doesn't trigger that guard.
val agpDslApi: Configuration by configurations.creating
dependencies {
    agpDslApi("com.android.tools.build:gradle-api:9.3.0")
}
tasks.named<org.gradle.plugin.devel.tasks.PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(agpDslApi)
}
