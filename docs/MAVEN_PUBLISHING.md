# Publishing to Maven Central

All 31 modules under `sdk/` are wired with the
[Vanniktech Maven Publish plugin](https://github.com/vanniktech/gradle-maven-publish-plugin)
(via the `devconsole.publishing` convention), targeting the Sonatype **Central Portal** under the
group `io.github.devconsole-android`. Artifact IDs follow the mapping in
`PublishingConventionPlugin`: `:sdk:full` → `devconsole`, `:sdk:noop` → `devconsole-noop`,
everything else → `devconsole-<module>`. Each publication carries sources, a Dokka-generated
javadoc jar, and a signed POM.

`./gradlew publishToMavenLocal` verifies the whole pipeline locally with no credentials — signing
no-ops when no key is configured. A real publish needs the three things below.

## 1. Verify the namespace (browser, one-time)

`io.github.devconsole-android` is a GitHub-verified namespace tied to the `devconsole-android`
GitHub **organization** — no domain or DNS needed:

1. Go to [central.sonatype.com](https://central.sonatype.com), sign in, and under **Namespaces**
   add `io.github.devconsole-android`. The portal shows a short **verification key**.
2. Create a public repository named exactly that key under the org
   (`github.com/devconsole-android/<key>`), click **Verify Namespace**, then delete the
   temporary repository.
3. Under your account icon → **View Account** → **Generate User Token**. The token is a
   username/password pair — this is `mavenCentralUsername` / `mavenCentralPassword`. It is *not*
   your Sonatype login password.

## 2. Generate a signing key (terminal, one-time)

Central requires every artifact to be GPG-signed.

```bash
gpg --full-generate-key          # RSA 4096, no expiration is fine for a personal signing key
gpg --list-secret-keys --keyid-format LONG   # note the key ID (the part after rsa4096/)
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # publish so Central can verify it
gpg --export-secret-keys --armor <KEY_ID> > private-key.asc
```

- `signingInMemoryKey` = the full contents of `private-key.asc` (ASCII armor, newlines and all)
- `signingInMemoryKeyId` = the **last 8 hex characters** of the key ID
- `signingInMemoryKeyPassword` = the passphrase you set (blank if none)

**Never commit `private-key.asc`** or paste it anywhere other than a GitHub Actions secret or
your local `~/.gradle/gradle.properties`.

## 3. Bump the version and publish

Central's release path does **not** accept `-SNAPSHOT` versions. Before the first real publish,
change `SDK_VERSION` in
`build-logic/convention-publishing/src/main/kotlin/io/devconsole/buildlogic/PublishingConventionPlugin.kt`
(and `version` in `gradle-plugin/build.gradle.kts`) to a real version, e.g. `1.1.1`.

**Locally:**

```bash
ORG_GRADLE_PROJECT_mavenCentralUsername=... \
ORG_GRADLE_PROJECT_mavenCentralPassword=... \
ORG_GRADLE_PROJECT_signingInMemoryKey="$(cat private-key.asc)" \
ORG_GRADLE_PROJECT_signingInMemoryKeyId=... \
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=... \
./gradlew publishToMavenCentral --no-configuration-cache
```

**Or via CI:** add the five values as repo secrets (Settings → Secrets and variables → Actions):
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_IN_MEMORY_KEY`,
`SIGNING_IN_MEMORY_KEY_ID`, `SIGNING_IN_MEMORY_KEY_PASSWORD` — then run the **Publish to Maven
Central** workflow from the Actions tab (`workflow_dispatch` only; it never fires automatically).

Either way, `publishToMavenCentral` **uploads and validates** a deployment but does not release
it. Go to central.sonatype.com → **Deployments**, wait for validation to pass, review the
contents, and press **Publish**. That last click is the irreversible step: a released
coordinate + version can never be replaced or deleted. (To skip the manual click on future
releases, use `./gradlew publishAndReleaseToMavenCentral` instead.)

Artifacts appear on `repo1.maven.org` within roughly 15–30 minutes of release, and on
search.maven.org within a few hours.

## The Gradle plugin is published separately

`io.github.devconsole-android` lives in the `gradle-plugin` included build and goes to the
**Gradle Plugin Portal**, not Central. One-time: create an account at
[plugins.gradle.org](https://plugins.gradle.org), generate an API key, and put
`gradle.publish.key` / `gradle.publish.secret` in `~/.gradle/gradle.properties`. Then:

```bash
./gradlew -p gradle-plugin publishPlugins
```

First-time publication of a new plugin ID may sit in a short manual approval queue on the
Portal side.

## Consuming a published release

```kotlin
plugins {
    id("io.github.devconsole-android") version "<version>"
}
dependencies {
    debugImplementation("io.github.devconsole-android:devconsole:<version>")
    releaseImplementation("io.github.devconsole-android:devconsole-noop:<version>")
}
```

See [BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md](BUILD_VARIANTS_AND_PRODUCTION_SAFETY.md) for what
the plugin enforces about that split.

## JitPack

Nothing to run: JitPack builds a ref on first request and serves the result under the group
`com.github.devconsole-android.DevConsole`. `jitpack.yml` pins the JDK to 17 and otherwise leaves
JitPack's default command alone — that default already passes `-xtest -xlint
-xsignMavenPublication`, so overriding it with an `install:` block would silently start running the
full unit suite on every consumer-triggered build.

Because JitPack skips the signing *task* but Gradle still validates the publication, a publication
that registers `.asc` artifacts fails there with "artifact file does not exist". That is why
`PublishingConventionPlugin` calls `signAllPublications()` only when a key is configured — the same
condition that lets a contributor run `publishToMavenLocal` without one.

Only the 31 library modules are served; `gradle-plugin` is an `includeBuild` and is not among them,
so JitPack consumers use plain coordinates rather than the plugin. Check a build at
`https://jitpack.io/api/builds/com.github.devconsole-android/DevConsole`.
