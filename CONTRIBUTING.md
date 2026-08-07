# Contributing to DevConsole

Thanks for taking a look. This project is a 25-module Android SDK plus two included Gradle
builds (`build-logic`, `gradle-plugin`), so the fastest way in is to lean on the same commands CI
runs — see [Verify](.github/workflows/verify.yml).

## Prerequisites

- JDK 17 (matches `compileOptions`/`setup-java` in CI; the `build-logic` and `gradle-plugin`
  included builds target the same toolchain).
- Android SDK with `compileSdk`/`targetSdk` 35 installed (Android Gradle Plugin 8.13.0, `minSdk`
  23). See the [compatibility table](README.md#compatibility) in the README for the full matrix.
- No emulator or device is required to build, run unit tests, or lint — only the three sample
  apps' instrumented tests would need one, and those aren't part of the default `build`/`test`
  path below.

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

If you only touched one module, scope any of the above to it, e.g.
`./gradlew :sdk:network-okhttp:test :sdk:network-okhttp:ktlintCheck`.

## Making a change

- Public API changes to a module that carries `org.jetbrains.kotlinx.binary-compatibility-validator`
  (anything with a committed `api/*.api` file, e.g. `sdk:api`) need `./gradlew apiDump` committed
  alongside the change — `apiCheck` in CI fails otherwise.
- Kotlin file header, view-id naming, and general style conventions used throughout this codebase
  are documented informally by example — match the surrounding file. New Kotlin files start with:
  ```kotlin
  /**
   * @author <you>
   * @since DD/MM/YY
   */
  ```
- Capture code (`sdk:network*`, `sdk:socket*`, `sdk:push*`) must never throw into the host app —
  wrap recording paths defensively, matching the existing `runCatching`/`onFailure { logcatInfo(...) }`
  pattern used throughout those modules.
- Redaction happens at capture time (`RedactionEngine`, applied via `config.redactionPolicy`); code
  that formats or displays already-captured text (dashboard JSON/XML rendering, Compose/Views
  detail screens) must not need to know about redaction — if you're adding a redaction concern to a
  formatter, something further upstream is probably the better place for it.
- If your change touches a protected-variant boundary (a module with a release no-op counterpart,
  or the Gradle plugin's variant-policy enforcement), run the sample assemble/verify commands above
  for all three samples — that's what actually exercises the debug/release split.

## Opening a pull request

- Keep PRs scoped to one logical change; the commit message should explain *why*, not just *what*.
- Include the specific `./gradlew` commands you ran to verify the change in the PR description.
- If you changed public API, confirm `apiDump` is included in the diff.
- If you changed anything security- or redaction-adjacent, call it out explicitly in the PR
  description — see [SECURITY.md](SECURITY.md) for how to report a vulnerability privately instead
  of through a public PR/issue.
- No DCO or commit-sign-off is currently required or enforced by this repository (no `DCO` check
  or commit-signing requirement is configured in CI) — standard `git commit` is fine. This may
  change; check [.github/workflows](.github/workflows) if you're unsure what's currently enforced.

## Reporting bugs / requesting features

Use the issue templates: [Bug report](.github/ISSUE_TEMPLATE/bug_report.md) or
[Feature request](.github/ISSUE_TEMPLATE/feature_request.md). Do **not** file a public issue for a
suspected credential exposure, production inclusion, authentication bypass, or remote code
execution — see [SECURITY.md](SECURITY.md) for private reporting instead.
