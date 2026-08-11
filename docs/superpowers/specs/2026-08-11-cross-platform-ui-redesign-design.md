# DevConsole Cross-Platform UI Redesign

Date: 2026-08-11
Status: Approved design

## Summary

Redesign the browser dashboard and Jetpack Compose inspector as **native siblings**: they share a restrained visual identity, color roles, data typography, and interaction principles, while each follows the conventions of its platform.

The browser remains a dense desktop workbench with rail navigation, tables, and resizable detail panes. Android becomes a clearly native Material 3 application with adaptive navigation, top app bars, edge-to-edge lists, system Back behavior, and platform-sized controls. The redesign preserves every existing capability and data flow.

## Problem

The current interfaces are internally consistent but feel synthetic and over-authored. Repeated rounded containers, pills, uppercase micro-labels, green-tinted surfaces, and decorative “instrument panel” language make ordinary controls look themed rather than natural. Android inherits too much of that desktop visual grammar, which makes it feel like a website compressed onto a phone.

The redesign must remove those signals without making DevConsole generic, sparse, or consumer-oriented.

## Goals

- Make both surfaces feel designed by a product team rather than generated from a theme prompt.
- Preserve expert density, fast scanning, and the existing evidence-first workflow.
- Give web and Android a shared character without forcing identical layouts or components.
- Replace the phosphor-green identity with a neutral graphite and cobalt system.
- Follow the system light or dark preference initially and remember an explicit user override.
- Preserve all existing capture, filtering, detail, server, evidence, export, and persistence behavior.
- Meet WCAG 2.2 AA on web and Android accessibility conventions.

## Non-goals

- No backend, protocol, storage, capture, or export behavior changes.
- No navigation destination changes or feature removals.
- No external fonts, icon services, remote assets, inline scripts, or CSP changes.
- No large-scale rewrite of the dashboard JavaScript or Compose state management.
- No adoption of Android wallpaper-derived Dynamic Color; the fixed cobalt roles keep the product recognizable across devices.

## Approved Direction

### Native siblings

The two surfaces share:

- graphite neutral ramps;
- cobalt interaction color;
- restrained semantic status colors;
- system UI typography plus monospace for captured data;
- sentence-case labels;
- compact spacing and direct hierarchy;
- limited container shapes;
- clear empty, loading, error, and disabled states.

They differ where platform convention demands it:

- Web uses a persistent rail, toolbars, data tables, keyboard focus, hover, and a resizable split view.
- Android uses Material top app bars, navigation bar or navigation rail according to width, edge-to-edge content, list rows, system Back, and Android-native transient feedback.

## Visual System

### Color roles

The palette is neutral first. Cobalt is rare and functional: selected, focused, live, linked, and primary. It is not used as decoration.

Dark roles:

- Background: `#111317`
- Panel: `#171A20`
- Raised surface: `#1D2129`
- Hover/pressed surface: `#242A34`
- Primary text: `#ECEEF2`
- Secondary text: `#969DA8`
- Outline: `#343A45`
- Strong outline: `#4A5260`
- Primary cobalt: `#72A7FF`
- On-primary: `#0E1624`

Light roles:

- Background: `#F7F8FA`
- Panel: `#FFFFFF`
- Raised surface: `#EEF1F5`
- Hover/pressed surface: `#E4E8EE`
- Primary text: `#1C1F24`
- Secondary text: `#68707B`
- Outline: `#D5D9E0`
- Strong outline: `#AAB1BC`
- Primary cobalt: `#245DA8`
- On-primary: `#FFFFFF`

Semantic roles remain independent of the accent:

- Error: coral red, never cobalt.
- Warning: amber, never cobalt.
- Success: muted green, used only when success itself matters.
- Informational and syntax colors may use teal, violet, or amber when their meaning is stable and accessible.

All component colors must use web custom properties or Compose theme roles. Raw colors remain limited to the theme definitions and test fixtures.

