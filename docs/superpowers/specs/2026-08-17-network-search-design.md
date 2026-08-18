# Network Detail Search Design

**Date:** 2026-08-17  
**Scope:** Android Compose Network detail search

## Goal

Make Network detail search useful for inspecting request and response data without flattening formatted response bodies. Search must support multi-section scope selection, key/value selection, exact highlighting, and next/previous match navigation.

## User behavior

The detail search field searches only the selected network sections:

- Request headers
- Request body
- Response headers
- Response body

All four sections are selected by default. General metadata, timing, and redaction sections are never included in this search.

The search options control opens as a modal bottom sheet. It contains:

- A multi-select list for the four request/response sections.
- Select all and clear all actions.
- A match mode choice: Keys, Values, or Keys + values.
- Apply and Cancel actions.

At least one section must be selected before Apply is enabled. The search toolbar summarizes the current scope, for example `All request + response`, `Response body`, or `2 sections`.

Keys is the default match mode. Matching is case-insensitive substring matching. Applying a changed scope or match mode rebuilds the match index and selects the first result. Clearing the query removes search highlighting and restores the normal detail rendering.

Matches are ordered by document order: request headers, request body, response headers, response body, with unselected sections omitted. Next and previous controls cycle through this ordered list. The current match is visually stronger than the other matches and is brought into view; its section and any required structured-data ancestors are expanded.

## Rendering requirements

Search must preserve the existing representation of each body:

- Formatted JSON remains a JSON tree while searching.
- Formatted XML remains formatted while searching.
- Raw mode remains raw.
- Header key/value rows remain key/value rows.

Highlighting is applied to exact matched character ranges with `AnnotatedString` spans. A row-level background alone is insufficient. Structured JSON matches retain their node/path identity so a key match can highlight a property name and a value match can highlight the scalar value.

Raw or otherwise unstructured content has no semantic key field; it is searched as value text without changing its presentation.

## Architecture

Keep this feature in the Compose presentation layer. The detail screen owns query, selected sections, match mode, and current match index with saveable local state. No capture-state or repository changes are needed.

Introduce a pure Kotlin search model/helper that:

1. Accepts the network detail sections and search configuration.
2. Produces ordered matches containing section identity, item/path identity, field type, and exact text range.
3. Produces section-level match counts and target metadata for scrolling/expansion.

The existing detail resolver should enrich sections with match metadata while preserving `Formattable`, `KeyValues`, and `Code` body types. It must not convert a `Formattable` body to a flat `Code` body merely because a query is active.

The search options bottom sheet edits a draft selection and only changes active search state after Apply. Cancel leaves the current search configuration untouched.

## Accessibility and interaction

- Every scope checkbox, match mode option, and navigation button has a meaningful content description or visible label.
- Navigation buttons use at least 48dp touch targets and are disabled when there are no matches.
- The match label communicates `current / total`; no-match state is explicit.
- The bottom sheet has a clear title and an obvious Apply action.

## Testing

Add pure unit coverage for:

- Default all-section scope.
- Excluding general/timing/redaction sections.
- Each multi-select combination.
- Keys, Values, and Keys + values behavior for headers and structured bodies.
- Case-insensitive substring matching and exact ranges.
- Stable document ordering and next/previous wrap-around.
- Nested JSON match identity and ancestor expansion metadata.

Add Compose/UI coverage for:

- Opening the options sheet, changing multiple sections, applying, and cancelling.
- Navigation button enabled/disabled states and match count.
- Formatted response bodies remaining formatted while a query is active.

Verify the feature on the Android emulator in light and dark themes, including a response with nested JSON and multiple matches.

## Out of scope

- Regex or query-language search.
- Changes to the traffic-list filter.
- Visual identity/token cleanup unrelated to search.
- Persistence of search options across different network requests or app restarts.

## Acceptance criteria

- Search never reports or highlights matches from General, Timing, or Redactions.
- Users can select any combination of the four request/response sections.
- Keys is the default match mode and Values/Keys + values work correctly.
- Next and previous navigate through all selected matches and bring the current match into view.
- Matched text is highlighted precisely.
- Searching a formatted response never converts it into a single-line or flat representation.
- Targeted unit/UI tests and the relevant Android build pass.
