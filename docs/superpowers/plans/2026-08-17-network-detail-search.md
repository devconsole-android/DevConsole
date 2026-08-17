# Network Detail Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add scoped, multi-select request/response search to the Android Compose Network detail screen with key/value modes, exact highlighting, match navigation, and preserved formatted bodies.

**Architecture:** Keep query, scope, mode, and current-match state in `InspectorObserveDetailScreen`. Add a pure candidate/match model for deterministic matching and document ordering. Pass matches as rendering metadata so existing `Formattable`, JSON-tree, raw, code, and key/value body types remain intact.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, existing DevConsole Compose primitives, JUnit4.

---

### Task 1: Add the pure search model and red tests

**Files:**
- Create: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDetailSearch.kt`
- Create: `sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/InspectorDetailSearchTest.kt`

- [ ] **Step 1: Define the search API and tests first**

Create `InspectorSearchField`, `InspectorSearchMode`, `InspectorDetailSearchSection`, `InspectorSearchCandidate`, and `InspectorDetailSearchMatch`. The matcher API must accept candidates, a query, a selected-section key set, and a mode, then return ordered matches with zero-based `ordinal`, `start`, and exclusive `end` offsets.

Write tests covering:

```kotlin
@Test
fun `keys mode matches keys but not values and keeps exact ranges`() {
    val candidates = listOf(
        InspectorSearchCandidate("reqh", "row:0", InspectorSearchField.KEY, "Content-Type"),
        InspectorSearchCandidate("reqh", "row:0", InspectorSearchField.VALUE, "application/json"),
    )

    val matches = searchInspectorCandidates(candidates, "content", setOf("reqh"), InspectorSearchMode.KEYS)

    assertEquals(1, matches.size)
    assertEquals(0, matches.single().start)
    assertEquals(7, matches.single().endExclusive)
}

@Test
fun `multi section matching follows candidate order and values mode`() {
    val candidates = listOf(
        InspectorSearchCandidate("req", "line:0", InspectorSearchField.VALUE, "userId: 1"),
        InspectorSearchCandidate("res", "line:0", InspectorSearchField.VALUE, "userId: 1"),
        InspectorSearchCandidate("general", "row:0", InspectorSearchField.VALUE, "userId: 1"),
    )

    val matches = searchInspectorCandidates(
        candidates,
        "userid",
        setOf("req", "res"),
        InspectorSearchMode.VALUES,
    )

    assertEquals(listOf("req", "res"), matches.map { it.sectionKey })
    assertEquals(listOf(0, 1), matches.map { it.ordinal })
}

@Test
fun `previous and next navigation wrap around`() {
    assertEquals(0, nextInspectorMatchIndex(current = 2, total = 3))
    assertEquals(2, previousInspectorMatchIndex(current = 0, total = 3))
}
```

- [ ] **Step 2: Run the focused test and verify it fails for the missing API**

Run:

```bash
./gradlew :sdk:ui-compose:testDebugUnitTest --tests io.devconsole.ui.compose.InspectorDetailSearchTest --no-daemon
```

Expected: compilation/test failure because the matcher and navigation helpers do not exist yet.

- [ ] **Step 3: Implement the minimal pure matcher**

Implement case-insensitive, non-overlapping substring ranges using `regionMatches`, filter by selected section and field mode, assign ordinals after filtering, and return an empty list for blank queries. Add wrap-around helpers that return `0` for next/`total - 1` for previous when the list is non-empty.

- [ ] **Step 4: Re-run the focused test and verify it passes**

Run the same Gradle command. Expected: all focused tests pass.

- [ ] **Step 5: Commit the pure search model**

```bash
git add sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDetailSearch.kt sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/InspectorDetailSearchTest.kt
git commit -m "feat: add network detail search matcher"
```

### Task 2: Build candidates from detail bodies and integrate Network-only search state

**Files:**
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDetailSearch.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorObserveDetail.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorObserveNetDetail.kt`
- Modify: `sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/InspectorDetailSearchTest.kt`