### Typography

- Use the system UI stack on web and Material typography roles on Android.
- Use monospace only for captured or generated data: URLs, methods, status codes, timestamps, durations, payloads, file paths, IDs, and counters.
- Use sentence case for navigation, headings, field labels, buttons, and state messages.
- Reserve uppercase for genuinely conventional short data labels such as HTTP methods and compact table column headers.
- Use weight and spacing for hierarchy before increasing size.
- Android text must scale with user font settings; fixed custom sizes are replaced with Material roles wherever possible.

### Shape and elevation

- Inline controls use 6–8px corners on web and the nearest restrained Material shape on Android.
- Outer grouped containers may use 10–12px corners.
- Pills are reserved for compact filters or status chips whose content benefits from the shape.
- List rows, metrics, toolbar groups, and ordinary sections are not cards by default.
- Resting surfaces rely on tonal contrast and separators. Shadows are limited to detached overlays such as menus, dialogs, and sheets.

### Spacing and density

- Keep a 4px base rhythm on web.
- Preserve expert information density without crowding interactive controls.
- Use 48dp minimum Android touch targets and at least 8dp separation where adjacent actions could be confused.
- Remove decorative padding that exists only to make a container look card-like.

## Web Dashboard

### Shell and navigation

- Keep the left rail and current destination hierarchy.
- Retain a compact desktop rail with sentence-case group and item labels.
- Active navigation uses a quiet raised surface plus a 2px cobalt indicator; it does not use a glowing green pill.
- Preserve compact-width rail collapse and keyboard navigation.
- Keep the top session bar, but simplify it to product identity, connected device/build context, session status, and global actions.

### Content hierarchy

- Each view begins with a title, a concise state summary, and view-level actions.
- Metrics are presented as a shared strip or aligned values rather than independent cards.
- Inspection views use data tables or aligned rows with stable columns.
- Form and configuration views use sections with headings and separators. A border surrounds a section only when it materially explains grouping.
- The current split-view behavior remains: list, draggable splitter, and detail pane on desktop; vertical stacking at narrower widths.

### Controls

- Search fields use normal compact input geometry rather than oversized rounded capsules.
- Filter chips remain only where quick toggling matters. They use restrained rounded rectangles and clear selected states.
- Buttons use filled, outlined, or text hierarchy. Only the primary action receives a cobalt fill.
- Badges are limited to status, method, count, or capability metadata.
- Focus is visible on every interactive element and never depends on color alone.

## Android Inspector

### Shell and navigation

- Follow the system light or dark theme on first launch. Persist an explicit in-product override.
- Use Material `NavigationBar` for compact widths and `NavigationRail` for expanded widths.
- Preserve the four destinations and all capture-category gating.
- Use a Material top app bar for screen context and overflow actions.
- Honor status/navigation bar insets, IME insets, predictive Back, and system Back.

### Content

- Replace large nested rounded panels with edge-to-edge or lightly inset Material lists.
- Use list rows for traffic, sockets, logs, crashes, exports, database entries, and files.
- Use section headings and dividers for configuration and server state.
- Use standard Material controls for switches, segmented choices, buttons, dialogs, sheets, menus, and snackbars.
- Use a full-screen detail route only for information-heavy inspection. Use a sheet for focused, dismissible tasks such as editing a rule when the content fits.
- Preserve 48dp touch targets even when visible rows remain compact.

### Responsive behavior

- Compact phone widths use the navigation bar and single-pane routes.
- Expanded widths use a navigation rail and may show list/detail side by side where existing state supports it.
- Content never hides under system bars, cutouts, the keyboard, or navigation components.

## Interaction and State Behavior

