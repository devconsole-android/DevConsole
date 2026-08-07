# DevConsole — Dashboard Design Spec

**Purpose of this doc:** a self-contained brief for anyone *validating* the current DevConsole UI
or *redesigning* it without breaking the engineering constraints that make it shippable. It captures identity, tokens, layout, components, every screen, interaction rules,
accessibility bar, and the hard limits. Read the **Non-negotiable constraints** section first —
several "obvious" design moves (web fonts, icon CDNs, inline scripts) are structurally impossible
here and a redesign that assumes them cannot ship.

---

## 1. What the product is

DevConsole is a **LAN-accessible, on-device developer console** for Android apps (debug builds
only). The app embeds a local HTTP/WebSocket server and serves a **browser dashboard** so a
developer can inspect network traffic, WebSocket frames, push events, logs, crashes and ANRs,
feature flags, app state, SQLite, files, mock rules, a request composer, and a QA evidence tray —
from a browser, with no IDE or desktop app. There is also a **native in-app inspector** (Jetpack
Compose) that mirrors a subset of this on the phone itself.

Two surfaces, one design language:

| Surface | Tech | Audience | Scope |
|---|---|---|---|
| **Web dashboard** | Static HTML/CSS/JS served by embedded Ktor server | Developer at a laptop on same LAN | Full: 16 views |
| **Android in-app inspector** | Jetpack Compose (`ui-compose`) | Developer on the device | Subset: Observe (5 tabs) / Control / Data / More — see §4c for the full parity table |

Tone: **developer tool, dense, terminal-adjacent, trustworthy.** Not consumer-glossy. Think a
well-built devtools panel, not a marketing dashboard. It leans on a single signal-green accent
against near-black (dark) or bone-white (light).

---

## 2. Non-negotiable constraints (a redesign MUST honor these)

1. **Content-Security-Policy — self-contained only.** The server sends:
   ```
   default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
   img-src 'self' data: blob:; connect-src 'self' ws: wss:
   ```
   Consequences:
   - **No external anything** — no Google Fonts, no icon CDNs, no remote CSS/JS/images. Everything
     ships from the app's own resources.
   - **No inline `<script>` and no `on*` HTML attributes.** All JS lives in `/assets/dashboard.js`;
     event wiring is `.onclick =` in JS. A test (`DashboardAssetsTest`) enforces this — inline
     handlers fail the build.
   - Inline **styles are allowed** (`style-src 'unsafe-inline'`). Images may be `data:`/`blob:`.
   - WebSocket to same origin only (`connect-src 'self' ws: wss:`).
2. **Fonts are system stacks only.** `--font-ui: -apple-system, BlinkMacSystemFont, "Segoe UI",
   Roboto, Helvetica, Arial, sans-serif` and `--font-mono: ui-monospace, "SF Mono", Menlo,
   Consolas, monospace`. A redesign cannot introduce a custom typeface without bundling it as a
   `data:`-URI `@font-face` (heavy; avoid).
3. **Green identity is fixed.** `--signal: #b7ed65` (dark) / `#427526` (light) is the brand. The
   user has explicitly chosen: **keep DevConsole green.** Don't reskin to blue/purple/etc.
4. **Rail navigation stays.** Left vertical rail with grouped sections is the chosen nav. The
   direction is "keep the rail, add polish," not "replace with topnav/tabs."
5. **Dual theme, real.** Dark is default; light is a genuine second theme via
   `:root[data-theme="light"]`, toggled at runtime (stamps `data-theme` on `<html>`). Every color
   must be a token so both themes stay correct — **no raw hex in components.**
6. **Accessibility bar: WCAG 2.2 AA.** Text contrast ≥ 4.5:1 (≥ 3:1 large), focus rings visible
   (≥ 3:1), Android touch targets ≥ 48dp, web interactive targets comfortable for `pointer:coarse`.
   This has already caught real regressions (see §9) — treat it as a gate, not a nicety.
7. **The 3-file split for the web app is enforced:** `index.html` + `/assets/dashboard.css` +
   `/assets/dashboard.js`. Keep it.

---

## 3. Design tokens (source of truth)

All from `sdk/server-ktor/src/main/resources/devconsole-web/dashboard.css`.

### 3.1 Color — Dark (default)