- [ ] **Step 1: Add failing candidate-extraction tests**

Test that headers produce `row:<index>` key/value candidates, code/raw lines produce `line:<index>` value candidates, and nested JSON produces a key candidate for an object property plus a scalar-value candidate with the property path and ancestor paths. Test that a formatted body remains an `InspectorDetailSectionBody.Formattable` body after search resolution.

- [ ] **Step 2: Run the focused test and verify the new extraction assertions fail**

Run:

```bash
./gradlew :sdk:ui-compose:testDebugUnitTest --tests io.devconsole.ui.compose.InspectorDetailSearchTest --no-daemon
```

- [ ] **Step 3: Implement body candidate extraction**

Traverse `KeyValues`, `Code`, and `Formattable` bodies in display order. For JSON, emit quoted object keys and rendered scalar values while retaining each row path and ancestor paths. Treat XML/plain raw lines as value candidates. Add the four network scope options and a `searchOptions` field to `ObserveDetailContent`; set it only in `netDetailContent` with all four sections selected and `KEYS` as the default mode.

- [ ] **Step 4: Split Network search resolution from legacy non-Network resolution**

Keep existing search behavior for other capture detail kinds. For Network detail, resolve only configured sections, keep all original rows/lines, attach ordered `InspectorDetailSearchMatch` lists, auto-expand matching sections, and never replace `Formattable` with `Code`. Excluded sections must retain their normal display and must not show `no match` metadata.

- [ ] **Step 5: Wire saveable state and navigation**

Add saveable selected-section keys, match mode, current-match index, and bottom-sheet visibility to `InspectorObserveDetailScreen` when `content.searchOptions` is present. Reset the current index when query, scope, or mode changes. Use `nextInspectorMatchIndex` and `previousInspectorMatchIndex` for wrap-around navigation. Keep the existing legacy query path for content without Network search options.

- [ ] **Step 6: Re-run tests and commit the state/resolution integration**

Run the focused test command and then:

```bash
./gradlew :sdk:ui-compose:testDebugUnitTest --no-daemon
```

Commit the verified integration:

```bash
git add sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDetailSearch.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorObserveDetail.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorObserveNetDetail.kt sdk/ui-compose/src/test/kotlin/io/devconsole/ui/compose/InspectorDetailSearchTest.kt
git commit -m "feat: scope network detail search"
```

### Task 3: Add the options bottom sheet and search navigation controls

**Files:**
- Create: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDetailSearchOptionsSheet.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDetailSearchField.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorObserveDetail.kt`

- [ ] **Step 1: Add the bottom-sheet composable**

Use `ModalBottomSheet` with a draft `Set<String>` and draft `InspectorSearchMode`. Render four 48dp-minimum checkbox rows, Select all/Clear all actions, three mutually exclusive mode chips, and Cancel/Apply actions. Disable Apply when no section is selected. Apply must return both draft values; Cancel must only dismiss.

- [ ] **Step 2: Add search-field controls**

Keep the existing search input. For Network detail, add 48dp previous and next icon buttons, disabled when no matches, plus a scope filter chip that opens the bottom sheet. Show `current/total`, `0/0` for no matches, and a scope summary such as `All request + response`, `Response body`, or `2 sections`. Use rotated existing chevron glyphs for arrows so no icon dependency is added.

- [ ] **Step 3: Render and connect the sheet**

Open the sheet from the scope chip, pass the current active configuration, and on Apply update the saveable state, reset the current match, and dismiss the sheet. Add accessible labels/content descriptions for scope, previous, next, checkboxes, modes, Cancel, and Apply.

- [ ] **Step 4: Build and run the existing unit suite**

```bash
./gradlew :sdk:ui-compose:testDebugUnitTest --no-daemon
```

Commit the verified controls:

```bash
git add sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDetailSearchOptionsSheet.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDetailSearchField.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorObserveDetail.kt
git commit -m "feat: add network search controls"
```

### Task 4: Add exact highlighting and preserve formatted bodies

**Files:**
- Create: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorSearchHighlight.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorKeyValueList.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorCodeBlock.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorCodeFullScreenOverlay.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorFormattableBody.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorBodyFormat.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorObserveDetail.kt`

