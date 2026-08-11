# Android Inspector Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Compose inspector a native, adaptive Material 3 sibling of the redesigned web workbench using graphite/cobalt roles and platform-aware theming.

**Architecture:** Keep `InspectorViewModel`, `InspectorState`, and `InspectorAction` unchanged. Add a small theme-preference boundary, move the custom theme onto the approved Material roles, make the workspace choose navigation bar or rail from available width, and replace card-shaped shared primitives with standard list/section composition.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Android `SharedPreferences`, JUnit, Gradle Android library build.

---

## File map

- Create `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorThemePreference.kt`: persisted explicit theme override.
- Create `sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/InspectorThemePreferenceTest.kt`: pure store/selection tests.
- Create `sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/InspectorAdaptiveLayoutTest.kt`: width-to-navigation contract.
- Modify `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/DevConsoleTheme.kt`: graphite/cobalt roles and Material typography/shapes.
- Modify `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/DevConsoleWorkspace.kt`: system theme, persisted override, adaptive navigation shell.
- Modify `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorNavComponents.kt`: Material top app bar, search, filters, navigation bar/rail.
- Modify `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorUiComponents.kt`: replace terminal-card primitives with flat sections.
- Modify `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorListComponents.kt`: edge-to-edge rows and sentence-case labels.
- Modify `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorHeroComponents.kt`: convert oversized heroes to compact summaries.
- Modify `InspectorObserveScreen.kt`, `InspectorControlScreen.kt`, `InspectorDataScreen.kt`, and `InspectorMoreScreen.kt`: adopt native shared primitives.
- Modify detail/editor files under `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/`: standard app bars, sheets, dialogs, and lists.
- Modify `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDesignSystemPreviews.kt`: compact/expanded and dark/light preview matrix.
- Modify `sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/DevConsoleWorkspaceTest.kt`: navigation and destination regression coverage.
- Modify `DESIGN.md` and regenerate `.impeccable/design.json` after both platform plans pass.

### Task 1: Add a persisted theme-preference boundary

**Files:**
- Create: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorThemePreference.kt`
- Create: `sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/InspectorThemePreferenceTest.kt`

- [ ] **Step 1: Write the failing pure preference tests**

```kotlin
package io.devconsole.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InspectorThemePreferenceTest {
    @Test
    fun `missing override delegates to system theme`() {
        val store = FakeThemePreferenceStore()
        assertNull(store.readOverride())
        assertEquals(true, resolveDarkTheme(store.readOverride(), systemDark = true))
        assertEquals(false, resolveDarkTheme(store.readOverride(), systemDark = false))
    }

    @Test
    fun `explicit override wins and survives a new store read`() {
        val values = mutableMapOf<String, Boolean>()
        val store = FakeThemePreferenceStore(values)
        store.writeOverride(true)

        assertEquals(true, FakeThemePreferenceStore(values).readOverride())
        assertEquals(true, resolveDarkTheme(store.readOverride(), systemDark = false))
    }
}