| Token | Value | Role |
|---|---|---|
| `--ground` | `#0b0e0d` | App background |
| `--panel` | `#151a18` | Card / panel surface |
| `--surface-2` | `#1b231e` | Raised surface |
| `--surface-3` | `#212b24` | Higher surface / hover |
| `--code-bg` | `#10140f` | Code / input background |
| `--ink` | `#e7e5d8` | Primary text |
|       |       |      |
| `--muted` | `#92968e` | Secondary text |
| `--text-3` | `#7d8479` | Tertiary text (labels) — **5.03:1, do not darken** |
| `--line` | `#344039` | Hairline border |
| `--border-strong` | `#4a5a4f` | Emphasized border |
| `--signal` | `#b7ed65` | **Brand accent** (active nav, primary CTA, focus) |
| `--signal-ink` | `#0b0e0d` | Text/icon on signal fill |
| `--warn` | `#f0b45c` | Warning |
| `--error` | `#ff715e` | Error / destructive |
| `--put` | `#7fd0c9` | Teal accent (PUT method, secondary) |
| `--signal-soft` | `rgba(183, 237, 101, 0.11)` | Signal tint (active/hover fills) |
| `--error-soft` | `rgba(255, 113, 94, 0.13)` | Error tint |
| `--warn-soft` | `rgba(240, 180, 92, 0.13)` | Warning tint |
| `--put-soft` | `rgba(127, 208, 201, 0.13)` | PUT/teal tint |
| `--elev-3` | `0 4px 8px 3px rgba(0,0,0,0.15), 0 1px 3px rgba(0,0,0,0.3)` | Elevation (one Material elevation, same value in both themes) |

### 3.2 Color — Light (`[data-theme="light"]`)

| Token | Value |
|---|---|
| `--ground` | `#f3f6ee` |
| `--panel` | `#ffffff` |
| `--surface-2` | `#eef2e6` |
| `--surface-3` | `#e3e9d7` |
| `--code-bg` | `#f6f8f1` |
| `--ink` | `#182018` |
| `--muted` | `#5b6459` |
| `--text-3` | `#656c5f` (**4.98:1, do not lighten**) |
| `--line` | `#dde3d1` |
| `--border-strong` | `#c3cdb4` |
| `--signal` | `#427526` (**5.52:1 vs. `#ffffff`, 5.05:1 vs. `--ground`; darkened from the earlier `#4f8f28`, which failed AA at 3.97:1/3.63:1 — do not lighten back**) |
| `--signal-ink` | `#ffffff` |
| `--warn` | `#93630a` |
| `--error` | `#b6392a` |
| `--put` | `#1f7a6c` |
| `--signal-soft` | `rgba(66, 117, 38, 0.1)` |
| `--error-soft` | `rgba(182, 57, 42, 0.1)` |
| `--warn-soft` | `rgba(147, 99, 10, 0.1)` |
| `--put-soft` | `rgba(31, 122, 108, 0.1)` |

`--elev-3` is not redeclared in the light block — it is one shared token inherited from `:root`
(`0 4px 8px 3px rgba(0,0,0,0.15), 0 1px 3px rgba(0,0,0,0.3)`, see §3.1).

### 3.3 Syntax-highlight palette (JSON viewer, both themes)

`--json-key`, `--json-string`, `--json-number`, `--json-boolean`, `--json-null`, and a 5-step
rainbow-brace scale `--json-brace-0..4`. Dark keys teal `#8fd6c4`, numbers amber `#f0b45c`,
booleans green `#b7ed65`. Keep semantic mapping identical in a redesign; recolor only via tokens.
The light theme now defines all five `--json-brace-0..4` steps. `--json-brace-4` was the missing
step and previously fell through to the dark value (`#92968e`); it is now `#6a4c93`, a plum not
derived from any other tone in the ladder, so depth still reads distinctly when nesting cycles back
to brace-0 every 5 levels. Contrast: ~6.9:1 on `#ffffff` and ~6.4:1 on `--code-bg` (`#f6f8f1`), both
comfortably past the 4.5:1 AA floor the rest of this block is held to.

### 3.4 Radius, type, spacing

