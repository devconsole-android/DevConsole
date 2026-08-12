# Web Dashboard Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the terminal-green, card-heavy browser dashboard with the approved graphite-and-cobalt desktop workbench while preserving every dashboard behavior.

**Architecture:** Keep the existing self-contained HTML/CSS/JavaScript delivery model and all functional element IDs. Establish the new visual contract in CSS custom properties, then migrate the shell and the three existing view archetypes: overview, split inspection, and form/configuration. Theme initialization becomes system-aware while explicit choices remain in `localStorage`.

**Tech Stack:** Static HTML, CSS custom properties, vanilla JavaScript, Kotlin/JUnit asset-contract tests, Gradle Android library build.

---

## File map

- Modify `sdk/server-ktor/src/main/resources/devconsole-web/dashboard.css`: graphite/cobalt tokens, flatter component rules, responsive behavior, accessibility states.
- Modify `sdk/server-ktor/src/main/resources/devconsole-web/index.html`: semantic shell and section classes while preserving IDs used by JavaScript.
- Modify `sdk/server-ktor/src/main/resources/devconsole-web/dashboard.js`: system-aware theme initialization and accessible theme state; no API/data-flow changes.
- Modify `sdk/server-ktor/src/test/kotlin/io/devconsole/server/ktor/DashboardAssetsTest.kt`: static design, theme, CSP, and semantic contract tests.
- Modify `docs/DESIGN_SPEC.md`: replace web-facing green instrument-panel rules with the approved native-siblings web contract.
- Modify `DESIGN.md`: replace the obsolete shared visual system after both web and Android plans are complete.
- Regenerate `.impeccable/design.json`: refresh the sidecar from the final `DESIGN.md` after both plans are complete.

### Task 1: Lock the new web contract with failing tests

**Files:**
- Modify: `sdk/server-ktor/src/test/kotlin/io/devconsole/server/ktor/DashboardAssetsTest.kt`
- Test: `sdk/server-ktor/src/test/kotlin/io/devconsole/server/ktor/DashboardAssetsTest.kt`

- [ ] **Step 1: Add palette and anti-slop asset assertions**

Add these tests inside `DashboardAssetsTest`:

```kotlin
@Test
fun `dashboard uses the graphite cobalt design tokens`() {
    val css = DashboardAssets.css().lowercase()

    listOf(
        "--ground: #111317",
        "--panel: #171a20",
        "--surface-2: #1d2129",
        "--ink: #eceef2",
        "--signal: #72a7ff",
    ).forEach { token -> assertTrue("missing $token", css.contains(token)) }

    listOf("#b7ed65", "#427526", "terminal-green", "instrument panel").forEach { legacy ->
        assertFalse("legacy design value remains: $legacy", css.contains(legacy))
    }
}

@Test
fun `dashboard defaults to system theme and persists only explicit choice`() {
    val script = DashboardAssets.js()

    assertTrue(script.contains("matchMedia('(prefers-color-scheme: dark)')"))
    assertTrue(script.contains("devconsole-theme"))
    assertTrue(script.contains("media.addEventListener('change'"))
}

@Test
fun `dashboard shell keeps stable functional ids and semantic landmarks`() {
    val dashboard = DashboardAssets.index()

    assertTrue(dashboard.contains("<header class=\"topbar\""))
    assertTrue(dashboard.contains("<nav class=\"rail\""))
    assertTrue(dashboard.contains("<main id=\"mainContent\""))
    assertTrue(dashboard.contains("aria-label=\"Inspector views\""))
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew :sdk:server-ktor:testDebugUnitTest --tests io.devconsole.server.ktor.DashboardAssetsTest
```

Expected: FAIL in `dashboard uses the graphite cobalt design tokens` because `#b7ed65` and the old dark tokens are still present.

- [ ] **Step 3: Commit the failing contract tests**

```bash
git add sdk/server-ktor/src/test/kotlin/io/devconsole/server/ktor/DashboardAssetsTest.kt
git commit -m "test(dashboard): define redesign contract"
```

### Task 2: Make web theming platform-aware

