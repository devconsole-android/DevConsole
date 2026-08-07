package io.devconsole.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
        pluginManager.apply("com.android.application")
        // AGP 8.x has no built-in Kotlin compilation (that was AGP 9's android.builtInKotlin), so the
        // Kotlin Android plugin must be applied explicitly for every Android module.
        pluginManager.apply("org.jetbrains.kotlin.android")
        extensions.configure<ApplicationExtension> {
            compileSdk = 35
            defaultConfig {
                minSdk = 23
                targetSdk = 35
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
        }
        // AGP 8.x no longer aligns the Kotlin jvmTarget to compileOptions automatically, so match
        // the Java 11 target explicitly to avoid the "Inconsistent JVM-target compatibility" failure.
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        }
        dependencies.add("testImplementation", "junit:junit:4.13.2")
        }
    }
}
