# Default LAN and Mock Behavior Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make LAN the default for both existing server-start APIs, preserve explicit loopback opt-out behavior, and lock the full/noop mock defaults with regression tests.

**Architecture:** Change the defaults at the existing public constructor boundaries rather than adding a new policy field. The full runtime already constructs its `MockEngine` enabled and the noop runtime constructs it disabled; tests will make those contracts explicit. Runtime permission checks, capture-category gating, and explicit binding branches remain unchanged.

**Tech Stack:** Kotlin/JVM and Kotlin Android libraries, JUnit 4, Robolectric, Gradle, Markdown documentation.

---

### Task 1: Lock the public and lower-level LAN defaults with failing tests

**Files:**
- Modify: `sdk/api/src/test/kotlin/io/devconsole/api/FocusedConfigurationTest.kt`
- Modify: `sdk/api/src/test/kotlin/io/devconsole/api/DevConsoleContractTest.kt`
- Create: `sdk/server-api/src/test/kotlin/io/devconsole/server/api/StartRequestDefaultsTest.kt`

- [ ] **Step 1: Change the focused API default assertion**

In `FocusedConfigurationTest`, rename the test to `focused policies use persistent sessions and LAN defaults` and change the binding assertion to:

```kotlin
assertEquals(BrowserBinding.LAN, browser.binding)
```

- [ ] **Step 2: Add the public `StartRequest` default assertion**

In `DevConsoleContractTest`, add this test next to the existing `StartRequest` validation test:

```kotlin
@Test
fun `default start request uses LAN binding`() {
    assertEquals(BindingMode.LAN, StartRequest().bindingMode)
}
```

The file already lives in `io.devconsole.api`, so no new imports are needed.

- [ ] **Step 3: Add the server-api default assertion**

Create `StartRequestDefaultsTest.kt` with:

```kotlin
package io.devconsole.server.api

import org.junit.Assert.assertEquals
import org.junit.Test

class StartRequestDefaultsTest {
    @Test
    fun `default server start request uses LAN binding`() {
        assertEquals(BindingMode.LAN, StartRequest().bindingMode)
    }
}
```

- [ ] **Step 4: Run the focused tests and verify they fail for the old defaults**

Run:

```bash
./gradlew :sdk:api:test --tests io.devconsole.api.FocusedConfigurationTest --tests io.devconsole.api.DevConsoleContractTest
./gradlew :sdk:server-api:testDebugUnitTest --tests io.devconsole.server.api.StartRequestDefaultsTest
```

Expected: the new/changed assertions fail because the current defaults are `LOOPBACK`.

- [ ] **Step 5: Commit the failing contract tests**

```bash
git add sdk/api/src/test/kotlin/io/devconsole/api/FocusedConfigurationTest.kt \
  sdk/api/src/test/kotlin/io/devconsole/api/DevConsoleContractTest.kt \
  sdk/server-api/src/test/kotlin/io/devconsole/server/api/StartRequestDefaultsTest.kt
git commit -m "test: define LAN binding defaults"
```

### Task 2: Lock full-runtime, noop, and mock-engine behavior with failing tests

**Files:**
- Modify: `sdk/mocks/src/test/kotlin/io/devconsole/mocks/MockEngineTest.kt`
- Modify: `sdk/full/src/test/kotlin/io/devconsole/FullFacadeTest.kt`
- Modify: `sdk/full/src/test/kotlin/io/devconsole/PlatformFacadeProviderMoreScreenBindingTest.kt`
- Modify: `sdk/noop/src/test/kotlin/io/devconsole/NoopFacadeTest.kt`

- [ ] **Step 1: Add the direct mock-engine default test**

In `MockEngineTest`, add:

```kotlin
@Test
fun `mock engine is enabled by default`() {
    assertTrue(MockEngine(emptyList()).isEnabled())
}
```

Use the file’s existing `assertTrue` import.

- [ ] **Step 2: Update the full facade’s default-start expectation and assert the full mock default**

In `FullFacadeTest`’s `FR-BUILD-002 full facade initializes the enabled runtime` test, keep the existing `StartRequest()` call, change:

