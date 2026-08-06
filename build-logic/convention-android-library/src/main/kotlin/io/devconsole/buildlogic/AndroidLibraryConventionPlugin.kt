package io.devconsole.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
        pluginManager.apply("com.android.library")
        // Every Android library module gets the Dokka plugin so it can expose Dokka's own
        // outgoing configuration; the root project's `dependencies { dokka(project(...)) }`
        // aggregation (see the root build.gradle.kts) depends on that configuration existing on
        // sdk:full and sdk:noop specifically -- without it, aggregation falls back to resolving
        // the module's ambiguous AGP runtime variants instead. Applying it here rather than only
        // on those two modules keeps the convention uniform and matches how devconsole.publishing
        // is applied to every published module regardless of whether each one is individually
        // aggregated today.
        pluginManager.apply("org.jetbrains.dokka")
        extensions.configure<LibraryExtension> {
            compileSdk = 37
            defaultConfig {
                minSdk = 24
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
            publishing {
                // Sources come from the release variant; the javadoc jar is added by the publishing
                // convention (a minimal placeholder, so this stays off the Dokka toolchain per module).
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }
        dependencies.add("testImplementation", "junit:junit:4.13.2")
        }
    }
}
