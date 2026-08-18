/**
 * @author Shakib
 * @since 20/07/26
 */
package io.devconsole.buildlogic

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Applies to every publishable SDK module. Wires the Vanniktech Maven Publish plugin, which picks
 * the right publication shape (`KotlinJvm` vs `AndroidSingleVariantLibrary`) from the plugins the
 * module already applies and attaches sources plus a Dokka-generated javadoc jar.
 *
 * Distribution is JitPack, which builds a ref on demand and runs `publishToMavenLocal` — so no
 * remote repository, credentials, or signing is configured here. See docs/PUBLISHING.md.
 */
class PublishingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.vanniktech.maven.publish")
            // The javadoc jar below packs Dokka output, and JavadocJar.Dokka resolves its task at
            // apply time — so Dokka must be on the project before the vanniktech configure runs,
            // not after this plugin in a module's plugins block. Re-application is a no-op for the
            // Android modules that already get it from AndroidLibraryConventionPlugin.
            pluginManager.apply("org.jetbrains.dokka")
            group = MAVEN_GROUP
            version = SDK_VERSION

            val artifactId =
                when (target.name) {
                    "full" -> "devconsole"
                    "noop" -> "devconsole-noop"
                    else -> "devconsole-${target.name}"
                }

            extensions.configure<MavenPublishBaseExtension> {
                coordinates(MAVEN_GROUP, artifactId, SDK_VERSION)
                // Every published module carries Dokka (directly on Kotlin/JVM modules, via
                // AndroidLibraryConventionPlugin on Android ones), so the javadoc jar packs that
                // module's own generated HTML instead of shipping empty.
                configureBasedOnAppliedPlugins(JavadocJar.Dokka("dokkaGeneratePublicationHtml"))
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
                        developerConnection.set("scm:git:ssh://git@github.com/devconsole-android/DevConsole.git")
                    }
                }
            }
        }
    }

    private companion object {
        // The POM group. JitPack rewrites it to com.github.devconsole-android.DevConsole when it
        // serves a build, so consumers name that group, not this one.
        const val MAVEN_GROUP = "io.github.devconsole-android"
        const val SDK_VERSION = "1.2.2"
        const val PROJECT_URL = "https://github.com/devconsole-android/DevConsole"
    }
}
