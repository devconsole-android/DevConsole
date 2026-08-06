/**
 * @author Shakib
 * @since 20/07/26
 */
package io.devconsole.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.plugins.signing.SigningExtension

/**
 * Applies to the SDK modules that carry a committed public API surface (see the root project's
 * `apiValidation` block). Wires `maven-publish` with the POM metadata, sources/javadoc jars, and
 * signature configuration Maven Central requires, so those modules are ready to publish once a
 * target repository and signing credentials are provided; does not itself publish anywhere.
 *
 * Signing is applied only when a key is present (`-PsigningInMemoryKey=...` or the matching
 * `ORG_GRADLE_PROJECT_signingInMemoryKey` env var), so a local `publishToMavenLocal` with no key
 * still succeeds while a real release stays signed.
 */
class PublishingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("maven-publish")
            pluginManager.apply("signing")
            group = MAVEN_GROUP
            version = SDK_VERSION

            // Kotlin/JVM modules publish the `java` component; the Android library convention wires
            // sources onto its `release` component. Register the JVM sources jar here to match.
            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                extensions.configure<JavaPluginExtension> {
                    withSourcesJar()
                }
            }

            // Maven Central requires a javadoc jar to exist. Every module that publishes also
            // carries the Dokka plugin (applied directly for Kotlin/JVM modules, or via
            // AndroidLibraryConventionPlugin for Android library modules), so pack that module's
            // own generated Dokka HTML into the jar -- the same browsable API reference the
            // aggregate site (`./gradlew dokkaGenerate`) assembles across modules, just scoped to
            // this one artifact. `withPlugin` fires whether Dokka was applied before or after this
            // plugin, so this stays correct regardless of plugin-block ordering in a module's
            // `build.gradle.kts`.
            val javadocJar =
                tasks.register<Jar>("devconsoleJavadocJar") {
                    archiveClassifier.set("javadoc")
                }
            pluginManager.withPlugin("org.jetbrains.dokka") {
                javadocJar.configure {
                    dependsOn("dokkaGeneratePublicationHtml")
                    from(layout.buildDirectory.dir("dokka/html"))
                }
            }

            afterEvaluate {
                val component = components.findByName("release") ?: components.findByName("java")
                if (component == null) return@afterEvaluate

                extensions.configure<PublishingExtension> {
                    publications {
                        create<MavenPublication>("maven") {
                            from(component)
                            artifact(javadocJar)
                            artifactId =
                                when (target.name) {
                                    "full" -> "devconsole"
                                    "noop" -> "devconsole-noop"
                                    else -> "devconsole-${target.name}"
                                }
                            pom {
                                name.set("DevConsole ${target.name}")
                                description.set("DevConsole SDK module: ${target.name}")
                                url.set(PROJECT_URL)
                                licenses {
                                    license {
                                        name.set("MIT")
                                        url.set("$PROJECT_URL/blob/main/LICENSE")
                                    }
                                }
                                developers {
                                    developer {
                                        id.set("Shakibuzzaman3104")
                                        name.set("Shakibuzzaman")
                                        email.set("shakib.zaman3104@gmail.com")
                                    }
                                }
                                scm {
                                    url.set(PROJECT_URL)
                                    connection.set("scm:git:$PROJECT_URL.git")
                                    developerConnection.set("scm:git:ssh://git@github.com/Shakibuzzaman3104/DevConsole.git")
                                }
                            }
                        }
                    }
                }

                extensions.configure<SigningExtension> {
                    val signingKey = findProperty("signingInMemoryKey") as String?
                    val signingPassword = findProperty("signingInMemoryKeyPassword") as String?
                    isRequired = signingKey != null
                    if (signingKey != null) {
                        useInMemoryPgpKeys(signingKey, signingPassword)
                        sign(extensions.getByType<PublishingExtension>().publications)
                    }
                }
            }
        }
    }

    private companion object {
        const val MAVEN_GROUP = "io.github.shakibuzzaman3104"
        const val SDK_VERSION = "0.1.0-SNAPSHOT"
        const val PROJECT_URL = "https://github.com/Shakibuzzaman3104/DevConsole"
    }
}
