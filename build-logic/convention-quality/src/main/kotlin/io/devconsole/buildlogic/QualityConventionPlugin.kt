/**
 * @author Shakib
 * @since 19/07/26
 */
package io.devconsole.buildlogic

import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jlleitschuh.gradle.ktlint.KtlintExtension

class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            pluginManager.apply("io.gitlab.arturbosch.detekt")
            pluginManager.apply("jacoco")

            extensions.configure<KtlintExtension> {
                android.set(true)
            }
            extensions.configure<DetektExtension> {
                buildUponDefaultConfig = true
                autoCorrect = false
                // Supplements the defaults: exempts @Composable from the two size rules that
                // Compose cannot satisfy without hurting the code. See the file for the reasoning.
                config.setFrom(rootProject.file("config/detekt/detekt.yml"))
                // Modules had ~3000 pre-existing findings from before detekt was turned on.
                // The baseline freezes those as accepted debt so detekt only fails builds on
                // NEW findings introduced from here on; regenerate a module's baseline via
                // `./gradlew :module:detektBaseline` after intentionally cleaning it up.
                baseline = file("$projectDir/detekt-baseline.xml")
            }
            extensions.configure<JacocoPluginExtension> {
                toolVersion = "0.8.12"
            }

            // Android modules don't apply the `java` plugin, so the jacoco plugin's
            // automatic `jacocoTestReport` task (wired to `test`) never gets created.
            // Register the equivalent report task ourselves against the debug unit tests.
            val registerAndroidJacocoReport = {
                tasks.register<JacocoReport>("jacocoTestReport") {
                    group = "verification"
                    description = "Generates a code coverage report for the debug unit tests."
                    dependsOn("testDebugUnitTest")

                    reports {
                        xml.required.set(true)
                        html.required.set(true)
                    }

                    val fileFilter =
                        listOf(
                            "**/R.class",
                            "**/R$*.class",
                            "**/BuildConfig.*",
                            "**/Manifest*.*",
                            "**/*Test*.*",
                            "android/**/*.*",
                        )

                    // Class output locations differ between AGP's built-in kotlinc and the
                    // classic Kotlin Gradle plugin task graph; include both so the report
                    // works regardless of which one produced the compiled classes.
                    val classTrees =
                        listOf(
                            "$buildDir/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
                            "$buildDir/tmp/kotlin-classes/debug",
                            "$buildDir/intermediates/javac/debug/compileDebugJavaWithJavac/classes",
                        ).map { dir -> fileTree(dir) { exclude(fileFilter) } }

                    classDirectories.setFrom(files(classTrees))
                    sourceDirectories.setFrom(files("$projectDir/src/main/kotlin", "$projectDir/src/main/java"))
                    executionData.setFrom(fileTree(buildDir) { include("jacoco/testDebugUnitTest.exec") })
                }
                Unit
            }
            pluginManager.withPlugin("com.android.library") { registerAndroidJacocoReport() }
            pluginManager.withPlugin("com.android.application") { registerAndroidJacocoReport() }

            tasks.register("verifyQuality") {
                group = "verification"
                description = "Runs the module's standard verification tasks."
                dependsOn(tasks.matching { it.name == "check" })
                dependsOn(tasks.matching { it.name == "jacocoTestReport" })
            }
        }
    }
}
