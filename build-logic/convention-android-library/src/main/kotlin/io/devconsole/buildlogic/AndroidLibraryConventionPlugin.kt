package io.devconsole.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
        pluginManager.apply("com.android.library")
        // AGP 8.x has no built-in Kotlin compilation (that was AGP 9's android.builtInKotlin), so the
        // Kotlin Android plugin must be applied explicitly for every Android module.
        pluginManager.apply("org.jetbrains.kotlin.android")
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
            compileSdk = 35
            defaultConfig {
                minSdk = 23
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
            // The release singleVariant publication (with sources) is registered by the Vanniktech
            // plugin via devconsole.publishing; registering it here too would collide.
        }
        // AGP 8.x no longer aligns the Kotlin jvmTarget to compileOptions automatically (that came
        // with AGP 9's built-in Kotlin), so match the Java 11 target explicitly to avoid the
        // "Inconsistent JVM-target compatibility" failure.
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        }
        dependencies.add("testImplementation", "junit:junit:4.13.2")
        }
    }
}
