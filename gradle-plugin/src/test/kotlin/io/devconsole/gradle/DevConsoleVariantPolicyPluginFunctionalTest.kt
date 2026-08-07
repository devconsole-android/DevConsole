/**
 * @author Shakib
 * @since 19/07/26
 */
package io.devconsole.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DevConsoleVariantPolicyPluginFunctionalTest {
    @get:Rule
    val projectDir = TemporaryFolder()

    private fun androidSdkDir(): String =
        System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("Set ANDROID_HOME or ANDROID_SDK_ROOT to run this functional test.")

    private fun writeFixture(
        devConsoleBlock: String,
        extraBuildScript: String = "",
        androidPlugin: String = "com.android.library",
        applicationIdLine: String = "",
        androidBlock: String = "",
        /**
         * When false, lists io.github.devconsole-android *before* the Android plugin in the
         * `plugins {}` block, reproducing the misordering that used to silently disable the deep
         * transitive-classpath and packaged-artifact checks (see the "applied before" functional tests).
         */
        androidPluginFirst: Boolean = true,
    ) {
        File(projectDir.root, "local.properties")
            .writeText("sdk.dir=${androidSdkDir().replace("\\", "\\\\")}\n")
        File(projectDir.root, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories { google(); mavenCentral(); gradlePluginPortal() }
            }
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
            }
            rootProject.name = "fixture"
            """.trimIndent(),
        )
        File(projectDir.root, "src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }
            .writeText("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />")
        // No version on the Android plugin: it resolves from the injected plugin-under-test
        // classpath (see gradle-plugin/build.gradle.kts), keeping a single AGP classloader shared
        // with the plugin under test so its type-based androidComponents lookups match.
        val pluginsBlock =
            if (androidPluginFirst) {
                """
                id("$androidPlugin")
                id("io.github.devconsole-android")
                """.trimIndent()
            } else {
                """
                id("io.github.devconsole-android")
                id("$androidPlugin")
                """.trimIndent()
            }
        File(projectDir.root, "build.gradle.kts").writeText(
            """
            plugins {
                $pluginsBlock
            }
            android {
                namespace = "io.devconsole.fixture"
                compileSdk = 35
                defaultConfig {
                    minSdk = 23
                    $applicationIdLine
                }
                $androidBlock
            }
            $extraBuildScript
            devConsole {
                $devConsoleBlock
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
        // AGP 8.13.0 requires Gradle 8.13+ and is not compatible with Gradle 9.x, so pin the TestKit
        // daemon to 8.14.3 (the repo's own wrapper version) rather than inheriting any 9.x Gradle.
        .withGradleVersion("8.14.3")
        .withProjectDir(projectDir.root)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")

    private fun report(): String = File(projectDir.root, "build/reports/devconsole/variants.json").readText()

    /**
     * Adds a local `:stub-full` library standing in for the full runtime the policy must exclude.
     * [extraBuildTypes] lets a caller publish a matching build type (e.g. "staging") when the
     * consuming fixture depends on `:stub-full` from a non-default build type -- otherwise variant
     * matching cannot find a compatible `...RuntimeClasspath` variant to resolve against.
     */
    private fun addStubFullProject(extraBuildTypes: String = "") {
        File(projectDir.root, "settings.gradle.kts").appendText("\ninclude(\":stub-full\")\n")
        File(projectDir.root, "stub-full/src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }
            .writeText("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />")
        File(projectDir.root, "stub-full/build.gradle.kts").writeText(
            """
            plugins { id("com.android.library") }
            android {
                namespace = "io.devconsole.fixture.stubfull"
                compileSdk = 35
                defaultConfig { minSdk = 23 }
                $extraBuildTypes
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `zero-config defaults enable debug and protect release`() {
        writeFixture(devConsoleBlock = "")

        val result = runner("devConsoleVariantReport").build()

        assertTrue(result.output.contains("devConsoleVariantReport"))
        // The whole point of the plugin: safe by default without the host writing any policy.
        assertTrue(report().contains("\"debug\": \"ENABLED\""))
        assertTrue(report().contains("\"release\": \"PROTECTED\""))
    }

    @Test
    fun `release is protected by default so the full runtime is rejected without opt-in`() {
        writeFixture(
            devConsoleBlock = """protectedDependencyPaths.set(setOf(":stub-full"))""",
            extraBuildScript = """dependencies { add("releaseImplementation", project(":stub-full")) }""",
        )
        addStubFullProject()

        val result = runner("verifyDevConsoleProtectedArtifacts").buildAndFail()

        assertTrue(result.output.contains("Protected variants contain the full DevConsole runtime"))
        assertTrue(result.output.contains("release -> :stub-full"))
    }

    @Test
    fun `explicit allowlist enables the named variant`() {
        writeFixture(devConsoleBlock = """enabledVariants.set(setOf("debug"))""")

        runner("devConsoleVariantReport").build()

        assertTrue(report().contains("\"debug\": \"ENABLED\""))
    }

    @Test
    fun `protected variant depending on a configured full-runtime path fails verification`() {
        writeFixture(
            devConsoleBlock = """
                protectedVariantPatterns.set(listOf("release"))
                protectedDependencyPaths.set(setOf(":stub-full"))
            """.trimIndent(),
            extraBuildScript = """dependencies { add("releaseImplementation", project(":stub-full")) }""",
        )
        addStubFullProject()

        val result = runner("verifyDevConsoleProtectedArtifacts").buildAndFail()

        assertTrue(result.output.contains("Protected variants contain the full DevConsole runtime"))
        assertTrue(result.output.contains("release -> :stub-full"))
    }

    @Test
    fun `protected variant rejects an enabled capture adapter by default`() {
        writeFixture(
            devConsoleBlock = "autoWireDependencies.set(false)",
            extraBuildScript = """dependencies { add("releaseImplementation", project(":sdk:network-okhttp")) }""",
        )
        File(projectDir.root, "settings.gradle.kts").appendText("\ninclude(\":sdk:network-okhttp\")\n")
        File(projectDir.root, "sdk/network-okhttp/src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }
            .writeText("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />")
        File(projectDir.root, "sdk/network-okhttp/build.gradle.kts").writeText(
            """
            plugins { id("com.android.library") }
            android {
                namespace = "io.devconsole.fixture.network"
                compileSdk = 35
                defaultConfig { minSdk = 23 }
            }
            """.trimIndent(),
        )

        val result = runner("verifyDevConsoleProtectedArtifacts").buildAndFail()

        assertTrue(result.output.contains("release -> :sdk:network-okhttp"))
    }

    @Test
    fun `protected application rejects an enabled marker packaged in final dex`() {
        writeFixture(
            devConsoleBlock = "autoWireDependencies.set(false)",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
        )
        File(
            projectDir.root,
            "src/main/java/io/devconsole/internal/enabled/NetworkOkHttpCaptureMarker.java",
        ).apply { parentFile.mkdirs() }
            .writeText(
                """
                package io.devconsole.internal.enabled;
                public final class NetworkOkHttpCaptureMarker {
                    public static final String SIGNATURE = "DEVCONSOLE_ENABLED_NETWORK_OKHTTP_V1";
                }
                """.trimIndent(),
            )

        val result = runner("verifyDevConsoleProtectedArtifacts").buildAndFail()

        assertTrue(result.output, result.output.contains("release"))
        assertTrue(result.output, result.output.contains("DEVCONSOLE_ENABLED_NETWORK_OKHTTP_V1"))
    }

    @Test
    fun `protected application rejects dashboard assets in final package`() {
        writeFixture(
            devConsoleBlock = "autoWireDependencies.set(false)",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
        )
        File(projectDir.root, "src/main/assets/devconsole-web/index.html").apply { parentFile.mkdirs() }
            .writeText("<!doctype html><title>forbidden dashboard</title>")

        val result = runner("verifyDevConsoleProtectedArtifacts").buildAndFail()

        assertTrue(result.output, result.output.contains("release"))
        assertTrue(result.output, result.output.contains("assets/devconsole-web/"))
    }

    @Test
    fun `protected application rejects full runtime manifest components`() {
        writeFixture(
            devConsoleBlock = "autoWireDependencies.set(false)",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
        )
        File(projectDir.root, "src/main/AndroidManifest.xml").writeText(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <provider
                        android:name="io.devconsole.DevConsoleInitializer"
                        android:authorities="${'$'}{applicationId}.devconsoleinitializer"
                        android:exported="false" />
                </application>
            </manifest>
            """.trimIndent(),
        )

        val result = runner("verifyDevConsoleProtectedArtifacts").buildAndFail()

        assertTrue(result.output, result.output.contains("release"))
        assertTrue(result.output, result.output.contains("io.devconsole.DevConsoleInitializer"))
    }

    @Test
    fun `protected application accepts public no op adapter names without enabled markers`() {
        writeFixture(
            devConsoleBlock = "autoWireDependencies.set(false)",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
        )
        File(
            projectDir.root,
            "src/main/java/io/devconsole/network/okhttp/DevConsoleOkHttpInterceptor.java",
        ).apply { parentFile.mkdirs() }
            .writeText(
                """
                package io.devconsole.network.okhttp;
                public final class DevConsoleOkHttpInterceptor {
                    public static final String MODE = "disabled-build";
                }
                """.trimIndent(),
            )
        File(projectDir.root, "src/main/java/io/devconsole/PlatformFacadeProvider.java")
            .apply { parentFile.mkdirs() }
            .writeText(
                """
                package io.devconsole;
                final class PlatformFacadeProvider {
                    static final String MODE = "disabled-build";
                }
                """.trimIndent(),
            )

        val result = runner("verifyDevConsoleProtectedArtifacts").build()

        assertTrue(result.output, result.output.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun `transitive enabled adapter is rejected from protected runtime`() {
        writeFixture(
            devConsoleBlock = "autoWireDependencies.set(false)",
            extraBuildScript = """dependencies { add("releaseImplementation", project(":bridge")) }""",
        )
        File(projectDir.root, "settings.gradle.kts").appendText(
            "\ninclude(\":bridge\", \":sdk:network-okhttp\")\n",
        )
        listOf("bridge", "sdk/network-okhttp").forEach { path ->
            File(projectDir.root, "$path/src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }
                .writeText("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />")
            File(projectDir.root, "$path/build.gradle.kts").writeText(
                """
                plugins { id("com.android.library") }
                android {
                    namespace = "io.devconsole.fixture.${path.replace('/', '.').replace('-', '_')}"
                    compileSdk = 35
                    defaultConfig { minSdk = 23 }
                }
                """.trimIndent(),
            )
        }
        File(projectDir.root, "bridge/build.gradle.kts").appendText(
            "\ndependencies { add(\"api\", project(\":sdk:network-okhttp\")) }\n",
        )

        val result = runner("verifyDevConsoleProtectedArtifacts").buildAndFail()

        assertTrue(result.output, result.output.contains("release -> project :sdk:network-okhttp (resolved)"))
    }

    @Test
    fun `flavored production release is protected and scans its final package`() {
        writeFixture(
            // Protect-by-default means every variant not explicitly enabled is now PROTECTED --
            // for a flavored project the flavor-qualified debug variant ("productionDebug") must be
            // opted in by its full name, since it does not literally equal the "debug" convention.
            devConsoleBlock =
                """
                autoWireDependencies.set(false)
                enabledVariants.set(setOf("productionDebug"))
                """.trimIndent(),
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
            androidBlock =
                """
                flavorDimensions += "environment"
                productFlavors { create("production") { dimension = "environment" } }
                """.trimIndent(),
        )
        File(
            projectDir.root,
            "src/main/java/io/devconsole/internal/enabled/FullRuntimeMarker.java",
        ).apply { parentFile.mkdirs() }
            .writeText(
                """
                package io.devconsole.internal.enabled;
                public final class FullRuntimeMarker {
                    public static final String SIGNATURE = "DEVCONSOLE_ENABLED_FULL_V1";
                }
                """.trimIndent(),
            )

        val result = runner("verifyDevConsoleProtectedArtifacts").buildAndFail()

        assertTrue(result.output, result.output.contains("productionRelease"))
        assertTrue(result.output, result.output.contains("DEVCONSOLE_ENABLED_FULL_V1"))
    }

    @Test
    fun `unsafe package emits warning when failure is disabled`() {
        writeFixture(
            devConsoleBlock =
                """
                autoWireDependencies.set(false)
                failBuildOnUnsafeVariant.set(false)
                """.trimIndent(),
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
        )
        File(
            projectDir.root,
            "src/main/java/io/devconsole/internal/enabled/FullRuntimeMarker.java",
        ).apply { parentFile.mkdirs() }
            .writeText(
                """
                package io.devconsole.internal.enabled;
                public final class FullRuntimeMarker {
                    public static final String SIGNATURE = "DEVCONSOLE_ENABLED_FULL_V1";
                }
                """.trimIndent(),
            )

        val result = runner("verifyDevConsoleProtectedArtifacts").build()

        assertTrue(result.output, result.output.contains("DevConsole: Protected variant release"))
        assertTrue(result.output, result.output.contains("DEVCONSOLE_ENABLED_FULL_V1"))
    }

    @Test
    fun `assembleRelease fails a violating project, not just check`() {
        writeFixture(
            devConsoleBlock = "autoWireDependencies.set(false)",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
        )
        File(
            projectDir.root,
            "src/main/java/io/devconsole/internal/enabled/FullRuntimeMarker.java",
        ).apply { parentFile.mkdirs() }
            .writeText(
                """
                package io.devconsole.internal.enabled;
                public final class FullRuntimeMarker {
                    public static final String SIGNATURE = "DEVCONSOLE_ENABLED_FULL_V1";
                }
                """.trimIndent(),
            )

        // `./gradlew bundleRelease && upload` (or assembleRelease) never runs `check`, so the deep
        // verifier must be wired directly onto the protected variant's own assemble/bundle tasks --
        // building the protected artifact itself must run the checks, without ever naming
        // `verifyDevConsoleProtectedArtifacts` or `check` on the command line.
        val result = runner("assembleRelease").buildAndFail()

        // The packaged-artifact scan (a dependency of the aggregate verifier) is what actually fails
        // first here -- Gradle stops scheduling new tasks on failure, so the aggregate
        // `verifyDevConsoleProtectedArtifacts` task that depends on it never itself gets to run. Either
        // task name proves the DevConsole verification machinery executed as part of `assembleRelease`
        // with no `check`/`verifyDevConsoleProtectedArtifacts` named on the command line.
        assertTrue(
            result.output,
            result.output.contains(":verifyDevConsoleProtectedArtifacts") ||
                result.output.contains(":verifyReleaseDevConsolePackagedArtifact"),
        )
        assertTrue(result.output, result.output.contains("DEVCONSOLE_ENABLED_FULL_V1"))
    }

    @Test
    fun `bundleRelease fails a violating project too`() {
        writeFixture(
            devConsoleBlock =
                """
                autoWireDependencies.set(false)
                protectedDependencyPaths.set(setOf(":stub-full"))
                """.trimIndent(),
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
            extraBuildScript = """dependencies { add("releaseImplementation", project(":stub-full")) }""",
        )
        addStubFullProject()

        val result = runner("bundleRelease").buildAndFail()

        assertTrue(result.output, result.output.contains(":verifyDevConsoleProtectedArtifacts"))
        assertTrue(result.output, result.output.contains("release -> :stub-full"))
    }

    @Test
    fun `a release-signed staging build type is protected by default with no explicit config`() {
        writeFixture(
            devConsoleBlock = "autoWireDependencies.set(false)",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
            androidBlock =
                """
                buildTypes {
                    create("staging") { initWith(getByName("release")) }
                }
                """.trimIndent(),
        )

        val result = runner("devConsoleVariantReport").build()

        // The whole point of protect-by-default: a release-signed staging/beta/preprod build type
        // that is neither in enabledVariants nor matched by protectedVariantPatterns still gets
        // PROTECTED instead of silently falling through to DISABLED.
        assertTrue(report().contains("\"staging\": \"PROTECTED\""))
        assertTrue(report().contains("\"debug\": \"ENABLED\""))
        assertTrue(report().contains("\"release\": \"PROTECTED\""))
    }

    @Test
    fun `a protected staging variant depending on the full runtime fails verification by default`() {
        writeFixture(
            devConsoleBlock =
                """
                autoWireDependencies.set(false)
                protectedDependencyPaths.set(setOf(":stub-full"))
                """.trimIndent(),
            extraBuildScript = """dependencies { add("stagingImplementation", project(":stub-full")) }""",
            androidBlock =
                """
                buildTypes {
                    create("staging") { initWith(getByName("release")) }
                }
                """.trimIndent(),
        )
        addStubFullProject(
            extraBuildTypes = """buildTypes { create("staging") { initWith(getByName("release")) } }""",
        )

        val result = runner("verifyDevConsoleProtectedArtifacts").buildAndFail()

        assertTrue(result.output.contains("Protected variants contain the full DevConsole runtime"))
        assertTrue(result.output.contains("staging -> :stub-full"))
    }

    @Test
    fun `applying the plugin before the Android plugin fails loudly instead of silently skipping checks`() {
        writeFixture(
            devConsoleBlock = "",
            androidPluginFirst = false,
        )

        val result = runner("devConsoleVariantReport").buildAndFail()

        assertTrue(
            result.output,
            result.output.contains("applied before com.android.application") ||
                result.output.contains("observed no Android build variants"),
        )
    }

    @Test
    fun `applying the plugin before the Android plugin on a flavored project fails loudly`() {
        writeFixture(
            devConsoleBlock = "autoWireDependencies.set(false)",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
            androidBlock =
                """
                flavorDimensions += "environment"
                productFlavors { create("production") { dimension = "environment" } }
                """.trimIndent(),
            androidPluginFirst = false,
        )

        val result = runner("verifyDevConsoleProtectedArtifacts").buildAndFail()

        // Must fail loudly with an actionable message -- not silently succeed with zero verification,
        // which is what happened before this variant-name/runtime-classpath mismatch was hardened.
        assertTrue(
            result.output,
            result.output.contains("applied before com.android.application") ||
                result.output.contains("observed no Android build variants") ||
                result.output.contains("RuntimeClasspath' configuration"),
        )
    }

    @Test
    fun `flavored debug variant is enabled via its build type with zero extra configuration`() {
        writeFixture(
            // Fully zero-config: no enabledVariants, no autoWireDependencies override. This is
            // MUST-FIX 2a's whole point -- a flavored project's debug variant must be ENABLED without
            // the host enumerating "productionDebug" (or any other flavor's debug variant) by name.
            // AGP's own build-type name ("debug", read off the variant via onVariants) is what
            // enabledVariants' default of ["debug"] now matches against, not the flavor-qualified
            // variant name.
            devConsoleBlock = "",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
            androidBlock =
                """
                flavorDimensions += "environment"
                productFlavors { create("production") { dimension = "environment" } }
                """.trimIndent(),
        )

        val result = runner("devConsoleVariantReport").build()

        assertTrue(report().contains("\"productionDebug\": \"ENABLED\""))
        assertTrue(report().contains("\"productionRelease\": \"PROTECTED\""))
    }

    @Test
    fun `explicit enabledVariants entry still opts in an exact flavored variant by name`() {
        writeFixture(
            // "qa" is a custom build type, not part of the default enabledVariants=["debug"], so
            // "productionQa" can only become ENABLED via the explicit name below -- proving build-type
            // matching (MUST-FIX 2a) is additive on top of exact-name opt-in, not a replacement for it.
            devConsoleBlock = """enabledVariants.set(setOf("productionQa"))""",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
            androidBlock =
                """
                flavorDimensions += "environment"
                productFlavors { create("production") { dimension = "environment" } }
                buildTypes { create("qa") { initWith(getByName("debug")) } }
                """.trimIndent(),
        )

        val result = runner("devConsoleVariantReport").build()

        assertTrue(report().contains("\"productionQa\": \"ENABLED\""))
    }

    @Test
    fun `assembleProductionDebug succeeds under default configuration without duplicating the full runtime`() {
        writeFixture(
            devConsoleBlock = """protectedDependencyPaths.set(setOf(":stub-full"))""",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
            androidBlock =
                """
                flavorDimensions += "environment"
                productFlavors { create("production") { dimension = "environment" } }
                """.trimIndent(),
            // Mirrors how a host actually wires the real runtime today: on the build-type-only
            // configuration, not the flavor-qualified one.
            extraBuildScript = """dependencies { add("debugImplementation", project(":stub-full")) }""",
        )
        addStubFullProject()

        // Default configuration: autoWireDependencies stays on (the default) and no variant is named
        // explicitly. Two independent bugs used to break this:
        //   (2a) "productionDebug" resolved to PROTECTED (not ENABLED) because its name doesn't match
        //        "debug" or "(?i).*release" -- the protected-artifact verifier would then flag the
        //        host's own legitimate debugImplementation(":stub-full") as a violation and fail the
        //        build outright.
        //   (2b) even once ENABLED, alreadyDeclared only inspected productionDebugImplementation's
        //        *direct* dependencies, never walking extendsFrom to see debugImplementation's
        //        declaration -- so auto-wire added a second, redundant DevConsole artifact on top of
        //        the host's own, duplicating the full runtime's classes at dex time.
        val result = runner(
            "assembleProductionDebug",
            "dependencies",
            "--configuration",
            "productionDebugImplementation",
        ).build()

        assertTrue(result.output, result.output.contains("BUILD SUCCESSFUL"))
        assertTrue(result.output, result.output.contains(":stub-full"))
        assertTrue(result.output, !result.output.contains("io.github.devconsole-android:devconsole"))
    }

    @Test
    fun `assembleProductionRelease still fails when the full runtime leaks into it under default configuration`() {
        writeFixture(
            devConsoleBlock = """protectedDependencyPaths.set(setOf(":stub-full"))""",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
            androidBlock =
                """
                flavorDimensions += "environment"
                productFlavors { create("production") { dimension = "environment" } }
                """.trimIndent(),
            extraBuildScript = """dependencies { add("releaseImplementation", project(":stub-full")) }""",
        )
        addStubFullProject()

        // Zero-config: no explicit enabledVariants, autoWireDependencies left on. "productionRelease"
        // still matches protectedVariantPatterns' default "(?i).*release" and must fail exactly as
        // before -- MUST-FIX 2a only widens what resolves to ENABLED via build type, it must not also
        // widen what counts as protected.
        val result = runner("assembleProductionRelease").buildAndFail()

        // The declared-dependency scan only inspects productionReleaseImplementation's and
        // implementation's own dependencies (a pre-existing, narrower version of the same
        // sibling-configuration blind spot MUST-FIX 2b fixes for auto-wire) and so does not itself
        // name this violation for a dependency declared on the sibling "releaseImplementation" bucket
        // -- but the transitive runtime-classpath walk (already exercised by the
        // "flavored project deep transitive classpath check" test above) catches it regardless, which
        // is what actually fails this build.
        assertTrue(result.output, result.output.contains("productionRelease -> project :stub-full (resolved)"))
    }

    @Test
    fun `flavored project deep transitive classpath check catches the full runtime when applied in order`() {
        writeFixture(
            devConsoleBlock = "autoWireDependencies.set(false)",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
            androidBlock =
                """
                flavorDimensions += "environment"
                productFlavors { create("production") { dimension = "environment" } }
                """.trimIndent(),
            // The "productionRelease"-qualified configuration only exists once AGP has finished
            // creating flavor x build-type variant configurations, which happens after this build
            // script's top-level `dependencies {}` block runs -- unlike the always-present base
            // "release"/"debug" configurations, referencing it eagerly throws
            // UnknownConfigurationException, so defer to afterEvaluate.
            extraBuildScript =
                """
                afterEvaluate {
                    dependencies { add("productionReleaseImplementation", project(":bridge")) }
                }
                """.trimIndent(),
        )
        File(projectDir.root, "settings.gradle.kts").appendText(
            "\ninclude(\":bridge\", \":sdk:network-okhttp\")\n",
        )
        listOf("bridge", "sdk/network-okhttp").forEach { path ->
            File(projectDir.root, "$path/src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }
                .writeText("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />")
            File(projectDir.root, "$path/build.gradle.kts").writeText(
                """
                plugins { id("com.android.library") }
                android {
                    namespace = "io.devconsole.fixture.${path.replace('/', '.').replace('-', '_')}"
                    compileSdk = 35
                    defaultConfig { minSdk = 23 }
                }
                """.trimIndent(),
            )
        }
        File(projectDir.root, "bridge/build.gradle.kts").appendText(
            "\ndependencies { add(\"api\", project(\":sdk:network-okhttp\")) }\n",
        )

        val result = runner("verifyDevConsoleProtectedArtifacts").buildAndFail()

        // Exercises the same transitive-walk machinery as the non-flavored
        // "transitive enabled adapter is rejected" test, but on a variant whose name
        // ("productionRelease") does not equal its build type name ("release") -- the exact
        // mismatch that used to make `${variant}RuntimeClasspath` resolve to nothing and silently
        // drop this variant from the deep check.
        assertTrue(
            result.output,
            result.output.contains("productionRelease -> project :sdk:network-okhttp (resolved)"),
        )
    }

    @Test
    fun `a force-protect pattern overrides the build-type match for a flavor it is meant to protect`() {
        writeFixture(
            // A host force-protecting an entire "partner" white-label flavor: even though
            // "partnerDebug"'s build type ("debug") is in the zero-config enabledVariants default,
            // the explicit pattern must win -- otherwise a partner/white-label flavor silently gets
            // the real runtime auto-wired in and no protected verification at all (NEW-A).
            devConsoleBlock = """protectedVariantPatterns.set(listOf("(?i).*release", "(?i)partner.*"))""",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
            androidBlock =
                """
                flavorDimensions += "environment"
                productFlavors {
                    create("partner") { dimension = "environment" }
                    create("production") { dimension = "environment" }
                }
                """.trimIndent(),
        )

        // devConsoleVariantReport proves the resolved policy; "tasks --all" proves the effect on task
        // registration without needing to resolve the (unpublished-in-this-fixture) auto-wired
        // DevConsole coordinate -- registerPackagedArtifactScans only registers a
        // verify<Variant>DevConsolePackagedArtifact task for variants resolved PROTECTED.
        val result = runner("devConsoleVariantReport", "tasks", "--all").build()

        assertTrue(report().contains("\"partnerDebug\": \"PROTECTED\""))
        assertTrue(report().contains("\"partnerRelease\": \"PROTECTED\""))
        assertTrue(report().contains("\"productionDebug\": \"ENABLED\""))
        assertTrue(report().contains("\"productionRelease\": \"PROTECTED\""))

        // partnerDebug: not auto-wired the real runtime, and has a protected-artifact verifier
        // registered to catch it if it ever leaks in.
        assertTrue(result.output, result.output.contains("verifyPartnerDebugDevConsolePackagedArtifact"))
        // productionDebug stays ENABLED via the build-type fallback, so it gets no such verifier.
        assertTrue(result.output, !result.output.contains("verifyProductionDebugDevConsolePackagedArtifact"))
    }

    @Test
    fun `auto-wired coordinate resolves to the released non-snapshot version`() {
        writeFixture(
            devConsoleBlock = "",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
        )

        // release is PROTECTED by default, so auto-wire adds the noop core coordinate. The published
        // coordinate is 0.3.0 -- the DEFAULT_SDK_VERSION must not point at a 0.3.0-SNAPSHOT that was
        // never published, which would make every zero-config release build fail to resolve.
        val result = runner("dependencies", "--configuration", "releaseImplementation").build()

        assertTrue(result.output, result.output.contains("io.github.devconsole-android:devconsole-noop:0.3.0"))
        assertTrue(result.output, !result.output.contains("0.3.0-SNAPSHOT"))
    }

    @Test
    fun `dynamic-feature module fails loudly because the plugin does not protect it`() {
        writeFixture(
            devConsoleBlock = "",
            androidPlugin = "com.android.dynamic-feature",
        )

        val result = runner("devConsoleVariantReport").buildAndFail()

        // A dynamic-feature module has neither an ApplicationExtension nor a LibraryExtension, so it
        // used to silently fall through with zero protection and zero warning. It must now fail loudly.
        assertTrue(
            result.output,
            result.output.contains("does not protect") &&
                result.output.contains("dynamic-feature"),
        )
    }

    @Test
    fun `declaring only an add-on coordinate still auto-wires the core runtime`() {
        writeFixture(
            devConsoleBlock = "",
            androidPlugin = "com.android.application",
            applicationIdLine =
                """
                applicationId = "io.devconsole.fixture"
                versionCode = 1
                """.trimIndent(),
            // An add-on coordinate in the DevConsole group but NOT the core runtime. It must not be
            // mistaken for "the core runtime is already declared" and suppress core auto-wire.
            extraBuildScript =
                """
                dependencies {
                    add("debugImplementation", "io.github.devconsole-android:devconsole-ui-compose:0.3.0")
                }
                """.trimIndent(),
        )

        val result = runner("dependencies", "--configuration", "debugImplementation").build()

        // debug is ENABLED, so the core runtime ("devconsole") must still be auto-wired alongside the
        // host's add-on declaration -- the add-on alone does not count as declaring the core runtime.
        assertTrue(result.output, result.output.contains("io.github.devconsole-android:devconsole:0.3.0"))
        assertTrue(result.output, result.output.contains("io.github.devconsole-android:devconsole-ui-compose"))
    }
}