**Files:**
- Modify: `sdk/server-ktor/src/main/resources/devconsole-web/dashboard.js:966-999`
- Modify: `sdk/server-ktor/src/main/resources/devconsole-web/index.html:1-12`
- Test: `sdk/server-ktor/src/test/kotlin/io/devconsole/server/ktor/DashboardAssetsTest.kt`

- [ ] **Step 1: Replace theme initialization with system-aware resolution**

Replace the existing theme block in `dashboard.js` with:

```javascript
  const THEME_STORAGE_KEY = 'devconsole-theme';
  const themeMedia = window.matchMedia('(prefers-color-scheme: dark)');

  function currentTheme() {
    return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
  }

  function storedTheme() {
    try {
      const value = localStorage.getItem(THEME_STORAGE_KEY);
      return value === 'dark' || value === 'light' ? value : null;
    } catch {
      return null;
    }
  }

  function systemTheme() {
    return themeMedia.matches ? 'dark' : 'light';
  }

  function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    applyThemeIcon();
  }

  function applyThemeIcon() {
    const btn = $('themeToggle');
    if (!btn) return;
    const dark = currentTheme() === 'dark';
    btn.innerHTML = icon(dark ? 'sun' : 'moon');
    btn.title = dark ? 'Switch to light theme' : 'Switch to dark theme';
    btn.setAttribute('aria-label', btn.title);
    btn.setAttribute('aria-pressed', String(dark));
  }

  function initTheme() {
    applyTheme(storedTheme() || systemTheme());
    themeMedia.addEventListener('change', (event) => {
      if (!storedTheme()) applyTheme(event.matches ? 'dark' : 'light');
    });
  }

  function toggleTheme() {
    const next = currentTheme() === 'dark' ? 'light' : 'dark';
    try {
      localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch {
      /* storage unavailable */
    }
    applyTheme(next);
  }
```

- [ ] **Step 2: Make the HTML theme metadata neutral**

Keep `<meta name="color-scheme" content="dark light">` and do not add a hard-coded `data-theme` to `<html>`. This prevents the document from claiming a fixed default before JavaScript resolves the system preference.

- [ ] **Step 3: Run the focused tests**

Run the Task 1 command.

Expected: the system-theme test passes; the palette test still fails.

- [ ] **Step 4: Commit the theme behavior**

```bash
git add sdk/server-ktor/src/main/resources/devconsole-web/dashboard.js sdk/server-ktor/src/main/resources/devconsole-web/index.html
git commit -m "feat(dashboard): follow system theme by default"
```

### Task 3: Replace the web token and type foundation

**Files:**
- Modify: `sdk/server-ktor/src/main/resources/devconsole-web/dashboard.css:1-180`
- Test: `sdk/server-ktor/src/test/kotlin/io/devconsole/server/ktor/DashboardAssetsTest.kt`

- [ ] **Step 1: Replace the dark and light token blocks**

Use these exact core roles; keep existing semantic variable names so downstream JavaScript-generated markup requires no color changes:

```css
:root {
  --font-ui: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  --font-mono: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
  --ground: #111317;
  --panel: #171a20;
  --surface-2: #1d2129;
  --surface-3: #242a34;
  --code-bg: #0d0f13;
  --ink: #eceef2;
  --muted: #969da8;
  --text-3: #858d99;
  --line: #343a45;
  --border-strong: #4a5260;
  --signal: #72a7ff;
  --signal-ink: #0e1624;
  --warn: #f2b66d;
  --error: #ff7b72;
  --put: #72c4ce;
  --success: #7bc9a5;
  --signal-soft: rgba(114, 167, 255, 0.13);
  --error-soft: rgba(255, 123, 114, 0.13);
  --warn-soft: rgba(242, 182, 109, 0.13);
  --put-soft: rgba(114, 196, 206, 0.13);
  --json-key: #8cb8ff;
  --json-string: #a9d6b9;
  --json-number: #f2b66d;
  --json-boolean: #c7a7ff;
  --json-null: #969da8;
  --json-brace-0: #72a7ff;
  --json-brace-1: #f2b66d;
  --json-brace-2: #72c4ce;
  --json-brace-3: #eceef2;
  --json-brace-4: #c7a7ff;
  --elev-3: 0 8px 24px rgba(0, 0, 0, 0.28);
}

:root[data-theme="light"] {
  --ground: #f7f8fa;
  --panel: #ffffff;
  --surface-2: #eef1f5;
  --surface-3: #e4e8ee;
  --code-bg: #f2f4f7;
  --ink: #1c1f24;
  --muted: #68707b;
  --text-3: #5f6874;
  --line: #d5d9e0;
  --border-strong: #aab1bc;
  --signal: #245da8;
  --signal-ink: #ffffff;
  --warn: #8a5a12;
  --error: #b33a31;
  --put: #1e6d76;
  --success: #2f7255;
  --signal-soft: rgba(36, 93, 168, 0.10);
  --error-soft: rgba(179, 58, 49, 0.10);
  --warn-soft: rgba(138, 90, 18, 0.10);
  --put-soft: rgba(30, 109, 118, 0.10);
  --json-key: #245da8;
  --json-string: #2f7255;
  --json-number: #8a5a12;
  --json-boolean: #6a46a5;
  --json-null: #68707b;
  --json-brace-0: #245da8;
  --json-brace-1: #8a5a12;
  --json-brace-2: #1e6d76;
  --json-brace-3: #1c1f24;
  --json-brace-4: #6a46a5;
}
```

- [ ] **Step 2: Normalize typography and motion**

Set the body to `13px/1.5`, remove global letter spacing, change structural labels from forced uppercase to sentence case, and replace `fadein` translation with opacity-only `150ms`. Add:

```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
    scroll-behavior: auto !important;
  }
}
```

- [ ] **Step 3: Run the focused asset tests**

Expected: all Task 1 tests pass.

- [ ] **Step 4: Commit the token foundation**

```bash
git add sdk/server-ktor/src/main/resources/devconsole-web/dashboard.css
git commit -m "style(dashboard): adopt graphite cobalt foundation"
```

### Task 4: Simplify the web shell and rail

**Files:**
- Modify: `sdk/server-ktor/src/main/resources/devconsole-web/index.html:60-139`
- Modify: `sdk/server-ktor/src/main/resources/devconsole-web/dashboard.css:180-410`
- Test: `sdk/server-ktor/src/test/kotlin/io/devconsole/server/ktor/DashboardAssetsTest.kt`

- [ ] **Step 1: Preserve shell IDs while simplifying visible copy**

Keep `railToggle`, `themeToggle`, `modeToggle`, `viewNav`, every `view*` button ID, and every `navCount*` ID. Change group labels to sentence case and reduce topbar content to brand, device/build line, session status, live/pause control, and global actions. Do not remove the connection instruction carried by `sessionPill` on narrow widths.

- [ ] **Step 2: Replace rail selection and shell styling**

Implement the approved selection treatment:

```css
.rail { flex-basis: 216px; width: 216px; padding: 10px 8px 24px; background: var(--panel); }
.rail-group { margin-top: 14px; }
.rail-group-label { padding: 0 10px 5px; color: var(--text-3); font-size: 11px; font-weight: 600; }
.rail-group-label .text { text-transform: none; letter-spacing: 0; }
.rail-group-label .rule { display: none; }
.rail button { height: 34px; border-radius: 6px; padding: 0 10px; }
.rail button.active,
.rail button[aria-current="page"] { background: var(--surface-2); color: var(--ink); }
.rail button.active::before,
.rail button[aria-current="page"]::before { top: 7px; bottom: 7px; background: var(--signal); }
```

Remove the dashed shortcut card border; render shortcuts as muted footer text. Keep the `<780px` icon-rail behavior and `rail-hidden` behavior.

- [ ] **Step 3: Run `DashboardAssetsTest` and `git diff --check`**

Expected: PASS; no whitespace errors.

- [ ] **Step 4: Commit the shell migration**

```bash
git add sdk/server-ktor/src/main/resources/devconsole-web/index.html sdk/server-ktor/src/main/resources/devconsole-web/dashboard.css
git commit -m "style(dashboard): simplify shell and navigation"
```

