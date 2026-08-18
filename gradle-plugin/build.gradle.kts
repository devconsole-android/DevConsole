plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("java-gradle-plugin")
    id("maven-publish")
    // Latest 1.x release: 2.x raises the minimum Gradle version this repo isn't ready to require yet.
    id("com.gradle.plugin-publish") version "1.3.1"
}

// Must share a top-level namespace with the plugin ID (a Plugin Portal requirement) and match
// the SDK's Maven group.
group = "io.github.devconsole-android"
version = "1.2.2"

// Targets Java 17: this plugin JAR runs inside the Gradle daemon (AGP 8.13.0
// requires JDK 17) and compiles against com.android.tools.build:gradle:8.13.0,
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
    compileOnly("com.android.tools.build:gradle:8.13.0")
    // org.gradle.kotlin.dsl.create / findByType extension functions live here;
    // the plain kotlin.jvm plugin doesn't pull this in the way build-logic's
    // `kotlin-dsl` plugin does.
    compileOnly(gradleKotlinDsl())
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.20")
    testImplementation(gradleTestKit())
}

gradlePlugin {
    // Required by com.gradle.plugin-publish for Plugin Portal submission; matches the repo URL used
    // in build-logic/convention-publishing's PublishingConventionPlugin (PROJECT_URL) for the SDK
    // Maven POMs.
    website.set("https://github.com/devconsole-android/DevConsole")
    vcsUrl.set("https://github.com/devconsole-android/DevConsole")
    plugins {
        register("devConsoleVariantPolicy") {
            // Namespaced under the GitHub-based Maven group so it can be published to the Gradle
            // Plugin Portal; `io.devconsole` would require owning that domain.
            id = "io.github.devconsole-android"
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
// omits compileOnly dependencies. Our plugin both references AGP's DSL types
// (ApplicationExtension/LibraryExtension) at load time and, at apply time,
// looks the `androidComponents` extensions up by type
// (getByType<LibraryAndroidComponentsExtension>()). For that lookup to match,
// the plugin and AGP must resolve the SAME Class object for those interfaces.
//
// The full AGP runtime is injected here and the functional-test fixtures apply
// `com.android.library`/`com.android.application` WITHOUT a version, so AGP is
// loaded from this injected classpath rather than resolved fresh from a
// repository. That guarantees a single AGP classloader shared with the plugin
// under test -- the type-based extension lookups resolve against the exact
// interfaces AGP registered. (Injecting the full jar while ALSO letting the
// fixture resolve its own versioned `com.android.library` would instead trip
// AGP's "Using different versions of the Android Gradle plugin ... not allowed"
// guard, which is why the fixtures must omit the version.)
val agpFunctionalTestRuntime: Configuration by configurations.creating
dependencies {
    agpFunctionalTestRuntime("com.android.tools.build:gradle:8.13.0")
}
tasks.named<org.gradle.plugin.devel.tasks.PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(agpFunctionalTestRuntime)
}