- **Radius:** no `--r*` custom properties — the Aug-5 design refresh deleted those tokens in favor of
  literal per-component radii (two stale `var(--r)`/`var(--r-sm)` references survived into the
  evidence-thumb/crumb/badge additions and have since been replaced with literals). In practice:
  `12px` on outer card/split shells (`.card-shell`, `.split-shell`), `8px` on standard controls
  (buttons, inputs, most chrome), `6px` on small nested elements (dropdown items, kv-grid,
  evidence thumbs, crumbs), `4px` on compact text badges (`.row-badge`, `.detail-badge`,
  `.badge-pill`, `.evidence-thumb-badge`), `0` inside joined segmented controls (`.seg` children
  share one outer border and must not each round), and `99px` pills reserved for status/chip/badge
  shapes (session pill, filter chips, toggle switch, dots). Not tight-cornered — pills only for
  status/chips still holds, but the base shape language is now clearly rounded (8–12px), not the
  old 3–4px.
- **Type scale (px, in use):** 10, 10.5, 11, 11.5, 12, 12.5, 13.5, 15, 16, 20. Body ~12–13.5,
  labels 10.5–11 uppercase with `letter-spacing: .06em`, view titles ~20. Mono for URLs, codes,
  payloads, numeric columns.
- **Spacing:** ad-hoc but small and consistent — gaps of 6–16px, card padding 16px, main padding
  `20px 24px 48px`. Follow a 4px base. Density is a feature; don't inflate whitespace.