- Existing view-model actions, dashboard event handlers, APIs, and stored state remain the source of truth.
- Theme initialization reads the platform preference; an explicit choice is stored and wins on later launches.
- Web selection opens the existing detail pane and preserves splitter behavior.
- Android selection navigates to a detail route or appropriate sheet and returns through system Back.
- Loading uses one restrained progress treatment at the affected scope; decorative skeleton card grids are not introduced.
- Empty states name what is absent and the next concrete action.
- Persistent errors appear beside the affected content. Android snackbars and web toasts are reserved for transient results.
- Disabled actions explain which capability, connection, or permission is missing.
- Destructive actions keep explicit confirmation where the current product requires it.
- Motion is short and functional, and honors reduced-motion or disabled-animation settings.

## Data Flow and Architecture

The redesign changes composition and styling, not business logic.

- Dashboard DOM structure may be simplified to support tables, flatter sections, and semantic landmarks, but existing IDs/data attributes used by JavaScript remain stable or are migrated with corresponding tests.
- Dashboard API requests, WebSocket updates, selection state, filters, evidence tray operations, and exports remain unchanged.
- Compose routes continue to consume `InspectorState` and dispatch `InspectorAction` through `InspectorViewModel`.
- Reusable visual primitives belong in the existing design-system/theme and component files, not in screen-specific duplicates.
- Web custom properties and Compose color/typography/shape roles remain the single visual sources of truth for their platforms.

## Accessibility

Web requirements:

- WCAG 2.2 AA color contrast.
- Visible 2px focus indicators with sufficient contrast.
- Keyboard access to navigation, filters, table rows, detail actions, dialogs, and menus.
- Semantic landmarks, headings, labels, table structure, and live regions where status changes require announcement.
- Coarse-pointer media rules for larger targets.

Android requirements:

- 48dp minimum touch targets.
- Material typography roles that follow font scaling.
- Meaningful content descriptions; decorative icons remain excluded from accessibility.
- Predictive Back and standard focus traversal.
- Correct semantics for navigation selection, filters, switches, expandable sections, and status.
- Reduced motion and adequate contrast in both themes.

## Verification

### Visual QA

Capture and compare bounded screenshot sets after implementation:

- Web: desktop and narrow widths, dark and light.
- Android: compact phone and expanded width, dark and light.
- Representative states: populated, empty, loading, error, disabled capability, selected row/detail, and overlay.

Perform one batched defect pass, apply one batched correction, then run at most one confirmation pass.

### Automated checks

- Existing dashboard CSP and asset tests continue to pass.
- Existing dashboard interaction tests continue to pass, with selectors updated only where required by the approved structure.
- Add focused tests for system-theme initialization and explicit-theme persistence.
- Add or update Compose UI tests for adaptive navigation, Back behavior, target semantics, and primary list/detail interactions.
- Run affected Gradle unit tests and UI module compilation.
- Verify color contrast for the finalized role pairs.

## Migration Plan

1. Replace the existing instrument-panel design documentation with the approved native-siblings system and regenerate its Impeccable sidecar.
2. Update shared web tokens and shell primitives before migrating individual views.
3. Migrate representative web archetypes first: overview, split inspection, and configuration/card-grid views. Apply their shared patterns to remaining views.
4. Update the Compose theme and workspace shell, including platform-aware theme initialization and adaptive navigation.
5. Migrate representative Android archetypes, then apply shared rows, sections, filters, and details across remaining routes.
6. Run automated checks and the bounded cross-platform visual QA pass.

## Success Criteria

- Neither surface relies on repeated rounded cards, decorative uppercase labels, or green-tinted panels for hierarchy.
- Web reads as a purpose-built desktop debugging tool and keeps its rail, density, and split-pane productivity.
- Android reads as a native Material application at compact and expanded widths.
- Graphite surfaces dominate; cobalt is limited to interactive and live states.
- System theme is honored initially and an explicit override persists.
- Existing features and data behavior remain intact.
- Required contrast, focus, touch target, font scaling, Back, and reduced-motion behavior are verified.
- Dark/light screenshots for both platforms show a coherent family without forcing pixel-identical composition.