private class FakeThemePreferenceStore(
    private val values: MutableMap<String, Boolean> = mutableMapOf(),
) : InspectorThemePreferenceStore {
    override fun readOverride(): Boolean? = values["dark"]
    override fun writeOverride(dark: Boolean) { values["dark"] = dark }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./gradlew :sdk:ui-compose:testDebugUnitTest --tests io.devconsole.ui.compose.InspectorThemePreferenceTest
```

Expected: FAIL because `InspectorThemePreferenceStore` and `resolveDarkTheme` do not exist.

- [ ] **Step 3: Implement the preference boundary**

```kotlin
package io.devconsole.ui.compose

import android.content.Context

internal interface InspectorThemePreferenceStore {
    fun readOverride(): Boolean?
    fun writeOverride(dark: Boolean)
}

internal class SharedPreferencesInspectorThemeStore(
    context: Context,
) : InspectorThemePreferenceStore {
    private val preferences =
        context.getSharedPreferences("devconsole.ui", Context.MODE_PRIVATE)

    override fun readOverride(): Boolean? =
        if (preferences.contains(KEY_DARK_THEME)) preferences.getBoolean(KEY_DARK_THEME, false) else null

    override fun writeOverride(dark: Boolean) {
        preferences.edit().putBoolean(KEY_DARK_THEME, dark).apply()
    }

    private companion object {
        const val KEY_DARK_THEME = "dark_theme_override"
    }
}

internal fun resolveDarkTheme(
    override: Boolean?,
    systemDark: Boolean,
): Boolean = override ?: systemDark
```

- [ ] **Step 4: Run the test and commit**

Expected: PASS.

```bash
git add sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorThemePreference.kt sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/InspectorThemePreferenceTest.kt
git commit -m "feat(compose): persist explicit theme choice"
```

### Task 2: Replace the Compose theme foundation

**Files:**
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/DevConsoleTheme.kt`
- Test: `sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/InspectorThemePreferenceTest.kt`

- [ ] **Step 1: Replace the old signal palette**

Use the approved dark roles:

```kotlin
private val DarkPrimary = Color(0xFF72A7FF)
private val DarkError = Color(0xFFFF7B72)
private val DarkWarning = Color(0xFFF2B66D)
private val DarkSecondary = Color(0xFF72C4CE)

internal val DevConsoleDarkColors =
    DevConsoleColors(
        ground = Color(0xFF111317),
        panel = Color(0xFF171A20),
        surface2 = Color(0xFF1D2129),
        surface3 = Color(0xFF242A34),
        codeBg = Color(0xFF0D0F13),
        ink = Color(0xFFECEEF2),
        muted = Color(0xFF969DA8),
        text3 = Color(0xFF858D99),
        line = Color(0xFF343A45),
        borderStrong = Color(0xFF4A5260),
        signal = DarkPrimary,
        signalInk = Color(0xFF0E1624),
        warn = DarkWarning,
        error = DarkError,
        errorInk = Color(0xFF0E1624),
        put = DarkSecondary,
        signalSoft = DarkPrimary.copy(alpha = 0.13f),
        errorSoft = DarkError.copy(alpha = 0.13f),
        warnSoft = DarkWarning.copy(alpha = 0.13f),
        putSoft = DarkSecondary.copy(alpha = 0.13f),
        jsonKey = Color(0xFF8CB8FF),
        jsonString = Color(0xFFA9D6B9),
        jsonNumber = DarkWarning,
        jsonBoolean = Color(0xFFC7A7FF),
        jsonNull = Color(0xFF969DA8),
        jsonBraces = listOf(DarkPrimary, DarkWarning, DarkSecondary, Color(0xFFECEEF2), Color(0xFFC7A7FF)),
    )
```

Use the exact light roles from the approved spec: background `0xFFF7F8FA`, panel `0xFFFFFFFF`, raised `0xFFEEF1F5`, pressed `0xFFE4E8EE`, ink `0xFF1C1F24`, muted `0xFF68707B`, outline `0xFFD5D9E0`, strong outline `0xFFAAB1BC`, and primary `0xFF245DA8`.

- [ ] **Step 2: Move bespoke type sizes onto Material roles**

Pass a `Typography` to `MaterialTheme`. Map screen titles to `headlineSmall`, row titles to `bodyLarge` with monospace only where data-driven, subtitles to `bodySmall`, and structural labels to `labelMedium`. Remove `DevConsoleType.title`, `groupLabel`, and `heroValue` after all call sites migrate.

Change `DevConsoleTheme`'s default to `isSystemInDarkTheme()` so isolated previews and direct component hosts also respect the platform when the workspace does not pass an explicit value.

- [ ] **Step 3: Add restrained Material shapes**

Pass:

```kotlin
val DevConsoleShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)
```

- [ ] **Step 4: Compile and commit**

```bash
./gradlew :sdk:ui-compose:compileDebugKotlin
git add sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/DevConsoleTheme.kt
git commit -m "style(compose): adopt graphite cobalt theme"
```

Expected: compilation may still report removed `DevConsoleType` call sites; keep compatibility aliases until Tasks 5–7 migrate them, then remove the aliases.

### Task 3: Define and test adaptive navigation selection

**Files:**
- Create: `sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/InspectorAdaptiveLayoutTest.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/DevConsoleWorkspace.kt`

- [ ] **Step 1: Write the failing width contract test**

```kotlin
package io.devconsole.ui.compose

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class InspectorAdaptiveLayoutTest {
    @Test fun `compact width uses navigation bar`() {
        assertEquals(InspectorNavigationLayout.Bar, inspectorNavigationLayout(599.dp))
    }

    @Test fun `expanded width uses navigation rail`() {
        assertEquals(InspectorNavigationLayout.Rail, inspectorNavigationLayout(600.dp))
    }
}
```

- [ ] **Step 2: Run and verify failure**

Run the focused `testDebugUnitTest` command with `InspectorAdaptiveLayoutTest`.

Expected: FAIL because the layout type and resolver do not exist.

- [ ] **Step 3: Add the pure layout resolver**

```kotlin
internal enum class InspectorNavigationLayout { Bar, Rail }

internal fun inspectorNavigationLayout(width: Dp): InspectorNavigationLayout =
    if (width < 600.dp) InspectorNavigationLayout.Bar else InspectorNavigationLayout.Rail
```

- [ ] **Step 4: Run the focused test and commit**

Expected: PASS.

```bash
git add sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/DevConsoleWorkspace.kt sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/InspectorAdaptiveLayoutTest.kt
git commit -m "test(compose): define adaptive navigation boundary"
```

### Task 4: Build the platform-aware adaptive workspace shell

**Files:**
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/DevConsoleWorkspace.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorNavComponents.kt`
- Modify: `sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/DevConsoleWorkspaceTest.kt`

- [ ] **Step 1: Wire system theme and persisted override**

In the public `DevConsoleWorkspace`, create `SharedPreferencesInspectorThemeStore(LocalContext.current.applicationContext)`, read the nullable override once, use `isSystemInDarkTheme()` as fallback, and persist the opposite of the resolved theme when the existing theme action is tapped. Hoist the resolved Boolean into `DevConsoleTheme`.

- [ ] **Step 2: Replace the custom bottom row with Material navigation components**

Implement `InspectorNavigationBar(items)` with `NavigationBar`/`NavigationBarItem` and `InspectorNavigationRail(items)` with `NavigationRail`/`NavigationRailItem`. Use Material selected state and cobalt `NavigationBarItemDefaults.colors`; do not paint a custom 64×32 pill.

- [ ] **Step 3: Choose bar or rail from available width**

Wrap the shell in `BoxWithConstraints`. Compact uses `Scaffold(bottomBar = ...)`; expanded uses a `Row` containing `InspectorNavigationRail` and `WorkspaceContent(Modifier.weight(1f))`. Continue hiding navigation while a full-screen detail overlay is open.

- [ ] **Step 4: Extend the workspace regression test**

Keep the four destination assertion and add:

```kotlin
@Test
fun `adaptive boundary does not change destination order`() {
    assertEquals(
        listOf("Observe", "Control", "Data", "More"),
        InspectorDestination.entries.map(InspectorDestination::label),
    )
    assertEquals(InspectorNavigationLayout.Bar, inspectorNavigationLayout(360.dp))
    assertEquals(InspectorNavigationLayout.Rail, inspectorNavigationLayout(840.dp))
}
```

- [ ] **Step 5: Run tests, compile, and commit**

```bash
./gradlew :sdk:ui-compose:testDebugUnitTest :sdk:ui-compose:compileDebugKotlin
git add sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/DevConsoleWorkspace.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorNavComponents.kt sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/DevConsoleWorkspaceTest.kt
git commit -m "feat(compose): add adaptive native navigation"
```

### Task 5: Replace custom navigation and shared UI primitives

**Files:**
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorNavComponents.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorUiComponents.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorListComponents.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorHeroComponents.kt`

- [ ] **Step 1: Replace `InspectorTopArea` with a Material top app bar composition**

Keep the function signature temporarily to avoid a broad screen rewrite. Render `TopAppBar(title = { Text(title) }, actions = ...)`; move `subLine` to a single `bodySmall` line immediately below the bar only when non-empty. Use `IconButton` for actions and Material minimum interactive sizing.

- [ ] **Step 2: Replace the pill search field**

Render `InspectorSearchBar` as `OutlinedTextField` with `singleLine = true`, a leading search icon, Material `shape = MaterialTheme.shapes.medium`, and `TextFieldDefaults.colors` mapped from the theme. Keep its current state and callback API.

- [ ] **Step 3: Replace terminal cards and uppercase labels**

Rename `TerminalCard` to `InspectorSection` and implement it as a plain `Column` with optional top/bottom divider; migrate call sites before deleting the old name. Change `TerminalSectionLabel` and `GroupLabel` to sentence case and `MaterialTheme.typography.labelMedium`.

- [ ] **Step 4: Flatten list rows**

Remove rounded row containers and per-row shadows. Use `ListItem`, `HorizontalDivider`, or an equivalent `Row` with 48dp minimum height. Preserve semantic roles, selected state, click behavior, trailing actions, and monospaced data fields.

- [ ] **Step 5: Compact hero components**

Replace 40sp hero values and rounded hero cards with a `SummaryStrip` composed of aligned values and dividers. Preserve collapse/expand actions only where they reveal real additional data.

- [ ] **Step 6: Compile and commit**

```bash
./gradlew :sdk:ui-compose:compileDebugKotlin
git add sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorNavComponents.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorUiComponents.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorListComponents.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorHeroComponents.kt
git commit -m "style(compose): replace terminal UI primitives"
```

### Task 6: Migrate Observe and Control

**Files:**
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorObserveScreen.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorObserveTrafficTab.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorObserveCrashesTab.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorControlScreen.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorMockRuleEditor.kt`
- Test: `sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/InspectorCaptureCategoryGatingTest.kt`
- Test: `sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/InspectorMockRuleEditorTest.kt`

- [ ] **Step 1: Convert Observe tabs and filters to Material roles**

Keep Traffic, Sockets, Push, Logs, and Crashes gating and selection logic. Use `PrimaryTabRow` or the existing row with Material typography and a 2dp cobalt indicator. Replace oversized filter pills with compact `FilterChip` components.

- [ ] **Step 2: Convert capture rows to edge-to-edge lists**

Keep stable keys, selection, bookmarking, evidence actions, status codes, timestamps, and durations. Use dividers between rows and a quiet `surface2` selected background with a cobalt leading indicator.

- [ ] **Step 3: Convert the evidence action to a standard extended FAB**

Use Material `ExtendedFloatingActionButton`; show one FAB only when the current route has a valid evidence action. Preserve `EvidenceFabScrollClearance` based on the Material FAB height plus scaffold inset.

- [ ] **Step 4: Convert Control sections and editor modality**

Keep snackbar result handling, capability gating, toggle actions, delete confirmation, and editor draft preservation. Use `ModalBottomSheet` for the mock-rule editor when the form fits; use the existing full-screen editor on compact height or when IME pressure requires it.

- [ ] **Step 5: Run affected tests and commit**

```bash
./gradlew :sdk:ui-compose:testDebugUnitTest --tests io.devconsole.ui.compose.InspectorCaptureCategoryGatingTest --tests io.devconsole.ui.compose.InspectorMockRuleEditorTest
git add sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorObserveScreen.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorObserveTrafficTab.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorObserveCrashesTab.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorControlScreen.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorMockRuleEditor.kt
git commit -m "style(compose): rebuild observe and control"
```

### Task 7: Migrate Data, More, and detail surfaces

**Files:**
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDataScreen.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorMoreScreen.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorFilesSection.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDatabaseSection.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDetailScaffold.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDetailHeader.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDetailFooterBar.kt`

- [ ] **Step 1: Convert Data to native sections and drill-ins**

Use sentence-case section headings, `ListItem` rows, dividers, and standard trailing icons. Preserve inspection gating, file/database actions, IME padding, sharing, SQL execution, redaction, and read-only messaging.

- [ ] **Step 2: Convert More to settings-shaped content**

Use top-level server status as a compact summary, standard filled/outlined buttons for Start/Stop, `ListItem` export rows, and grouped sections for browser access, exports, retention, sessions, and SDK health. Remove rounded hero cards and decorative metric circles.

- [ ] **Step 3: Standardize detail headers, footers, and predictive Back**

Use `TopAppBar` with a navigation icon that calls the existing close/back callback. Keep code formatting, search, copy, share, pin, compare, and evidence actions. Replace the plain detail-overlay `BackHandler` with `androidx.activity.compose.PredictiveBackHandler`; collect its progress flow, keep the visual transition a reduced-motion-safe fade, and invoke the same close callback when the gesture completes. Nested text/code scroll remains independent.

- [ ] **Step 4: Remove obsolete terminal types and card primitives**

After all call sites compile, delete `TerminalType`, `DevConsoleType.title`, `DevConsoleType.groupLabel`, `DevConsoleType.heroValue`, and the compatibility `TerminalCard` wrapper. Keep `rowTitle` monospace only where the title is captured data.

- [ ] **Step 5: Compile and commit**

```bash
./gradlew :sdk:ui-compose:compileDebugKotlin
git add sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose
git commit -m "style(compose): migrate data more and details"
```

### Task 8: Add preview coverage and verify Android behavior

**Files:**
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDesignSystemPreviews.kt`
- Modify: `sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/DevConsoleWorkspaceTest.kt`
- Modify: `DESIGN.md`
- Regenerate: `.impeccable/design.json`

- [ ] **Step 1: Add bounded preview variants**

Add dark/light compact previews for Observe, Control, Data, and More. Add at least one expanded-width workspace preview that shows the navigation rail and list/detail arrangement. Use representative populated, empty, error, and disabled-capability states already available in preview factories.

- [ ] **Step 2: Run the complete UI module test and compile suite**

```bash
./gradlew :sdk:ui-compose:testDebugUnitTest :sdk:ui-compose:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run static legacy-style checks**

```bash
git diff --check
rg -n 'B7ED65|427526|TerminalCard|TerminalType|RoundedCornerShape\(50\)|RoundedCornerShape\(99' sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose
```

Expected: no old green constants, obsolete terminal primitives, or percentage/pill corners remain except genuine status/filter chips documented inline.

- [ ] **Step 4: Refresh the durable design artifacts**

Replace `DESIGN.md` with the approved graphite/cobalt native-siblings design. Run Impeccable’s `document` workflow to regenerate `.impeccable/design.json` from the updated source of truth; verify the sidecar is no longer reported stale.

- [ ] **Step 5: Capture the bounded Android visual set**

Capture compact phone and expanded-width layouts in dark and light themes. Cover Observe list/detail, Control editor, Data drill-in, More server state, empty, error, and disabled-capability states. Perform one batched defect correction and at most one confirmation capture.

- [ ] **Step 6: Commit documentation and final Android verification changes**

```bash
git add DESIGN.md .impeccable/design.json sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDesignSystemPreviews.kt sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/DevConsoleWorkspaceTest.kt
git commit -m "docs: publish graphite cobalt design system"
```