- [ ] **Step 1: Add failing rendering-model tests**

Test that search metadata for a formatted JSON response remains `InspectorDetailSectionBody.Formattable`, that a key match targets the JSON row key text, and that a value match targets the scalar text with exact offsets.

- [ ] **Step 2: Run the focused test and verify it fails against the current flat-search behavior**

Run:

```bash
./gradlew :sdk:ui-compose:testDebugUnitTest --tests io.devconsole.ui.compose.InspectorDetailSearchTest --no-daemon
```

- [ ] **Step 3: Implement reusable exact-range highlighting**

Add `InspectorSearchHighlight(start, endExclusive, active)` and a monospace `AnnotatedString` renderer that applies a signal-soft background to inactive matches and a stronger signal background to the active match. Clamp invalid ranges and leave text unchanged when there are no highlights.

- [ ] **Step 4: Apply highlights without changing body shape**

Pass match metadata and current ordinal into key/value, code, and formattable renderers. Highlight key and value spans separately. Extend `JsonTreeRow` with key/value search highlight ranges, preserve existing mock-diff row highlighting, and apply search highlights to JSON tree key/value text. Search-driven matches must not set the old row-only `highlighted` flag or replace a Formattable body with Code.

- [ ] **Step 5: Keep raw/XML/JSON views usable**

For XML and raw lines, decorate the existing code lines. For JSON formatted mode, decorate the existing tree rows. Keep the Raw/Formatted toggle and its selected state intact while the query is active; the response must never collapse to a one-line or flat representation as a side effect of searching.

- [ ] **Step 6: Re-run the full unit suite and commit the rendering fix**

```bash
./gradlew :sdk:ui-compose:testDebugUnitTest --no-daemon
```

Commit after the output reports success:

```bash
git add sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorSearchHighlight.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorKeyValueList.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorCodeBlock.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorCodeFullScreenOverlay.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorFormattableBody.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorBodyFormat.kt sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorObserveDetail.kt
git commit -m "fix: preserve formatted network search results"
```

### Task 5: Bring the active match into view and verify the Android UI

**Files:**
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorKeyValueList.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorCodeBlock.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorFormattableBody.kt`
- Modify: `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/InspectorDetailPreviews.kt`

- [ ] **Step 1: Add target scrolling**

Use `LazyListState` for code and formatted body lists. When the active ordinal changes, locate its `line:<index>` or JSON path target and animate that row into view. Use `BringIntoViewRequester` for key/value rows in the outer detail scroll. Force-expand JSON ancestor paths for selected matches while retaining user expansion overrides otherwise.

- [ ] **Step 2: Add realistic preview data**

Update the detail preview with nested request/response JSON and repeated matches so the formatted tree, exact highlights, active match, and navigation controls are visible in previews.

- [ ] **Step 3: Run targeted unit/build verification**

```bash
./gradlew :sdk:ui-compose:testDebugUnitTest :samples:compose-app:assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL and zero failed tests.

- [ ] **Step 4: Install and verify the sample on the running emulator**

Install the debug APK, open the Network tab and a transaction detail, then verify:

- default scope is all four request/response sections;
- bottom sheet supports selecting multiple sections and cancelling without changes;
- Keys is selected by default and Values/Keys + values change matches;
- previous/next update the current match and scroll to it;
- exact text is highlighted;
- General/Timing/Redactions never contribute matches;
- formatted JSON remains a tree after searching.

- [ ] **Step 5: Inspect the final diff and commit verification-safe changes**

```bash
git status --short
git diff --check
git diff --stat
```

Commit any final source/preview fixes only after the targeted build and tests pass.