- **Z-index scale:** `1`, `30`, `60`, `80`, `1000` (modal/overlay top).
- **Breakpoints — a rationalised, width-descending ladder (task E1).** What used to be an ad hoc
  960/780/680/620/560/480/1000/1180 set scattered through `dashboard.css` is now a single documented
  ladder plus two container queries; every value was doing real, distinct work, so none were dropped:

  | Width | Selector | Effect |
  |---|---|---|
  | 1180px | `.sbs-grid` | Side-by-side request/response (or diff) panes stack to one column. |
  | 1044px | `.cards-grid` (container query) | A `data-span="3"` card folds to span 2. Reacts to the grid's own rendered width — e.g. rail collapsed vs. expanded — not just the window. |
  | 1000px | `.view-badge` | The view-badge chip next to the view title hides — below this width there's no longer room for badge + title + hint on one baseline (`.view-hint` is the one that must stay, per §2). |
  | 960px | `.split-shell`, `.host-line` | List+detail split panes stack (list on top); the topbar's host/build info hides, leaving the session-pill's status text as the only connect instruction from here down — it must never also go `display:none`. |
  | 780px | `.rail` | Collapses to a 58px icon-only strip (labels/counts/shortcuts hide); `.main-inner` side padding tightens 24px → 14px; `.view` bottom padding grows so content clears the shorter viewport. |
  | 692px | `.cards-grid` (container query) | Any remaining `data-span` card folds to span 1 (full width). |
  | 680px | `.session-pill` | First width cut, 260px → 150px, as the topbar's shrinkable slot. |
  | 620px | `.col-duration` | The list row's "duration" column hides (still visible once a row is opened in the detail pane). |
  | 560px | `.stream-seg` | LIVE/PAUSED labels hide, icon-only. |
  | 480px | `.session-pill`, `.brand-name`, `.topbar` | Second width cut (150px → 108px); wordmark shrinks; topbar wraps to two rows — below this width its non-shrinkable groups (rail-toggle + brand + stream-seg + the four topbar-action buttons) no longer fit on one line even with the session-pill collapsed to nothing, and nothing here is ever allowed to just disappear. |

  Plus `prefers-reduced-motion` and `pointer:coarse` blocks, independent of width. This is now a
  **mobile-first** pass (task E1, done together with E6's density tokens below): no horizontal body
  overflow at 375/768/1024 on any of the 16 views — see §9 for what "resolved" means here and what's
  still open.

### 3.5 Density tokens (Simple vs. Advanced, task E6)

Seven CSS custom properties, scoped so ordinary usage stays byte-identical to the pre-E6 hard-coded
values and only `body.mode-simple` relaxes them:

| Token | Default (Advanced / body) | `body.mode-simple` | Governs |
|---|---|---|---|
| `--d-body-font` | `13px` | `14.5px` | Base body text size |
| `--d-row-h` | `34px` | `44px` | List-row height (Network, WebSockets, Timeline, Push, Crashes) |
| `--d-row-font` | `12px` | `13px` | List-row text size |
| `--d-row-main-font` | `12.5px` | `13.5px` | List-row's primary (mono) column text size |
| `--d-trace-h` | `44px` | `54px` | Timeline/trace row minimum height |
| `--d-card-pad` | `16px` | `20px` | Card padding |
| `--d-card-gap` | `12px` | `16px` | Card-grid gap |

Advanced mode never had these values change — they're the same numbers the CSS hard-coded before this
task, now expressed as tokens. Simple mode relaxes all seven under the `body.mode-simple` class, so
density itself is the feature difference between the two modes, not just which rail items and toolbar
controls are visible. E1 and E6 interact (a mode switch changes row height, which the E1 virtualizer
must recompute mid-scroll rather than assume) — see §9.

---

## 4. Layout (web)

```
┌───────────────────────────────────────────────────────────────┐
│ TOPBAR  brand · status(server-info) · ws-status · record ·     │  ~56px, icon-btns 32×32
│         shortcuts · theme-toggle                     [right]    │
├──────────┬────────────────────────────────────────────────────┤
│  RAIL    │  MAIN (max-width 1560px, centered)                  │
│  228px   │   ┌ view-head: h1 title + hint ─────────────┐       │
│  grouped │   │ toolbar (search / segmented / chips /    │       │
│  nav     │   │          actions — align-items: flex-end)│       │
│          │   ├ content ────────────────────────────────┤       │
│  WORKBEN │   │  split:  [ list pane | ⇔ | detail pane ] │       │
│  TRAFFIC │   │   or     card-grid (auto-fit 340px)      │       │
│  SIGNALS │   └──────────────────────────────────────────┘      │
│  CONTROL │                                                      │
│  DATA    │                                                      │
│  REPORT  │                                                      │
└──────────┴────────────────────────────────────────────────────┘
```

- **Rail groups & items** (6 groups, **16 views**):
  - **WORKBENCH** — Overview
  - **TRAFFIC** — Network, WebSockets
  - **SIGNALS** — Timeline, **Crashes**, Push, SDK Health
  - **CONTROL** — Composer, Mocks, Capture rules
  - **DATA** — State & Flags, Preferences, Database, Files
  - **REPORT** (renamed from EXPORT) — **Evidence tray**, Session & Security
- Active item: signal-green pill/highlight. Group labels: uppercase 10.5px `--text-3`.
- **Split view** (`.split`): resizable list + detail with a drag `.splitter` (5px). Stacks vertically
  under 960px. Used by list-heavy views (Network, WebSockets, Timeline, Push, Crashes).
- **Card grid** (`.card-grid`): `repeat(auto-fit, minmax(min(100%,340px), 1fr))`. Used by
  form/data views (Files, SDK Health, State, Prefs, Composer, Session).
- **Toolbar** (`.toolbar`): flex row, wraps, `align-items: flex-end` so label-stacked fields
  (uppercase label over input) share a bottom baseline with bare buttons. (This was a bug — see §9.)

## 4b. Layout (Android in-app inspector)

- **Bottom nav, 4 destinations:** `01 Observe · 02 Control · 03 Data · 04 More` (numbered + label,
  active = green pill). Max-5 rule respected.
- **Observe** = tabbed live activity, now **five** tabs: `TRAFFIC · SOCKETS · PUSH · LOGS · CRASHES`
  (`ObserveTab` enum, `sdk:ui-compose`) — at the documented max-5 ceiling for this surface. Traffic
  has a search field + two chip rows (method: ALL/GET/POST/PUT/PATCH/DELETE; status:
  ALL/2XX/3XX/4XX/5XX/FAILED) and a list of rows (checkbox · method · host/path · status ·
  duration). Crashes lists CRASH/ANR events with kind/thread/timestamp; its detail renders the
  all-thread dump inside the existing full-screen Code overlay — see
  [CRASH_AND_ANR.md](CRASH_AND_ANR.md#the-crashes-surface).
- **Control** = mock rules + feature flags only. Composer is **intentionally absent** on Android —
  "typing URLs on a phone is hostile," so cloning a request hands off to the dashboard instead —
  and capture rules have no on-device management UI either.
- **Data** = preferences, database, files, and app state providers.
- **More** screen: connect QR + session code, export actions (HAR / Postman / session-ZIP),
  screenshot capture, retained runs, health, browser sessions, retention, LAN warning. Must scroll
  (was a bug — see §9).
- Compose specifics: chips use `minimumInteractiveComponentSize()` (48dp) even when visually
  smaller; `isVisible`-style show/hide; status badges `maxLines=1` + ellipsis to avoid mid-word wrap.

## 4c. Cross-surface parity (E5)

The web dashboard has 16 views; the Android in-app inspector has 4 destinations (Observe's 5 tabs
counted individually below). E5 asked for a documented decision on which web views the Android
inspector mirrors, and matching names where it does. The decision:

| Web view (rail group) | Android mirror | Naming | Notes |
|---|---|---|---|
| Overview (Workbench) | — | not mirrored | No on-device equivalent; the closest analog (health, session state) lives on More instead of a landing dashboard. |
| Network (Traffic) | Observe → Traffic | aligned | |
| WebSockets (Traffic) | Observe → Sockets | aligned (shortened) | |
| Timeline (Signals) | Observe → Logs | **not aligned** | Same underlying shape (any captured event, by kind/source/summary) under a different name. Pre-dates this design; documented here as a known naming mismatch rather than silently left unmentioned. Reconciling the name is a code change, out of scope for this documentation pass. |
| Crashes (Signals) | Observe → Crashes | aligned | New on both surfaces in this design. |
| Push (Signals) | Observe → Push | aligned | |
| SDK Health (Signals) | More → SDK health | aligned (different surface) | |
| Composer (Control) | — | **deliberately not mirrored** | "Composer is intentionally absent" — typing URLs on a phone is hostile; cloning a request from Observe hands off to the dashboard instead. |
| Mocks (Control) | Control | aligned (folded into one screen with Flags) | |
| Capture rules (Control) | — | not mirrored | No on-device capture-rule management UI. |
| State & Flags (Data) | Control (flags) + Data (state providers) | split across two destinations | Feature flags live on Control next to Mocks; app state providers live on Data next to Preferences/Database/Files. |
| Preferences (Data) | Data | aligned | |
| Database (Data) | Data | aligned | |
| Files (Data) | Data | aligned | |
| Evidence tray (Report) | — | not mirrored as a screen | Flagging exists inline on Observe's network and crash detail screens and writes to the same durable store the web tray reads, so the two surfaces agree. What Android lacks is the tray itself: no report draft, no bundle export, no clipboard formats, and no flag affordance for timeline/socket/push/screenshot items. A device-only workflow can collect evidence but not file it. |
| Session & Security (Report) | More (session code/QR, browser sessions, retention, LAN warning, retained runs) | aligned (different surface) | |

Rationale for what's deliberately **not** mirrored: Composer and capture rules are both
configuration-heavy, keyboard-and-mouse-shaped tasks the dashboard already does well — duplicating
them on a phone screen would be worse UX than sending the developer to a laptop for those two.
Overview has no on-device equivalent because there's no "first thing you see" landing concept in a
bottom-nav app the way there is in a rail-nav dashboard. The Evidence tray's absence as a standalone
screen is more a sequencing gap than a decision: once evidence flagging is durable on-device (see the
in-flight note in [EVIDENCE_AND_BUG_REPORTS.md](EVIDENCE_AND_BUG_REPORTS.md#known-limitations)), a
tray screen becomes a natural addition, just not one this pass builds.

---

## 5. Component inventory

- **Topbar icon-buttons** (32×32, `.icon-btn`): record/tail, keyboard-shortcuts, theme-toggle.
- **Status cluster:** server-info text, `.ws-status` (live/paused), `LIVE STREAM` pill.
- **Buttons:** default (outline, hover→signal border/text), `.primary` (signal fill, `--signal-ink`,
  700 weight), `.danger` (error-tinted). Disabled: opacity .5, `not-allowed`.
- **Inputs / `label.field`:** grid, 5px gap, uppercase 10.5px label over control.
- **Search field** (`.search-wrap`): leading magnifier icon, `flex: 1 1 220px`.
- **Segmented control** (`.seg`): joined buttons, one active (signal).
- **Chips / chip-row** (`.chip-row`): wrapping pill filters.
- **Cards** (`.card`): panel surface, 1px `--line`, 16px pad, `h2` with small leading icon.
- **Split panes:** `.list` (rows) + `.splitter` + `.detail-pane` (min-width 300px).
- **Data table** (`.db-table`): real `<table>`, `<th scope=col>`, truncation via `.db-truncated`,
  horizontal scroll in `.db-table-wrap`.
- **Empty states** (`.empty-state`): centered icon + title + sub. Used widely — design these well;
  many views start empty until the browser connects and traffic flows.
- **Modal / overlay** (z 1000): shortcuts help (`#shortcutsModal`), blurred backdrop.
- **Toast** (`role=status`, `aria-live=polite`): transient, auto-dismiss.
- **Skip link** (`.skip-link`): off-screen until focused → `#mainContent`.
- **Icons:** inline SVG sprite (`<use href="#dc-...">`) — folder, refresh, file, plus, pencil,
  check, download, trash, keyboard, health, etc. **One stroke style.** No emoji as icons.

---

## 6. Every web view (what it holds)

1. **Overview** — landing / status summary, connect prompt when unauthenticated, and the
   previous-run-crashed banner (see [CRASH_AND_ANR.md](CRASH_AND_ANR.md#the-previous-run-crashed-banner)).
2. **Network** — captured HTTP list + detail; toolbar (search, status seg, method seg, Apply, Clear,
   HAR, Postman); detail action row (Clone to composer, cURL, fetch, JSON, Related events); redacted
   metadata pane.
3. **WebSockets** — socket sessions (URL, STATE/SENT/RECV), frame list per socket.
4. **Timeline** — unified event timeline (search).
5. **Crashes** — crash/ANR list + detail: kind/thread badges, breadcrumb strip, the all-thread dump
   in a collapsed code block with its truncation markers rendered verbatim. Visible in Simple mode
   (a QA surface, not an implementation detail). Every item arrives auto-flagged into the evidence
   tray. See [CRASH_AND_ANR.md](CRASH_AND_ANR.md#the-crashes-surface).
6. **Push** — push events (provider, lifecycle, message, campaign).
7. **SDK Health** — protocol metadata + bounded health snapshot (Refresh).
8. **Composer** — request builder; gated by `composerEnabled` + host allowlist; errors like
   `COMPOSER_DISABLED`, `COMPOSER_HOST_REJECTED` surface here.
9. **Mocks** — mock rules list, enable/disable toggle, editor.
10. **Capture rules** — capture rule management.
11. **State & Flags** — app state + feature flags; edits gated by `stateMutationsEnabled`.
12. **Preferences** — SharedPreferences viewer/editor; **shows redaction behavior** — `access_token`
    masked, `authToken` verbatim (deliberate allowlist blind-spot demo).
13. **Database** — SQLite browser; table renderer with real `<table>`.
14. **Files** — file tree (roots, path, Go, Refresh roots) + preview/editor (create/rename/save/
    download/delete), each gated by the `files` capability.
15. **Evidence tray** — flagged items (network/timeline/socket/push/screenshot/crash), severity/
    summary/expected/actual report draft (autosaves), bundle download, and three clipboard formats
    (Markdown/Jira/GitHub). Visible in Simple mode. See
    [EVIDENCE_AND_BUG_REPORTS.md](EVIDENCE_AND_BUG_REPORTS.md).
16. **Session & Security** — session info, connected browsers, retention, LAN warning, security
    posture, and a retained-runs table (id, status, start/end time, app version, device).

Capability gating: 7 `EditingCapabilities` flags (preferences, database, files, mocks, captureRules,
featureFlags, requestExecution) + server gates (`composerEnabled`/`composerAllowedHosts`,
`stateMutationsEnabled`). **When a capability is off, the corresponding write control is disabled
with a title explaining why.** A redesign must keep the read-only-vs-editable distinction obvious
and never imply an edit is possible when it is refused (`REDACTED_WRITE_BLOCKED`,
`COMPOSER_HOST_REJECTED`, etc.).

**Capture-category gating (init-time, not editing).** A host chooses which capture surfaces are
recorded at all via `DevConsoleConfig.captureCategories` (`io.devconsole.api.CaptureCategory`),
independent of the `EditingCapabilities` write gates above. Nine categories, each grouping the
screens/routes it owns: `NETWORK` (Network), `SOCKET` (WebSockets, plain-WS connections/frames),
`MQTT` (WebSockets, MQTT connections/frames — same view, discriminated by protocol), `PUSH` (Push),
`LOGS` (Timeline), `CRASHES` (Crashes + the ANR watchdog), `STATE` (State & Flags), `INSPECTION`
(Preferences + Database + Files), `MOCKS` (Mocks + Capture rules). Default is **all nine** — every
existing host sees no behavior change. A disabled category means the SDK never records that data in
the first place (not merely hides an already-captured view): its list is empty, its server routes
respond `403 CATEGORY_DISABLED`, and both surfaces **hide** the corresponding nav entry/tab rather
than showing it empty. Every gate is fail-open — a config that hasn't arrived yet, or a gate that
itself throws, always means "capture," never "silently drop." A redesign must treat a
capture-disabled surface the same as a not-yet-implemented one: absent from the nav, not present in
a disabled/greyed state.

**`SocketProtocol` discriminator.** The WebSockets view (web) / Observe → Sockets tab (Android)
renders both plain WebSocket and MQTT connections through the one existing socket UI —
`SocketConnection.protocol` (`websocket` | `mqtt`) distinguishes them, and an MQTT frame additionally
carries `topic`/`qos`. A redesign should surface the protocol as a small badge/label per connection
(and topic/QoS on an MQTT frame's detail) rather than inventing a second, parallel "MQTT view."

---

## 7. Interaction & motion

- **Keyboard shortcuts:** `/` focus the current view's filter, `?` open shortcuts help, `p` toggle
  pause/record, `t` toggle theme. Guarded so they don't fire while typing in inputs or when a dialog
  is open. Keep and document any redesign's shortcut map.
- **Focus:** visible focus rings everywhere; skip-link first in tab order.
- **Motion:** subtle, 150–300ms; **must** respect `prefers-reduced-motion`. No decorative motion.
- **States:** every interactive element needs distinct hover / active / disabled; active nav and
  active segment use signal green.
- **Live data:** views stream/refresh; there's a record/pause concept (`LIVE / PAUSED`). Empty and
  "not connected yet" states are common first impressions — design them intentionally.

---

## 8. The chosen redesign direction (already decided)

The goal is reference-app-quality **presentation** applied to DevConsole **without** changing its
identity. Decisions locked in:

- **Palette:** keep DevConsole green.
- **Nav:** keep the rail; add polish (spacing, hierarchy, active-state clarity, group rhythm).
- **Scope:** willing to do a full pass in one go.

So: this is a **polish/representation upgrade on a fixed skeleton**, not a rebrand. Improve
information hierarchy, density balance, empty states, table/detail readability, responsive behavior,
and cross-surface consistency (web ↔ Android) — inside the tokens and constraints above.

---

## 9. Known-fixed issues + open items (context for validation)

**Recently fixed (don't re-report as new):**
- Toolbar controls misaligned — `.toolbar` was `align-items: center`, so bare buttons floated above
  label-stacked fields; now `flex-end` (shared baseline). Verified across all 16 views.
- Rail group labels failed AA at 10.5px → `--text-3` retuned to 5.03:1 (dark) / 4.98:1 (light).
- Preferences redaction "lied" (masked header but showed a token) → now demonstrates the allowlist
  blind spot honestly with two side-by-side tokens.
- Android More screen didn't scroll (export buttons unreachable) → `verticalScroll` added.
- Android Observe chips had sub-48dp touch targets → wrapped with `minimumInteractiveComponentSize()`.
- Stale "sign in" hints after connect → synced via connect-prompt logic.
- **Responsive was desktop-first and thin (E1)** — now a documented, mobile-first breakpoint ladder
  (§3.4) plus container-query card folds; no horizontal body overflow at 375/768/1024 on any of the
  16 views. Card grids, toolbars, split panes, and data tables were redesigned for narrow widths, and
  wide content (tables, JSON/code, the crash dump) scrolls inside its own `overflow-x` container
  rather than pushing the page wide.
- **Empty/first-run states were thin (E2)** — every list view's empty state now names the concrete
  next step: the device's More screen, the `adb forward tcp:8080 tcp:8080` command (with its caveat
  that 8080 is only the first port tried), and that the `#code=` fragment is the credential without
  which the page sits unauthenticated forever.
- **Cross-surface parity was undecided** — resolved and documented in §4c, with a full 16-row table
  of which web views the Android inspector mirrors, which it deliberately doesn't (Composer, capture
  rules, Overview), and one open naming mismatch (Timeline / Logs) left as a documented gap rather
  than silently inconsistent.

**Open / worth a design eye:**
- **Table/detail density** on Network/Database detail panes — readability at scale — is still
  unaddressed by this pass.
- **The Timeline / Logs naming mismatch** (§4c) — same underlying event stream, different name on
  each surface. Documented, not fixed; fixing it is a code change (renaming `ObserveTab.LOGS` or the
  web view, or both to a shared name), out of scope for a documentation-only pass.
- **The evidence tray has no Android screen yet** (§4c) — flagging exists inline on Observe's
  network/crash detail, but there's no standalone tray/report-draft screen on-device, partly because
  the durable evidence wiring itself is still landing (see the note below).

**Known limitations carried into this release, stated plainly rather than buried:**
- **Several `androidTest` suites have never been executed** — no emulator was available while this
  feature set was built. This covers the Room migration 4→5 test
  (`Migration4To5InstrumentedTest`), the evidence cascade-delete test
  (`EvidenceCascadeDeletionInstrumentedTest`), the screenshot capture test
  (`ScreenshotCaptureInstrumentedTest`, which exercises real `PixelCopy` and `FLAG_SECURE` behavior),
  and the crash/ANR/screenshot trigger paths added to all three sample apps. Everything in this list
  compiles and the relevant `androidTest` APKs assemble; none has run against a real device or
  emulator. Treat on-device behavior in these paths as reviewed, not verified. The migration and
  cascade-delete tests specifically have a worse history than "awaiting an emulator": for most of this
  feature's development, `sdk:storage-room`'s whole instrumented APK could not even be **dexed** — a
  backtick test name with spaces in an unrelated test required `minSdk 30` on a `minSdk 24` module —
  so those two tests could not have run on any device, emulator or otherwise. That dexing failure is now
  fixed; the APK assembles, and only the lack of an emulator remains. See
  [EVIDENCE_AND_BUG_REPORTS.md](EVIDENCE_AND_BUG_REPORTS.md#known-limitations) for the full account.
- **Clicking *Older* in the network list after scroll-triggered append resets to a single fresh page
  at that cursor, discarding the appended rows.** E4's windowed lists combine scroll-triggered
  incremental append with the pre-existing Older/Newest full-page pager; the pager's surrounding
  logic (facet counts, selection pruning, select-all-matching-filter) assumes one coherent page, and
  rebuilding that onto an accumulate-and-append model was out of scope for this pass. Known
  asymmetry, not a regression waiting to be noticed.
- **The previous-run-crashed banner opens the general Crashes view, not a view filtered to that past
  run** — see [CRASH_AND_ANR.md](CRASH_AND_ANR.md#the-previous-run-crashed-banner). Nothing in this
  SDK yet supports filtering Crashes (or Timeline, or Network) to a session other than the active
  one.
- **Network body attachments deliberately do not report `redactionApplicability`** anywhere in their
  own detail JSON, because the only writer of that attachment kind always uses `APPLIED` — there is
  exactly one possible answer, so surfacing the field there would be a lookup with no information in
  it. See
  [SECURITY_AND_REDACTION.md](SECURITY_AND_REDACTION.md#redactionapplicability-and-the-storage-boundary).

---

## 10. How to use this with a design agent

Ask it to:
1. **Validate** current screens against §2 constraints, §3 tokens, and the WCAG AA bar — flag any
   contrast, focus, touch-target, or hierarchy issue, per view.
2. **Redesign within the box** — produce layouts/components that use only the tokens in §3, honor the
   CSP/self-contained/system-font/green/rail constraints in §2, and deliver the reference-quality
   presentation polish described in §8.
3. **Output as tokens + component specs + per-view redlines**, not a from-scratch visual language.
   Anything that requires a web font, an icon CDN, inline scripts, or a non-green identity is
   out of bounds and should be proposed as a bundled/self-hosted alternative or dropped.

**Source files to reference:**
- `sdk/server-ktor/src/main/resources/devconsole-web/index.html` — structure & sprite
- `.../devconsole-web/dashboard.css` — all tokens & components
- `.../devconsole-web/dashboard.js` — wiring, views, shortcuts
- `sdk/ui-compose/src/main/kotlin/io/devconsole/ui/compose/` — Android inspector screens
- `sdk/server-ktor/.../DevConsoleKtorModule.kt` (~L283) — the CSP header
- `docs/THREAT_MODEL.md`, `docs/SECURITY_AND_REDACTION.md` — why redaction/gating look the way they do
- `docs/EVIDENCE_AND_BUG_REPORTS.md`, `docs/CRASH_AND_ANR.md` — the evidence tray, screenshots, and
  crash/ANR capture this spec's §4c parity table and §6/§9 updates describe