```kotlin
assertEquals(BindingMode.LAN, started.endpoint.bindingMode)
```

and add this assertion after initialization, before starting the server:

```kotlin
assertTrue(provider.mockEngine().isEnabled())
```

- [ ] **Step 3: Change the More-screen default test into a LAN assertion and add an explicit loopback test**

In `PlatformFacadeProviderMoreScreenBindingTest`:

1. Update the class KDoc so it says the default config binds LAN and loopback is the explicit secondary choice.
2. Rename `More screen start still binds loopback under the default config` to `More screen start binds LAN under the default config` and change its assertion to:

```kotlin
assertEquals(BindingMode.LAN, provider.startFromMoreScreen().bindingMode)
```

3. Add this explicit opt-out test:

```kotlin
@Test
fun `More screen start binds loopback when the host explicitly configures loopback`() =
    runTest {
        val provider = PlatformFacadeProvider()
        provider.initialize(
            ApplicationProvider.getApplicationContext(),
            DevConsoleConfig.default().withBrowserConfig(
                BrowserConfig(binding = BrowserBinding.LOOPBACK, portRange = 8640..8659),
            ),
        )

        assertEquals(BindingMode.LOOPBACK, provider.startFromMoreScreen().bindingMode)

        provider.stop(StopReason.UserRequested)
    }
```

- [ ] **Step 4: Assert noop mocks remain disabled**

In `NoopFacadeTest`, add to the existing disabled-facade test or create a separate test with the same initialization setup:

```kotlin
assertTrue(!DevConsole.mockEngine().isEnabled())
```

- [ ] **Step 5: Run the focused runtime tests and verify they fail only on the changed default expectations**

Run:

```bash
./gradlew :sdk:mocks:test --tests io.devconsole.mocks.MockEngineTest \
  :sdk:full:testDebugUnitTest --tests io.devconsole.FullFacadeTest --tests io.devconsole.PlatformFacadeProviderMoreScreenBindingTest \
  :sdk:noop:testDebugUnitTest --tests io.devconsole.NoopFacadeTest
```

Expected: the full/default LAN assertions and the noop/mock assertions identify the old behavior; existing explicit loopback/LAN tests continue to pass.

- [ ] **Step 6: Commit the runtime contract tests**

```bash
git add sdk/mocks/src/test/kotlin/io/devconsole/mocks/MockEngineTest.kt \
  sdk/full/src/test/kotlin/io/devconsole/FullFacadeTest.kt \
  sdk/full/src/test/kotlin/io/devconsole/PlatformFacadeProviderMoreScreenBindingTest.kt \
  sdk/noop/src/test/kotlin/io/devconsole/NoopFacadeTest.kt
git commit -m "test: cover full and noop default behavior"
```

### Task 3: Implement LAN defaults and update runtime KDoc

**Files:**
- Modify: `sdk/api/src/main/kotlin/io/devconsole/api/FocusedPolicies.kt`
- Modify: `sdk/api/src/main/kotlin/io/devconsole/api/StartRequest.kt`
- Modify: `sdk/server-api/src/main/kotlin/io/devconsole/server/api/LocalServerEngine.kt`
- Modify: `sdk/facade-shared/src/main/kotlin/io/devconsole/DevConsole.kt`
- Modify: `sdk/full/src/main/kotlin/io/devconsole/PlatformFacadeProvider.kt`

- [ ] **Step 1: Make `BrowserConfig` default to LAN**

In `FocusedPolicies.kt`, change:

```kotlin
val binding: BrowserBinding = BrowserBinding.LOOPBACK,
```

to:

```kotlin
val binding: BrowserBinding = BrowserBinding.LAN,
```

Rewrite the surrounding KDoc so it says LAN is the default for the More-screen start and loopback is the explicit safer choice; retain the warning that the dashboard uses plaintext HTTP and cite `docs/THREAT_MODEL.md`.

- [ ] **Step 2: Make public `StartRequest` default to LAN**

In `sdk/api/.../StartRequest.kt`, change:

```kotlin
val bindingMode: BindingMode = BindingMode.LOOPBACK,
```

to:

```kotlin
val bindingMode: BindingMode = BindingMode.LAN,
```

