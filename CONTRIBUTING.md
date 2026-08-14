# Contributing to DevConsole

Thanks for taking a look. This is a 34-module Android SDK plus two included Gradle builds
(`build-logic` and `gradle-plugin`), which sounds like a lot until you realise you rarely touch more
than one at a time. The quickest way in is to run the same commands CI runs, listed below and in
[Verify](.github/workflows/verify.yml).

## Prerequisites

- JDK 17 (matches `compileOptions`/`setup-java` in CI; the `build-logic` and `gradle-plugin`
  included builds target the same toolchain).
- Android SDK with `compileSdk`/`targetSdk` 35 installed (Android Gradle Plugin 8.13.0, `minSdk`
  23). See the [compatibility table](README.md#compatibility) in the README for the full matrix.
- No emulator or device needed to build, test, or lint. Only the sample apps' instrumented tests
  want one, and those sit outside the default `build` and `test` paths below.

## Building and testing

All commands run from the repository root.

```bash
# Full build (every module, both build types where applicable)
./gradlew build

# Unit tests only
./gradlew testDebugUnitTest

# Lint (ktlint + detekt; detekt findings pre-dating a module are frozen in its
# detekt-baseline.xml, so this gates on new findings, not the existing baseline)
./gradlew ktlintCheck detekt

# Public API surface check (fails if a change to a module with a committed
# .api baseline — see sdk/*/api/*.api — wasn't accompanied by an apiDump)
./gradlew apiCheck

# Regenerate the .api baseline after an intentional public API change
./gradlew apiDump

# Generate the Dokka API reference (sdk:api, sdk:full, sdk:noop) at
# build/dokka/html/index.html
./gradlew dokkaGenerate

# Verify every publishable module's POM/coordinates resolve end-to-end
# (does not publish anywhere real)
./gradlew publishToMavenLocal

# gradle-plugin (the io.github.devconsole-android plugin) has its own test suite;
# build-logic (convention plugins only, no tests of its own) is checked via ./gradlew build above
./gradlew -p gradle-plugin test

# Sample apps: debug + release compile, plus the plugin's protected-variant check
./gradlew :samples:compose-app:assembleDebug :samples:compose-app:assembleRelease :samples:compose-app:verifyDevConsoleProtectedArtifacts
./gradlew :samples:foundation-app:assembleDebug :samples:foundation-app:assembleRelease :samples:foundation-app:verifyDevConsoleProtectedArtifacts
./gradlew :samples:views-java-app:assembleDebug :samples:views-java-app:assembleRelease :samples:views-java-app:verifyDevConsoleProtectedArtifacts
```

Touched one module? Scope the command to it and save yourself the wait:
`./gradlew :sdk:network-okhttp:test :sdk:network-okhttp:ktlintCheck`.

## Making a change

- Changing public API on a module with a committed `api/*.api` baseline means committing a
  `./gradlew apiDump` alongside it. `apiCheck` fails in CI otherwise, which is the point.
- Style is documented by example rather than by rule, so match the file you're in. New Kotlin files
  start with:
  ```kotlin
  /**
   * @author <you>
   * @since DD/MM/YY
   */
  ```
- Capture code (`sdk:network*`, `sdk:socket*`, `sdk:push*`) must never throw into the host app. A
  debugging tool that crashes the app it exists to observe is worse than no tool. Wrap recording
  paths defensively, following the `runCatching` / `onFailure { logcatInfo(...) }` pattern already
  in those modules.
- Redaction happens at capture time, in `RedactionEngine` via `config.redactionPolicy`. Code that
  formats or displays already-captured text (dashboard JSON/XML rendering, the Compose and Views
  detail screens) should never need to know redaction exists. If you find yourself adding a
  redaction concern to a formatter, the fix probably belongs further upstream.
- If your change touches a protected-variant boundary (a module with a release no-op twin, or the
  Gradle plugin's variant-policy enforcement), run the sample assemble and verify commands above for
  all three samples. That is what actually exercises the debug/release split.

## Opening a pull request

- Keep a PR to one logical change, and let the commit message explain *why* rather than *what*. The
  diff already says what.
- Include the specific `./gradlew` commands you ran to verify the change in the PR description.
- If you changed public API, confirm `apiDump` is included in the diff.
- Changed anything security- or redaction-adjacent? Say so plainly in the PR description. If you're
  reporting a vulnerability rather than fixing one, [SECURITY.md](SECURITY.md) explains how to do
  that privately instead of in a public PR or issue.
- No DCO or sign-off is required; a plain `git commit` is fine. That could change, so check
  [.github/workflows](.github/workflows) if you want to be sure what's enforced today.

## Reporting bugs / requesting features

Use the issue templates: [Bug report](.github/ISSUE_TEMPLATE/bug_report.md) or
[Feature request](.github/ISSUE_TEMPLATE/feature_request.md).

One exception. Never open a public issue for a suspected credential exposure, an accidental
production inclusion, an authentication bypass, or remote code execution.
[SECURITY.md](SECURITY.md) has the private reporting route for those.
