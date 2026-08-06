plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.android.tools.build:gradle:9.3.0")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:14.2.0")
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.8")
    // Matches the `dokka` version in gradle/libs.versions.toml. Applied by
    // AndroidLibraryConventionPlugin so every Android library module -- notably sdk:full and
    // sdk:noop, which the root project aggregates via `dependencies { dokka(project(...)) }` --
    // exposes the Dokka-specific outgoing configuration the aggregation needs. Without it, the
    // root's aggregation dependency falls back to resolving the module's ordinary AGP runtime
    // variants, which is both semantically wrong and ambiguous (debug vs release).
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.2.0")
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.37.0")
}

sourceSets {
    named("main") {
        kotlin.srcDirs(
            "convention-android-application/src/main/kotlin",
            "convention-android-library/src/main/kotlin",
            "convention-kotlin-jvm/src/main/kotlin",
            "convention-quality/src/main/kotlin",
            "convention-publishing/src/main/kotlin",
        )
    }
}

gradlePlugin {
    plugins {
        register("devConsoleAndroidApplication") {
            id = "devconsole.android.application"
            implementationClass = "io.devconsole.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("devConsoleAndroidLibrary") {
            id = "devconsole.android.library"
            implementationClass = "io.devconsole.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("devConsoleKotlinJvm") {
            id = "devconsole.kotlin.jvm"
            implementationClass = "io.devconsole.buildlogic.KotlinJvmConventionPlugin"
        }
        register("devConsoleQuality") {
            id = "devconsole.quality"
            implementationClass = "io.devconsole.buildlogic.QualityConventionPlugin"
        }
        register("devConsolePublishing") {
            id = "devconsole.publishing"
            implementationClass = "io.devconsole.buildlogic.PublishingConventionPlugin"
        }
    }
}