Update the enum and data-class KDoc to say programmatic starts default to LAN, while callers can explicitly pass `BindingMode.LOOPBACK`; remove claims that loopback is the default.

- [ ] **Step 3: Align `sdk:server-api`’s lower-level default**

In `sdk/server-api/src/main/kotlin/io/devconsole/server/api/LocalServerEngine.kt`, change the lower-level `StartRequest` constructor default to:

```kotlin
val bindingMode: BindingMode = BindingMode.LAN,
```

Do not change `SessionCodeAuthority`’s placeholder `lastEndpoint`, which is an internal pre-start URL fallback rather than a server-start binding default.

- [ ] **Step 4: Update facade and provider KDoc**

In `sdk/facade-shared/src/main/kotlin/io/devconsole/DevConsole.kt`, change the `startBrowser` documentation to describe `StartRequest()` as LAN by default, and show explicit `BindingMode.LOOPBACK` as the safer choice for trusted USB/ADB-only use.

In `sdk/full/src/main/kotlin/io/devconsole/PlatformFacadeProvider.kt`, update `configuredStartRequest()`’s comment so it no longer says the default remains loopback. Keep the mapping of explicit `BrowserBinding.LOOPBACK` and `BrowserBinding.LAN` unchanged.

- [ ] **Step 5: Run the focused tests and compile the changed modules**

Run:

```bash
./gradlew :sdk:api:test --tests io.devconsole.api.FocusedConfigurationTest --tests io.devconsole.api.DevConsoleContractTest \
  :sdk:server-api:testDebugUnitTest --tests io.devconsole.server.api.StartRequestDefaultsTest \
  :sdk:mocks:test --tests io.devconsole.mocks.MockEngineTest \
  :sdk:full:testDebugUnitTest --tests io.devconsole.FullFacadeTest --tests io.devconsole.PlatformFacadeProviderMoreScreenBindingTest \
  :sdk:noop:testDebugUnitTest --tests io.devconsole.NoopFacadeTest
```

Expected: all focused tests pass, including the explicit loopback and noop-disabled assertions.

- [ ] **Step 6: Commit the implementation and KDoc changes**

```bash
git add sdk/api/src/main/kotlin/io/devconsole/api/FocusedPolicies.kt \
  sdk/api/src/main/kotlin/io/devconsole/api/StartRequest.kt \
  sdk/server-api/src/main/kotlin/io/devconsole/server/api/LocalServerEngine.kt \
  sdk/facade-shared/src/main/kotlin/io/devconsole/DevConsole.kt \
  sdk/full/src/main/kotlin/io/devconsole/PlatformFacadeProvider.kt
git commit -m "feat: default dashboard binding to LAN"
```

### Task 4: Refresh user-facing LAN guidance

**Files:**
- Modify: `README.md`
- Modify: `docs/THREAT_MODEL.md`
- Modify: `docs/LAN_PERMISSION_AND_TROUBLESHOOTING.md`

- [ ] **Step 1: Update README setup examples**

Replace the quick-start programmatic example with the default call and an explicit loopback alternative:

```kotlin
val result = DevConsole.startBrowser() // LAN by default
// For USB/ADB-only access instead:
val loopbackResult = DevConsole.startBrowser(
    StartRequest(bindingMode = BindingMode.LOOPBACK),
)
```

Replace the More-screen paragraph and sample with:

```markdown
That button binds **LAN** by default, so its QR code is reachable from another device on the same
network. For the safer USB/ADB-only mode, configure loopback explicitly; the button issues no
`StartRequest` of its own:
```

```kotlin
DevConsoleConfig.default().withBrowserConfig(
    BrowserConfig(binding = BrowserBinding.LOOPBACK),
)
```

Update the regular code sample to call `DevConsole.startBrowser(StartRequest())`, annotate it as
the LAN default, and retain the existing explicit `StartRequest(bindingMode = BindingMode.LOOPBACK)`
snippet as the loopback opt-out.

Change the connect-method bullets to identify LAN as the default and loopback as the explicit secondary option. Keep the existing warning that LAN is plaintext and never silently falls back to loopback.

Use:

