package io.devconsole.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.gradle.kotlin.dsl.configure

class KotlinJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }
        dependencies.add("testImplementation", "org.jetbrains.kotlin:kotlin-test-junit:2.2.20")
        }
    }
}