### Task 5: Flatten overview and shared content primitives

**Files:**
- Modify: `sdk/server-ktor/src/main/resources/devconsole-web/index.html:141-176`
- Modify: `sdk/server-ktor/src/main/resources/devconsole-web/dashboard.css`
- Test: `sdk/server-ktor/src/test/kotlin/io/devconsole/server/ktor/DashboardAssetsTest.kt`

- [ ] **Step 1: Convert overview metric cards into a shared strip**

Retain all dynamic IDs. Wrap metrics in `.metric-strip`; each `.metric-card` becomes a separator-based `.metric` without a resting border, radius, or shadow.

```css
.metric-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border-block: 1px solid var(--line); }
.metric-strip .metric { min-width: 0; padding: 12px 14px; border-right: 1px solid var(--line); }
.metric-strip .metric:last-child { border-right: 0; }
.metric-strip .metric-value { font: 600 20px/1.2 var(--font-mono); }
.metric-strip .metric-label { margin-top: 3px; color: var(--muted); font-size: 11px; }
```

- [ ] **Step 2: Make headings and status copy human**

Use sentence-case headings in static HTML. Preserve server-provided values verbatim. Replace decorative section icons where the heading remains unambiguous without them; keep icons on actions and status where they improve scanning.

- [ ] **Step 3: Flatten shared row and section rules**

Use separators and selected-row backgrounds for `.row`, `.trace-row`, `.overview-*`, `.kv-row`, and `.metric-row`. Keep `data-id`, `role`, `tabindex`, selection, checkbox, and flag controls unchanged.

- [ ] **Step 4: Run focused tests and commit**

```bash
./gradlew :sdk:server-ktor:testDebugUnitTest --tests io.devconsole.server.ktor.DashboardAssetsTest
git add sdk/server-ktor/src/main/resources/devconsole-web/index.html sdk/server-ktor/src/main/resources/devconsole-web/dashboard.css
git commit -m "style(dashboard): flatten overview hierarchy"
```

### Task 6: Migrate split inspection views

**Files:**
- Modify: `sdk/server-ktor/src/main/resources/devconsole-web/index.html:177-456`
- Modify: `sdk/server-ktor/src/main/resources/devconsole-web/dashboard.css`
- Verify: `sdk/server-ktor/src/main/resources/devconsole-web/dashboard.js:2020-2210`

- [ ] **Step 1: Preserve the split-view behavioral contract**

Do not rename `.split-shell`, `.list-pane`, `.detail-pane`, `.splitter`, or any list/detail IDs. Keep separator ARIA, keyboard resizing, pointer capture, double-click reset, hidden-pane restoration, and detail scroll ownership intact.

- [ ] **Step 2: Restyle split shells as workbench panes**

```css
.split-shell { border: 1px solid var(--line); border-radius: 10px; background: var(--panel); }
.list-pane { background: var(--panel); }
.detail-pane { background: var(--ground); }
.splitter { width: 5px; background: var(--line); }
.splitter:hover,
.splitter:focus-visible,
.splitter.dragging { background: var(--signal); }
.row, .trace-row { border-radius: 0; border-inline: 0; border-top: 0; border-bottom: 1px solid var(--line); }
.row.selected, .trace-row.selected { background: var(--surface-2); box-shadow: inset 2px 0 var(--signal); }
```

- [ ] **Step 3: Normalize toolbars, filters, and search fields**

Use 32–36px controls, 6px corners, and sentence-case labels. Restrict pill geometry to status/count chips. Keep the simple/advanced visibility contract and every filter ID.

- [ ] **Step 4: Compress the Network request list into inline rows**

Remove the visible Method, Path, Duration, and Status column-title strip from the left Network pane while retaining accessible context for assistive technology. Render each transaction as one compact scanning line: method, path (flexing), duration, status, and evidence action. Do not stack field titles above values. Preserve selection, checkboxes, flags, host visibility, virtualized list behavior, and the detail-pane contract. Let the left pane start narrower so the request inspector receives more space.

- [ ] **Step 5: Exercise each split view manually**

