# Publishing

DevConsole is distributed through **[JitPack](https://jitpack.io/#devconsole-android/DevConsole)**.
The Gradle plugin is the one exception: it lives in the `gradle-plugin` included build and goes to
the **Gradle Plugin Portal**, because the `plugins { }` DSL cannot resolve a plugin marker from
JitPack.

## The 34 library modules → JitPack

All 34 modules under `sdk/` are wired with the
[Vanniktech Maven Publish plugin](https://github.com/vanniktech/gradle-maven-publish-plugin)
(via the `devconsole.publishing` convention). Artifact IDs follow the mapping in
`PublishingConventionPlugin`: `:sdk:full` → `devconsole`, `:sdk:noop` → `devconsole-noop`,
everything else → `devconsole-<module>`. Each publication carries sources, a Dokka-generated
javadoc jar, and a POM with license/developer/SCM metadata.

**There is nothing to run to release.** JitPack builds a ref the first time someone asks for it and
serves the result under the group `com.github.devconsole-android.DevConsole`. Cutting a release is
just pushing a tag; the first consumer request triggers the build.

`jitpack.yml` pins the JDK to 17 and otherwise leaves JitPack's default command alone — that
default already passes `-xtest -xlint -xsignMavenPublication`, so overriding it with an `install:`
block would silently start running the full unit suite on every consumer-triggered build.

To verify the whole pipeline locally before tagging:

```bash
./gradlew publishToMavenLocal
```

That produces exactly what JitPack produces. Check a real JitPack build at
`https://jitpack.io/api/builds/com.github.devconsole-android/DevConsole`.

### Bumping the version

`SDK_VERSION` in
`build-logic/convention-publishing/src/main/kotlin/io/devconsole/buildlogic/PublishingConventionPlugin.kt`
and `version` in `gradle-plugin/build.gradle.kts` are kept in step. JitPack derives the consumer-
facing version from the tag or branch rather than from `SDK_VERSION`, but the two should match so
`publishToMavenLocal` output and the tag agree.

## The Gradle plugin → Gradle Plugin Portal

One-time: create an account at [plugins.gradle.org](https://plugins.gradle.org), generate an API
key, and put `gradle.publish.key` / `gradle.publish.secret` in `~/.gradle/gradle.properties`. Then:

```bash
./gradlew -p gradle-plugin publishPlugins
```

First-time publication of a new plugin ID may sit in a short manual approval queue on the Portal
side.

## Consuming a release

Add JitPack to the **settings** file, not the module:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
plugins {
    id("io.github.devconsole-android") version "<version>"
}
dependencies {
    debugImplementation("com.github.devconsole-android.DevConsole:devconsole:<version>")
    releaseImplementation("com.github.devconsole-android.DevConsole:devconsole-noop:<version>")
}
```

A release tag works as the bare tag. Any branch works as `<branch>-SNAPSHOT`, with `/` written as
`~`, so a `feature/x` branch is `feature~x-SNAPSHOT`. JitPack support starts at **1.1.1**; earlier
tags do not build there.

See [BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md](BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md) for what the
plugin enforces about the debug/release split.
