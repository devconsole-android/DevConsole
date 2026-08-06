/**
 * @author Shakib
 * @since 03/08/26
 */
package io.devconsole.gradle

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "No-shake" for the no-op side: `DevConsoleVariantPolicyPluginFunctionalTest` proves that a
 * protected variant is *rejected* when the full runtime is reachable and *accepted* when only
 * no-op-shaped code is present, but that only exercises fixture projects built inside the test.
 * This is the complementary, much cheaper check on the real repository: the no-op modules a
 * release variant is meant to depend on (`sdk:noop` and the `-noop` capture adapters) must never
 * themselves declare a dependency on a full/enabled-side module. If one did, R8/proguard
 * shrinking would be the *only* thing keeping server/dashboard/capture code out of a protected
 * build -- the two-coordinate story (`releaseImplementation(project(":sdk:noop"))` etc., see
 * `samples/foundation-app/build.gradle.kts`) would already have failed to keep it out at the
 * dependency-graph level.
 *
 * Implemented as a plain text scan of each no-op module's `build.gradle.kts` for
 * `project(":sdk:...")` references, rather than a full Gradle/AGP dependency-resolution run: this
 * is a property of what the module *declares*, which is exactly what a `build.gradle.kts` review
 * would catch by eye, and it runs in milliseconds with no Android SDK/AGP fixture project needed
 * (unlike `DevConsoleVariantPolicyPluginFunctionalTest`, which requires `ANDROID_HOME`).
 */
class NoopRuntimeExcludesFullModulesTest {
    /** The no-op-side modules a protected build variant is meant to depend on instead of their full-side counterparts. */
    private val noopModules =
        listOf(
            "sdk/noop",
            "sdk/network-okhttp-noop",
            "sdk/mocks-okhttp-noop",
            "sdk/socket-okhttp-noop",
            "sdk/socket-paho-noop",
            "sdk/push-firebase-noop",
        )

    /**
     * Modules that carry server/dashboard/capture/UI code, or the full-side counterpart of a
     * no-op adapter. None of [noopModules] may declare a `project(...)` dependency on any of these.
     */
    private val fullSideModules =
        setOf(
            ":sdk:full",
            ":sdk:core",
            ":sdk:server-api",
            ":sdk:server-ktor",
            ":sdk:storage-room",
            ":sdk:composer",
            ":sdk:timeline",
            ":sdk:export",
            ":sdk:ui-compose",
            ":sdk:ui-views",
            ":sdk:push-firebase",
            ":sdk:network-okhttp",
            ":sdk:mocks-okhttp",
            ":sdk:socket-okhttp",
            ":sdk:socket-paho",
        )

    @Test
    fun `no no-op module declares a project dependency on a full-side module`() {
        val repoRoot = repoRoot()
        assertTrue("expected at least one no-op module to check", noopModules.isNotEmpty())

        noopModules.forEach { moduleRelativePath ->
            val buildScript = File(repoRoot, "$moduleRelativePath/build.gradle.kts")
            assertTrue("expected a build script at $moduleRelativePath/build.gradle.kts", buildScript.exists())

            val declaredProjectDependencies = projectDependencyPaths(buildScript.readText())
            val forbidden = declaredProjectDependencies intersect fullSideModules

            assertTrue(
                "$moduleRelativePath declares a dependency on full-side module(s) $forbidden -- a release " +
                    "build using this module would pull in server/dashboard/capture code that R8 shrinking " +
                    "is not guaranteed to strip, defeating the two-coordinate debug/release story",
                forbidden.isEmpty(),
            )
        }
    }

    /** Self-check: if the forbidden set were accidentally emptied, the test above would pass vacuously. */
    @Test
    fun `the full-side module list itself is non-empty and does not overlap the no-op modules under test`() {
        assertTrue(fullSideModules.isNotEmpty())
        val noopProjectPaths = noopModules.map { ":" + it.replace('/', ':') }.toSet()
        assertTrue(
            "the no-op modules under test must not themselves appear in the forbidden full-side list",
            (fullSideModules intersect noopProjectPaths).isEmpty(),
        )
    }

    private fun projectDependencyPaths(buildScriptText: String): Set<String> =
        Regex("""project\(\s*"(:sdk:[\w-]+)"\s*\)""")
            .findAll(buildScriptText)
            .map { it.groupValues[1] }
            .toSet()

    /**
     * `gradle-plugin` is itself an included build with its own `settings.gradle.kts`
     * (`rootProject.name = "devconsole-gradle-plugin"`), so the nearest `settings.gradle.kts`
     * walking up from this test's working directory is *not* the monorepo root the `sdk`
     * modules live under -- keep walking past it until the actual root project's settings file
     * is found.
     */
    private fun repoRoot(): File {
        var candidate = File(".").absoluteFile
        while (true) {
            val settingsFile = File(candidate, "settings.gradle.kts")
            if (settingsFile.exists() && settingsFile.readText().contains("rootProject.name = \"DevConsole\"")) {
                return candidate
            }
            candidate = candidate.parentFile ?: error("Could not locate the DevConsole repo root above ${File(".").absoluteFile}")
        }
    }
}