```markdown
- **LAN (default):** open the URL from another device on the same network. Read
  [the threat model](docs/THREAT_MODEL.md) first because the dashboard speaks plaintext HTTP.
- **Loopback (explicit):** run `adb forward tcp:<port> tcp:<port>`, then open the connect URL.
```

- [ ] **Step 2: Update README security language**

Replace the security-model introduction and first network bullet with:

```markdown
DevConsole deliberately exposes your app's internals to a browser. LAN is the default to make
cross-device debugging work immediately, while loopback remains the safer explicit choice on
untrusted or shared networks.

- **The dashboard speaks plaintext HTTP.** There is no TLS. In LAN mode, anyone who can watch your
  network packets can read everything it shows: headers, tokens, bodies, exports. Use
  `BindingMode.LOOPBACK` with `adb forward` when the network is not trusted.
```

- [ ] **Step 3: Update the threat model**

In the Safe defaults section, explain:

```kotlin
DevConsole.startBrowser(StartRequest()) // LAN default
DevConsole.startBrowser(StartRequest(bindingMode = BindingMode.LOOPBACK)) // explicit safer mode
```

Precede the examples with: “LAN is the primary default for both existing start entry points so
cross-device debugging works without extra configuration. Use the explicit loopback form on an
untrusted/shared network.” Change the More-screen explanation to say `BrowserConfig()` defaults to
LAN and that `BrowserConfig(binding = BrowserBinding.LOOPBACK)` is the explicit secondary choice.
Preserve all threat warnings, permission behavior, and the statement that the two entry points
remain independently configurable.

- [ ] **Step 4: Update LAN troubleshooting wording**

Rename the loopback section’s posture from “safer default” to “explicit loopback / ADB mode” and leave its command and `adb forward` instructions intact.

- [ ] **Step 5: Search for stale default claims and commit documentation**

Run:

```bash
rg -n "loopback.*default|default.*loopback|LAN mode is always an explicit opt-in|LAN.*explicit opt-in|BrowserBinding\.LOOPBACK.*default|StartRequest\(\).*loopback" README.md docs sdk -g '*.md' -g '*.kt'
```

The remaining matches must be explicit loopback examples, fallback/error guidance, or the internal session-code placeholder—not claims about the public server-start defaults.

Then commit:

```bash
git add README.md docs/THREAT_MODEL.md docs/LAN_PERMISSION_AND_TROUBLESHOOTING.md
git commit -m "docs: describe LAN as the default binding"
```

### Task 5: Run the complete verification set and review the final diff

**Files:**
- Test: `sdk/api`, `sdk/server-api`, `sdk/mocks`, `sdk/full`, `sdk/noop`
- Review: all files changed by Tasks 1–4

- [ ] **Step 1: Run all affected unit tests**

```bash
./gradlew :sdk:api:test :sdk:server-api:test :sdk:mocks:test \
  :sdk:full:testDebugUnitTest :sdk:noop:testDebugUnitTest
```

Expected: every task completes successfully with no failed tests.

- [ ] **Step 2: Compile the public/full/noop artifacts**

```bash
./gradlew :sdk:api:compileKotlin :sdk:server-api:compileDebugKotlin \
  :sdk:full:compileDebugKotlin :sdk:noop:compileDebugKotlin
```

Expected: all compile tasks pass without API or Kotlin errors.

- [ ] **Step 3: Check formatting and inspect the diff**

```bash
git diff --check HEAD~4..HEAD
git status --short
git log --oneline -6
```

Review that only the approved design spec, default values, tests, KDoc, and LAN guidance changed; verify noop construction remains `MockEngine(emptyList(), enabled = false)` and no permission/fallback logic was altered.

- [ ] **Step 4: Run the repository check task**

```bash
./gradlew check
```

Expected: the repository-wide quality and test checks pass. If the check task reports only an expected API-dump or documentation artifact difference, inspect it and include the generated tracked artifact only if the build explicitly requires it.

- [ ] **Step 5: Report completion with evidence**

Summarize the public defaults, explicit loopback override, preserved noop safety, documentation updates, and the exact Gradle commands that passed. Link the key changed files and mention any environment-specific test limitation rather than claiming unverified success.