Open Timeline, Network, WebSockets, Push, and Crashes. For each: select a row, scroll long detail content, drag the splitter, resize with arrows, double-click reset, hide each pane with Home/End, and restore it.

Expected: behavior matches the pre-redesign dashboard; only presentation changes.

- [ ] **Step 6: Commit split views**

```bash
git add sdk/server-ktor/src/main/resources/devconsole-web/index.html sdk/server-ktor/src/main/resources/devconsole-web/dashboard.css
git commit -m "style(dashboard): rebuild inspection workbench"
```

### Task 7: Migrate form, configuration, and overlay surfaces

**Files:**
- Modify: `sdk/server-ktor/src/main/resources/devconsole-web/index.html:457-719`
- Modify: `sdk/server-ktor/src/main/resources/devconsole-web/dashboard.css`

- [ ] **Step 1: Replace generic card-grid styling with semantic sections**

Keep `.cards-grid`, `.card-shell`, `.card-shell-head`, and `.card-shell-body` as compatibility hooks, but restyle them so borders appear only around meaningful grouped tasks:

```css
.cards-grid { gap: 18px 24px; }
.card-shell { border: 0; border-radius: 0; background: transparent; overflow: visible; }
.card-shell-head { min-height: 36px; padding: 0 0 8px; border-bottom: 1px solid var(--line); }
.card-shell-title { font-size: 14px; text-transform: none; letter-spacing: 0; }
.card-shell-body { padding: 14px 0 0; }
.card-shell[data-contained="true"] { padding: 16px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); }
```

Add `data-contained="true"` only to sections that form one bounded task, such as the request composer and modal-like editors.

- [ ] **Step 2: Normalize inputs, dialogs, menus, banners, toasts, and empty states**

Inputs use `code-bg`, a 6px radius, and cobalt focus. Dialogs and menus keep `--elev-3`; resting sections do not. Empty states use one icon, a sentence-case title, a concise explanation, and one next action. Error/warn banners keep semantic left accents without tinted full-card decoration.

- [ ] **Step 3: Verify responsive and coarse-pointer rules**

At 1180, 960, 780, 620, and 480px, verify every action remains reachable. Under `@media (pointer: coarse)`, ensure interactive targets reach at least 44 CSS px without increasing desktop row density.

- [ ] **Step 4: Commit remaining surfaces**

```bash
git add sdk/server-ktor/src/main/resources/devconsole-web/index.html sdk/server-ktor/src/main/resources/devconsole-web/dashboard.css
git commit -m "style(dashboard): refine forms and state surfaces"
```

### Task 8: Document and verify the web redesign

**Files:**
- Modify: `docs/DESIGN_SPEC.md`
- Test: `sdk/server-ktor/src/test/kotlin/io/devconsole/server/ktor/DashboardAssetsTest.kt`

- [ ] **Step 1: Update the binding web design rules**

Replace phosphor-green, forced-uppercase, card-grid, and dark-default statements with the approved graphite/cobalt roles, sentence-case hierarchy, flat sections, and system-aware theme behavior. Preserve CSP, stable-ID, split-pane, density, accessibility, and breakpoint constraints.

- [ ] **Step 2: Run the complete server module verification**

```bash
./gradlew :sdk:server-ktor:testDebugUnitTest :sdk:server-ktor:verifyDashboardAssets
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run static checks**

```bash
git diff --check
rg -n '#b7ed65|#427526|terminal-green|instrument panel' sdk/server-ktor/src/main/resources/devconsole-web docs/DESIGN_SPEC.md
```

Expected: `git diff --check` is silent; the legacy-design search returns no matches except historical migration text intentionally retained in changelog material outside these paths.

- [ ] **Step 4: Capture the bounded web visual set**

Capture desktop and narrow screenshots in dark and light themes, covering Overview, Network list/detail, Composer, an empty state, an error state, and a modal. Perform one batched defect correction and at most one confirmation capture.

- [ ] **Step 5: Commit documentation and web verification changes**

```bash
git add docs/DESIGN_SPEC.md sdk/server-ktor/src/test/kotlin/io/devconsole/server/ktor/DashboardAssetsTest.kt
git commit -m "docs(dashboard): record native sibling design"
```
