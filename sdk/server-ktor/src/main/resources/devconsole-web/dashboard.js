/**
 * DevConsole browser dashboard.
 *
 * Single external script (CSP: script-src 'self', no 'unsafe-inline') that drives every
 * view. All fetch routes, param names, and the session-code exchange flow are the
 * functional contract shared with the SDK server — see server-ktor's DevConsoleKtorModule.
 */
(() => {
  'use strict';

  // ================================================================
  // State
  // ================================================================
  let token = '';
  let currentView = 'overview';
  let csrf = '';
  let paused = true;
  let events = [];
  let pendingLiveEvents = [];
  let timelineCursor = null;
  let transactions = [];
  let networkCursor = null;
  let pushes = [];
  let selectedTransactionId = '';
  let selectedTransactionDetail = null;
  let selectedEventId = '';
  let eventDetailQuery = '';
  let selectedPushIndex = -1;
  let pushDetailQuery = '';
  let captureRulesEditable = false;
  let composerCapabilityEnabled = false;
  let mocksEditable = false;
  let mocksGloballyEnabled = true;
  let mockRulesCache = [];
  let mockDialogOpenerEl = null;
  // Original-response snapshot for the rule currently open in the dialog — set only when the
  // draft came from "Mock this response" (or an edit of a rule that already had one), reset on
  // every dialog open, and included in the save payload as-is (never re-derived from edited fields).
  let mockRuleDraftSourceBodySnapshot = null;
  let preferencesEditable = false;
  let databaseEditable = false;
  let filesEditable = false;
  let preferenceFiles = [];
  let databases = [];
  let dbTablesCache = [];
  let fileRoots = [];
  let selectedFilePath = '';
  let stream = null;
  let streamRetry = null;
  // Highest Timeline event.sequence this browser has taken delivery of, whether via a live WS
  // frame or a full /api/v1/events refetch -- compared against server.welcome's currentSequence on
  // every (re)connect so a reconnect that missed events while the socket was down can tell that
  // apart from a fresh connect with nothing to backfill (see reconcileStreamGap).
  let lastKnownSequence = -1;
  let sessionExpired = false;
  let consecutiveAuthFailures = 0;
  let timelineSeverityFilter = '';
  let timelineSourceFilter = '';
  let timelineBookmarkedOnly = false;
  let timelineOrder = [];
  let pushProviderFilter = '';
  let railHidden = false;
  let uiMode = 'simple';
  let railAdvancedOpen = false;
  let railAdvancedHome = null;
  // Capture-category gating (see applyCaptureCategoryGating): null means "unknown" -- an older SDK
  // whose /api/v1/meta omits captureCategories, or a meta fetch that hasn't resolved yet -- and is
  // treated as fail-open (show everything). Once known, it's the exact Set<string> of wire-name
  // categories (see CaptureCategory.wireName) the host enabled for this app run.
  let enabledCaptureCategories = null;
  // Sockets view protocol filter: 'all' | 'websocket' | 'mqtt'. Pinned to whichever single
  // protocol is enabled (see applyCaptureCategoryGating) when only one of socket/mqtt is on.
  let socketProtocolFilter = 'all';

  // Network: `networkPage` holds the broadest server-fetched page (every filter except
  // method/status/host, which are applied client-side below so the Status/Method segments and
  // the Service dropdown can show live facet counts without extra round-trips); `transactions`
  // is the client-filtered subset actually shown in the list.
  let networkPage = [];
  let networkStatusFilter = '';
  let networkMethodFilter = '';
  const networkHostFilter = new Set();
  let networkFailOnly = false;
  // Transaction ids checked for export (HAR/Postman), independent of `selectedTransactionId`
  // (single-row detail-pane focus). Pruned in loadNetwork alongside networkHostFilter so a
  // selection can never silently outlive the rows it was made against.
  const networkSelectedIds = new Set();
  let networkShowHost = true;
  let networkPinnedId = '';
  let networkTab = 'compare';
  let networkDetailQuery = '';
  let networkSbsStacked = false;
  // Backing state for the 'related' detail tab — see loadRelatedEvents/renderNetworkDetail.
  let networkRelatedEvents = [];
  let networkRelatedEventsForId = null;
  let networkRelatedEventsLoadingId = null;
  // transaction id -> detail JSON (for baseline diffing). LRU-bounded — see cacheNetworkDetail —
  // so a long-running session's Network tab can't grow this without limit; never reassigned, only
  // mutated in place, hence `const`.
  const networkDetailCache = new Map();
  const NETWORK_DETAIL_CACHE_MAX = 50;
  /** Inserts/refreshes `id` as most-recently-used and evicts the least-recently-used entry once
   * over the cap. The pinned diff baseline is never evicted — diff mode depends on it staying
   * resident no matter how long ago it was pinned or how much Network browsing happens after. */
  function cacheNetworkDetail(id, detail) {
    networkDetailCache.delete(id);
    networkDetailCache.set(id, detail);
    while (networkDetailCache.size > NETWORK_DETAIL_CACHE_MAX) {
      const oldestKey = [...networkDetailCache.keys()].find((k) => k !== networkPinnedId);
      if (oldestKey === undefined) break;
      networkDetailCache.delete(oldestKey);
    }
  }
  let networkHostDropOpen = false;

  // WebSockets: `socketConnections` from /connections; `socketMessages_` from /messages for the
  // active connection/frame-type/direction filters (fetched broad, connection multi-select and
  // list/timeline mode apply client-side on top).
  let socketConnections = [];
  let socketDetailQuery = '';
  let socketMessages_ = [];
  const socketSelectedConnIds = new Set();
  let socketFrameTypeFilter = '';
  let socketDirectionFilter = '';
  let socketMode = 'list';
  let socketSelectedIndex = -1;
  let socketConnDropOpen = false;

  const bookmarkedIds = new Set();
  // Evidence tray is durable server state (Room, see EvidenceStore) — `/api/v1/evidence`. This
  // Map is a CLIENT CACHE over that server state, never the source of truth: every row's flag
  // button, the rail count, and the tray view itself all read through it, but the cache is only
  // ever populated from a real server response (loadEvidenceFlags/loadEvidence) and every
  // flag/unflag is optimistic-then-reconciled against the server, never a purely local mutation.
  // Keyed by `${kind}:${id}` (kind lower-cased so 'network'/'NETWORK' from either the row buttons
  // or a server response land on the same key) so the topbar count, rail count, and tray view all
  // read the same store through this tiny API.
  const evidenceFlags = new Map();
  const evidenceKey = (kind, id) => String(kind).toLowerCase() + ':' + id;
  const isFlagged = (kind, id) => evidenceFlags.has(evidenceKey(kind, id));
  function evidenceErrorMessage(code) {
    if (code === 'ALREADY_FLAGGED') return 'Already flagged as evidence.';
    if (code === 'EVIDENCE_QUOTA_EXCEEDED') return 'Evidence tray is full (200 items) — clear some before flagging more.';
    if (code === 'EVIDENCE_UNAVAILABLE') return 'Evidence storage is unavailable on this build.';
    if (code === 'NOT_FOUND') return 'That capture is no longer available to flag.';
    if (code === 'VALIDATION_FAILED') return 'That capture could not be flagged.';
    if (code === 'AUTH_REQUIRED') return 'Connect this browser first.';
    if (code === 'CSRF_INVALID') return 'Session check failed — refresh and try again.';
    return 'Request failed' + (code ? ': ' + code : '');
  }
  /** Optimistic flag/unflag: the calling row/detail pane updates immediately (this function's
   * Map mutation always runs synchronously before its first `await`, so a caller that reads
   * `isFlagged()` right after calling this — every existing call site does — sees the optimistic
   * state without needing to await), then the server call reconciles. A rejection (already
   * flagged, over quota, evidence storage unavailable…) undoes the optimistic change and surfaces
   * the real reason via toast rather than leaving the UI silently wrong. */
  // In-flight guard, keyed the same as evidenceFlags: without this, a double-click (or a fast
  // double-tap) fires an overlapping POST and DELETE for the same subject. DELETE always answers
  // 200 (unconditionally idempotent) so there is no error signal on it, and POST does strictly
  // more work than DELETE, so the two responses can land in either order — whichever settles last
  // would win the local map regardless of what actually landed server-side. A second call for a
  // key that already has one in flight is dropped; the in-flight call is authoritative and will
  // leave both the map and the server in the same state.
  const evidenceFlagInFlight = new Set();
  async function toggleEvidenceFlag(kind, id, label) {
    const key = evidenceKey(kind, id);
    if (evidenceFlagInFlight.has(key)) return;
    evidenceFlagInFlight.add(key);
    try {
      const wasFlagged = evidenceFlags.has(key);
      if (wasFlagged) {
        const prev = evidenceFlags.get(key);
        evidenceFlags.delete(key);
        updateEvidenceUi();
        if (!hasSession()) return;
        const r = await fetch('/api/v1/evidence/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id), { method: 'DELETE', headers: controlHeaders() });
        if (r.ok) {
          toast('Removed from evidence.');
          if (currentView === 'evidence') loadEvidence();
        } else {
          evidenceFlags.set(key, prev);
          updateEvidenceUi();
          let code; try { code = (await r.json()).code; } catch { code = undefined; }
          toast(evidenceErrorMessage(code), 'error');
        }
      } else {
        evidenceFlags.set(key, { kind: String(kind).toLowerCase(), id, label, flaggedAtMs: Date.now() });
        updateEvidenceUi();
        if (!hasSession()) return;
        const r = await fetch('/api/v1/evidence', {
          method: 'POST',
          headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({ kind, id }),
        });
        if (r.ok) {
          const item = await r.json();
          evidenceFlags.set(key, {
            kind: String(item.kind).toLowerCase(), id: item.subjectId, label: item.label,
            flaggedAtMs: item.flaggedAtMs, snapshot: item.snapshot, attachmentId: item.attachmentId, itemId: item.id,
            redactionApplicability: item.redactionApplicability ?? null,
          });
          toast('Flagged as evidence.');
          if (currentView === 'evidence') loadEvidence();
        } else {
          let code; try { code = (await r.json()).code; } catch { code = undefined; }
          if (code === 'ALREADY_FLAGGED') {
            // The subject *is* flagged server-side (e.g. two tabs racing to flag the same item) —
            // deleting the key here would make the UI report it as unflagged when it isn't.
            // Resync from the server instead so the entry carries the real itemId/attachmentId/
            // snapshot rather than staying the bare optimistic placeholder.
            await loadEvidenceFlags();
          } else {
            evidenceFlags.delete(key);
            updateEvidenceUi();
          }
          toast(evidenceErrorMessage(code), 'error');
        }
      }
    } finally {
      evidenceFlagInFlight.delete(key);
    }
  }

  // ================================================================
  // DOM / formatting helpers
  // ================================================================
  const $ = (id) => document.getElementById(id);
  const auth = () => ({ Authorization: 'Bearer ' + token });
  const esc = (s) =>
    String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const icon = (name, cls) => `<svg class="ic${cls ? ' ' + esc(cls) : ''}" aria-hidden="true"><use href="#dc-${esc(name)}"/></svg>`;
  const hasSession = () => Boolean(token);
  // Wrapped once, globally, instead of at each of this file's ~100 fetch call sites: a stray 401 on
  // an authenticated request can just be a request racing a fresh reconnect, but several in a row
  // is the generic signal that the session died server-side outside of a WS close (e.g. the tab was
  // backgrounded through the token's TTL) -- see handleSessionExpired.
  const SESSION_DEAD_AFTER_CONSECUTIVE_401S = 3;
  const nativeFetch = window.fetch.bind(window);
  window.fetch = async (input, init) => {
    const response = await nativeFetch(input, init);
    if (init?.headers?.Authorization && token) {
      if (response.status === 401) {
        consecutiveAuthFailures += 1;
        if (consecutiveAuthFailures >= SESSION_DEAD_AFTER_CONSECUTIVE_401S) handleSessionExpired();
      } else {
        consecutiveAuthFailures = 0;
      }
    }
    return response;
  };
  const time = (ms) => new Date(ms || 0).toLocaleTimeString();
  /** Coarse "3m ago" style relative time for mock rule last-hit stamps — falls back to days once
   * past a day rather than growing more units, matching the density of the rest of the panel. */
  function relativeTime(ms) {
    if (!ms) return '';
    const s = Math.floor(Math.max(0, Date.now() - ms) / 1000);
    if (s < 60) return 'just now';
    const m = Math.floor(s / 60);
    if (m < 60) return m + 'm ago';
    const h = Math.floor(m / 60);
    if (h < 24) return h + 'h ago';
    return Math.floor(h / 24) + 'd ago';
  }
  const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  /** Shared byte formatter — used anywhere the server reports a raw byte count (database size,
   * export size estimate) so the same value always reads the same way across views. */
  function formatBytes(n) {
    if (n == null) return '—';
    if (n < 1024) return n + ' B';
    const units = ['KB', 'MB', 'GB', 'TB'];
    let v = n / 1024, i = 0;
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    return v.toFixed(v < 10 ? 2 : 1) + ' ' + units[i];
  }

  const SEVERITY_SHORT = ['DBG', 'INF', 'WRN', 'ERR'];

  function renderEmpty(container, iconName, title, sub) {
    container.innerHTML = `<div class="empty-state">${icon(iconName, 'empty-icon')}<div class="empty-title">${esc(
      title,
    )}</div>${sub ? `<div class="empty-sub">${esc(sub)}</div>` : ''}</div>`;
  }

  function setNavCount(id, n) {
    const el = $(id);
    if (!el) return;
    el.textContent = n > 999 ? '999+' : String(n);
    el.classList.toggle('show', n > 0);
  }

  // The rail's Evidence-tray count badge is the single flagged-count surface (the old topbar
  // duplicate was removed as clutter).
  function updateEvidenceUi() {
    setNavCount('navCountEvidence', evidenceFlags.size);
  }

  // ================================================================
  // Split-view chrome shared by Network / WebSockets / Timeline / Push:
  // metrics strip cells, method/level/direction/provider badges, list rows, the detail-pane
  // header/tabs/find-bar/body system, JSON-syntax code blocks, and keyboard selection stepping.
  // ================================================================
  function metricHtml(label, val, sub, tone) {
    return `<div class="metric"><div class="metric-label">${esc(label)}</div><div class="metric-value-row"><span class="metric-value tone-text-${tone}">${esc(
      val,
    )}</span>${sub ? `<span class="metric-sub">${esc(sub)}</span>` : ''}</div></div>`;
  }
  function methodTone(method) {
    if (method === 'GET') return 'signal';
    if (method === 'POST' || method === 'PUT') return 'put';
    if (method === 'PATCH') return 'warn';
    if (method === 'DELETE') return 'error';
    return 'muted';
  }
  function statusTone(status) {
    if (!status) return 'error';
    if (status >= 500) return 'error';
    if (status >= 400) return 'warn';
    if (status >= 300) return 'put';
    return 'signal';
  }
  function levelTone(shortName) {
    if (shortName === 'ERR') return 'error';
    if (shortName === 'WRN') return 'warn';
    return 'muted';
  }

  /** One list row across all four split views: optional 24x24 export-checkbox · badge col (54px)
   * · main col (flex) · duration col (74px, hidden <620px) · status col (62px) · 24x24 flag button.
   * `checkbox: true` opts a row into the export-selection checkbox (Network only); `checked`
   * reflects whether `id` is currently in that selection. */
  function rowHtml(opts) {
    const { id, selected, badgeText, badgeTone, mainText, mainRtl, tagText, tagTone, duration, statusText, sTone, flagKind, flagLabel, checkbox, checked, posinset, setsize } = opts;
    const flagId = opts.flagId ?? id; // selection id and evidence id may differ (socket frames)
    const flagged = flagKind ? isFlagged(flagKind, flagId) : false;
    // aria-posinset/aria-setsize: with the list virtualized, only a window of `role="option"`
    // rows is ever in the DOM at once, so a screen reader can't infer "row 40 of 5,000" from DOM
    // order alone. Omitted when a caller doesn't pass `setsize` (none currently — every rowHtml()
    // call site was updated alongside the virtualizer).
    const posAttrs = setsize != null ? ` aria-posinset="${posinset}" aria-setsize="${setsize}"` : '';
    return `<div class="row${selected ? ' selected' : ''}${checked ? ' row-picked' : ''}" data-row-id="${esc(id)}" tabindex="${selected ? '0' : '-1'}" role="option" aria-selected="${selected}"${posAttrs}>
      ${
        checkbox
          ? `<button type="button" class="row-check" data-check-id="${esc(id)}" role="checkbox" aria-checked="${Boolean(checked)}" title="${checked ? 'Remove from export selection' : 'Add to export selection'}">${checked ? icon('check', 'ic-sm') : ''}</button>`
          : ''
      }
      <span class="row-badge badge-${badgeTone}">${esc(badgeText)}</span>
      <span class="row-main">
        <span class="row-main-text"${mainRtl ? ' style="direction:rtl;text-align:left"' : ''}>${esc(mainText)}</span>
        ${tagText ? `<span class="row-tag tone-text-${tagTone || 'muted'}">${esc(tagText)}</span>` : ''}
      </span>
      <span class="row-duration">${esc(duration ?? '')}</span>
      <span class="row-status tone-text-${sTone || 'muted'}">${esc(statusText ?? '')}</span>
      ${
        flagKind
          ? `<button type="button" class="row-flag" data-flag-kind="${esc(flagKind)}" data-flag-id="${esc(flagId)}" data-flag-label="${esc(
              flagLabel || mainText,
            )}" aria-pressed="${flagged}" title="${flagged ? 'Remove from evidence' : 'Flag as evidence (Enter)'}">${icon('flag', 'ic-sm')}</button>`
          : '<span class="col-flag"></span>'
      }
    </div>`;
  }

  /** Wires a `.row`/`.trace-row` list's shared behaviors: click-to-select, click-to-flag,
   * click-to-check (when `onCheck` is supplied), and Space to activate the focused row (Enter is
   * reserved globally for flagging). */
  function wireRowList(containerId, { onSelect, onCheck, rowSelector = '.row' } = {}) {
    const el = $(containerId);
    el.addEventListener('click', (e) => {
      const checkBtn = e.target.closest('[data-check-id]');
      if (checkBtn) {
        if (onCheck) onCheck(checkBtn.dataset.checkId);
        return;
      }
      const flagBtn = e.target.closest('[data-flag-kind]');
      if (flagBtn) {
        toggleEvidenceFlag(flagBtn.dataset.flagKind, flagBtn.dataset.flagId, flagBtn.dataset.flagLabel);
        const on = isFlagged(flagBtn.dataset.flagKind, flagBtn.dataset.flagId);
        flagBtn.setAttribute('aria-pressed', String(on));
        flagBtn.title = on ? 'Remove from evidence' : 'Flag as evidence (Enter)';
        if (flagBtn.dataset.flagKind === 'network') renderNetworkMetrics();
        else if (flagBtn.dataset.flagKind === 'crash') renderCrashesMetrics();
        return;
      }
      const row = e.target.closest(rowSelector);
      if (row && onSelect) onSelect(row.dataset.rowId ?? row.dataset.traceId);
    });
    el.addEventListener('keydown', (e) => {
      if (e.key !== ' ') return;
      const row = e.target.closest(rowSelector);
      if (!row || e.target !== row) return;
      e.preventDefault();
      row.click();
    });
  }
  /** Re-renders that replace a subtree via innerHTML destroy focus; these capture a stable
   * selector for the focused element (id or first data-* attribute) and restore it afterwards.
   * `container` is the element about to be re-rendered — when the selector falls back to a
   * data-* attribute (not guaranteed unique document-wide, e.g. the network compare view's two
   * side-by-side panes both emit `data-group-toggle` for like-named groups), restoreFocus must
   * only search inside that same container, never the whole document. */
  function captureFocus(container) {
    const el = document.activeElement;
    if (!el || el === document.body) return null;
    if (container && !container.contains(el)) return null;
    if (el.id) return { sel: '#' + CSS.escape(el.id), caret: el.selectionStart ?? null };
    for (const k in el.dataset) {
      const attr = 'data-' + k.replace(/[A-Z]/g, (ch) => '-' + ch.toLowerCase());
      return { sel: '[' + attr + '="' + CSS.escape(el.dataset[k]) + '"]', caret: null };
    }
    return null;
  }
  function restoreFocus(snap, container) {
    if (!snap) return;
    const el = (container || document).querySelector(snap.sel);
    if (!el) return;
    el.focus({ preventScroll: true });
    if (snap.caret != null && el.setSelectionRange) el.setSelectionRange(snap.caret, snap.caret);
  }

  function focusSelectedRow(containerId, index) {
    // `index` (the row's position in the view's logical order — see viewControllers below) lets a
    // virtualized container scroll the target row into the DOM before the query below looks for
    // it; callers that don't have an index (none currently) just skip that step.
    const virt = virtualLists.get(containerId);
    if (virt && index != null) virt.focusIndex(index);
    requestAnimationFrame(() => {
      const el = $(containerId)?.querySelector('.row.selected, .trace-row.selected');
      if (el) {
        el.focus({ preventScroll: true });
        el.scrollIntoView({ block: 'nearest' });
      }
    });
  }

  // ================================================================
  // Windowed list rendering: Network (#transactions), Timeline (#events), WebSockets
  // (#sockets — both its list- and timeline-mode row templates) and Push (#pushEvents) all page
  // through server data but used to render every fetched row into the DOM, so a long QA session
  // meant either clicking through pages or a DOM that grew without bound. One virtualizer per
  // list container renders only the rows scrolled into view plus a small overscan, standing two
  // spacer elements in for the rows above/below so native scroll geometry, `scrollIntoView`, and
  // the existing captureFocus/restoreFocus dance (used for the group-toggle re-renders elsewhere)
  // all keep working unmodified — the four render functions below only ever build the same
  // `rowHtml()`/row-template strings they always did, just for a window of indices instead of the
  // whole array.
  //
  // Row height is not a constant: body.mode-simple/advanced scale `--d-row-h`
  // (44px/34px) and `--d-trace-h` (54px/44px), so every repaint re-measures the real computed
  // value off the container rather than trusting a cached number, and `remeasure()` (wired to the
  // mode toggle below) keeps the row at the top of the viewport stable across a height change
  // instead of jumping to an unrelated scroll position.
  // ================================================================
  const virtualLists = new Map();

  function createVirtualList(containerId, { rowHeightVar = '--d-row-h', overscan = 8, onNearEnd } = {}) {
    const el = $(containerId);
    // The markup's aria-label belongs to the listbox role; both come off together in the empty
    // state (ARIA 1.2 prohibits aria-label on an implicit generic) and go back on with real rows.
    const listboxLabel = el.getAttribute('aria-label');
    let count = 0;
    let renderRow = () => '';
    let emptyHtml = '';
    let rowH = 32;
    let windowStart = -1;
    let windowEnd = -1;
    let rafId = 0;
    let nearEndFired = false;

    // `rowHeightVar` may be a getter (WebSockets swaps between --d-row-h and --d-trace-h
    // depending on List/Timeline sub-mode without ever recreating this virtualizer).
    const heightVar = () => (typeof rowHeightVar === 'function' ? rowHeightVar() : rowHeightVar);
    function measureRowHeight() {
      const n = parseFloat(getComputedStyle(el).getPropertyValue(heightVar()));
      return Number.isFinite(n) && n > 0 ? n : rowH;
    }
    function computeWindow() {
      const viewport = el.clientHeight || 400; // 400: a reasonable guess for the one tick where a
      // just-shown view hasn't been laid out yet — the render that follows navigation's class
      // toggle always repaints once real layout exists, so this only ever affects a single frame.
      const visibleRows = Math.ceil(viewport / rowH) + overscan * 2;
      // `start` must also be clamped against `count`, not just against 0: if the list shrinks
      // (a filter applied while scrolled deep into a longer list) `el.scrollTop` is still the old,
      // large value until the browser gets a chance to re-clamp it on the next layout, so the
      // naive `start` can land past `count`. That makes `end = Math.min(count, start + visibleRows)`
      // less than `start`, the row loop below never runs, and the list renders as blank even though
      // it has matching rows — the top spacer alone still reports the old scrollHeight, so native
      // scroll clamping never kicks in to fix it either. Capping `start` at `count - visibleRows`
      // (never below 0) guarantees `start <= count` and that the window always reaches `count`.
      const maxStart = Math.max(0, count - visibleRows);
      const start = Math.min(maxStart, Math.max(0, Math.floor(el.scrollTop / rowH) - overscan));
      return { start, end: Math.min(count, start + visibleRows) };
    }
    function paint(force) {
      rowH = measureRowHeight();
      if (!count) {
        windowStart = windowEnd = -1;
        el.innerHTML = emptyHtml;
        // The empty state (disconnected placeholder or a filtered-to-nothing message) is prose,
        // not `role="option"` rows — listbox only applies once real rows are on screen, so it
        // comes off here and goes back on below the moment there's something to select.
        el.removeAttribute('role');
        el.removeAttribute('aria-label');
        return;
      }
      el.setAttribute('role', 'listbox');
      if (listboxLabel) el.setAttribute('aria-label', listboxLabel);
      const { start, end } = computeWindow();
      if (!force && start === windowStart && end === windowEnd) return;
      windowStart = start;
      windowEnd = end;
      const focusSnap = captureFocus(el);
      let html = start > 0 ? `<div class="virt-spacer" style="height:${start * rowH}px"></div>` : '';
      for (let i = start; i < end; i++) html += renderRow(i, count);
      if (end < count) html += `<div class="virt-spacer" style="height:${(count - end) * rowH}px"></div>`;
      el.innerHTML = html;
      restoreFocus(focusSnap, el);
      // Roving tabindex meets virtualization: the selected row is normally the list's only tab
      // stop, but free scrolling can paint a window that doesn't contain it -- every painted row
      // then carries tabindex="-1" and Tab skips the whole listbox. Promote the first painted row
      // whenever no tab stop survived the repaint, so the list always stays in the Tab order.
      if (!el.querySelector('[tabindex="0"]')) {
        const first = el.querySelector('[role="option"]');
        if (first) first.setAttribute('tabindex', '0');
      }
    }
    function onScroll() {
      if (rafId) return;
      rafId = requestAnimationFrame(() => {
        rafId = 0;
        paint(false);
        if (onNearEnd && !nearEndFired && count) {
          const remaining = el.scrollHeight - el.scrollTop - el.clientHeight;
          if (remaining < rowH * overscan * 2) {
            nearEndFired = true;
            onNearEnd();
          }
        }
      });
    }
    el.addEventListener('scroll', onScroll, { passive: true });

    const api = {
      /** Called by the owning view's render function every time the backing array, filter, or
       * selection changes — a full re-supply, not an incremental patch, since these are cheap
       * string-built rows and the data model already recomputes on every change anyway. Resets
       * the near-end latch so a filter change can trigger another auto-load once scrolled back
       * toward the (new) end. */
      update(newCount, newRenderRow, newEmptyHtml) {
        count = newCount;
        renderRow = newRenderRow;
        emptyHtml = newEmptyHtml;
        nearEndFired = false;
        paint(true);
      },
      /** Guarantees `index` is inside the rendered window (scrolling if needed) and repaints
       * synchronously, so a caller can immediately query the DOM for it — used by the j/k/arrow
       * stepper so keyboard navigation reaches rows virtualization hasn't painted yet. */
      focusIndex(index) {
        if (index < 0 || index >= count) return;
        const target = index * rowH;
        if (target < el.scrollTop || target + rowH > el.scrollTop + el.clientHeight) {
          el.scrollTop = Math.max(0, target - el.clientHeight / 2 + rowH / 2);
        }
        paint(false);
      },
      /** Row height changed (Simple ↔ Advanced) — re-measure and repaint without disturbing which
       * *row* sits at the top of the viewport (index-based, not pixel-based, so a 44px→34px mode
       * switch doesn't jump to an unrelated scroll position). */
      remeasure() {
        const prevRowH = rowH || 1;
        const topIndex = Math.round(el.scrollTop / prevRowH);
        rowH = measureRowHeight();
        el.scrollTop = topIndex * rowH;
        paint(true);
      },
      /** Viewport size changed (window resize) without the row height changing. */
      relayout() {
        paint(true);
      },
    };
    virtualLists.set(containerId, api);
    return api;
  }
  /** Every render call site fetches (or lazily creates) the same virtualizer instance for its
   * container — creation only happens once, the first time a view actually has data to show. */
  function virtualList(containerId, opts) {
    return virtualLists.get(containerId) || createVirtualList(containerId, opts);
  }
  window.addEventListener('resize', () => virtualLists.forEach((v) => v.relayout()));

  // Per-view keyboard-step registration consumed by the `devconsole:step` custom event (j/k /
  // arrow keys, dispatched globally in wireEvents) and by the global Enter/`b` handlers.
  const viewControllers = {};
  document.addEventListener('devconsole:step', (e) => {
    const c = viewControllers[e.detail.view];
    if (!c) return;
    const order = c.order();
    if (!order.length) return;
    const cur = c.getSelected();
    let idx = order.indexOf(cur);
    idx = idx === -1 ? 0 : idx + e.detail.direction;
    idx = Math.max(0, Math.min(order.length - 1, idx));
    // select() may be async (Network fetches uncached detail); focus only after it re-rendered.
    Promise.resolve(c.select(order[idx])).then(() => focusSelectedRow(c.containerId, idx));
  });

  /** `chipsLabel` (e.g. "N of M shown ·") is optional — callers only pass it when
   * they already track a distinct shown/total pair; never invented for views that don't. */
  function appliedFiltersHtml(containerId, chips, resetFn, chipsLabel) {
    const wrap = $(containerId);
    if (!chips.length) {
      wrap.hidden = true;
      wrap.innerHTML = '';
      return;
    }
    wrap.hidden = false;
    wrap.innerHTML =
      (chipsLabel ? `<span class="applied-filters-label">${esc(chipsLabel)}</span>` : '') +
      chips
        .map(
          (c, i) =>
            `<button type="button" class="applied-filter" data-chip-index="${i}" title="${esc(c.title || 'Remove this filter')}"><span class="applied-filter-label">${esc(
              c.label,
            )}</span><span class="applied-filter-x">&times;</span></button>`,
        )
        .join('') + `<button type="button" class="applied-filters-reset" data-chip-reset>Reset all</button>`;
    wrap.onclick = (e) => {
      const resetBtn = e.target.closest('[data-chip-reset]');
      if (resetBtn) {
        resetFn();
        return;
      }
      const chip = e.target.closest('[data-chip-index]');
      if (chip) chips[Number(chip.dataset.chipIndex)].on();
    };
  }

  /** Formats any JSON-serializable value into mock-style {pad,k,v,cls} code lines, reusing the
   * `--json-*` tokens. `query` (lowercased) marks matching lines with the find-highlight class. */
  function formatJsonLines(value, query) {
    if (value === undefined) return [];
    const text = JSON.stringify(value, null, 2);
    const q = (query || '').trim().toLowerCase();
    return text.split('\n').map((line) => {
      const m = line.match(/^(\s*)"((?:[^"\\]|\\.)*)":\s?(.*)$/);
      let pad, k, v;
      if (m) {
        pad = m[1];
        k = `"${m[2]}": `;
        v = m[3];
      } else {
        pad = line.slice(0, line.length - line.trimStart().length);
        k = '';
        v = line.trim();
      }
      const rest = v.replace(/,$/, '');
      let cls = 'code-brace';
      if (rest === 'null') cls = 'json-null';
      else if (rest === 'true' || rest === 'false') cls = 'json-boolean';
      else if (/^-?\d/.test(rest)) cls = 'json-number';
      else if (rest.startsWith('"')) cls = 'json-string';
      const hit = q && (k + v).toLowerCase().includes(q);
      return { pad, k, v, cls, hit };
    });
  }
  /** The bare `.code-block` element, without the fullscreen affordance `codeBlockHtml` wraps around
   * it. Split out for the two callers that must not offer a nested expand: the fullscreen overlay
   * itself (already fullscreen) and the Remote Config value modal (a second overlay opening from
   * inside the first would fight it over Escape). */
  function codeLinesHtml(lines, large) {
    return `<div class="code-block${large ? ' code-block-lg' : ''}">${lines
      .map(
        (l) =>
          `<div class="code-line${l.hit ? ' hit' : ''}${l.diffHit ? ' diff' : ''}"><span class="code-pad">${esc(l.pad)}</span><span class="code-key">${esc(
            l.k,
          )}</span><span class="${l.cls}">${esc(l.v)}</span></div>`,
      )
      .join('')}</div>`;
  }
  /** `title` is a cheap, already-plain-text label for the fullscreen overlay's header (a group
   * label, card title, filename, etc.) — pass it whenever one is sitting in scope already; falls
   * back to "JSON body" in the overlay itself when omitted. Every code block gets the same
   * top-right fullscreen affordance (wireCodeFullscreen), wired once via delegated click. */
  function codeBlockHtml(lines, large, title) {
    if (!lines || !lines.length) return '';
    const block = codeLinesHtml(lines, large);
    return `<div class="code-block-wrap"><button type="button" class="code-fullscreen-btn" data-code-fullscreen${
      title ? ` data-code-title="${esc(title)}"` : ''
    } title="Full screen" aria-label="Full screen">${icon('expand', 'ic-sm')}</button>${block}</div>`;
  }
  /** Marks kv rows whose key/value matches the find query — same highlight contract as
   * `formatJsonLines`, so the find counter can count exactly what the pane renders. */
  function markKvHits(kvs, query) {
    const q = (query || '').trim().toLowerCase();
    if (!q) return kvs;
    return kvs.map((r) => ({ ...r, hit: (r.k + ' ' + r.v).toLowerCase().includes(q) }));
  }
  function countPaneHits(panes) {
    let n = 0;
    panes.forEach((p) => p.groups.forEach((g) => {
      if (g.kvs) n += g.kvs.filter((r) => r.hit).length;
      if (g.code) n += g.code.filter((l) => l.hit).length;
    }));
    return n;
  }
  function kvGridHtml(rows, large) {
    if (!rows.length) return '';
    return `<div class="kv-grid${large ? ' kv-grid-lg' : ''}">${rows
      .map((r) => `<span class="kv-k${r.hit ? ' hit' : ''}">${esc(r.k)}</span><span class="kv-v${r.tone ? ' tone-text-' + r.tone : ''}${r.hit ? ' hit' : ''}">${esc(r.v)}</span>`)
      .join('')}</div>`;
  }
  function barsHtml(bars) {
    if (!bars.length) return '';
    return `<div class="bars-grid">${bars
      .map(
        (b) =>
          `<div class="bar-row"><span class="bar-row-label">${esc(b.label)}</span><span class="bar-track"><span class="bar-fill tone-bg-${
            b.tone
          }" style="width:${b.pct}%"></span></span><span class="bar-row-val">${esc(b.val)}</span></div>`,
      )
      .join('')}</div>`;
  }
  function detailGroupHtml(key, group, openState) {
    // Default: Payload/Body groups open (the content you came here to read); every other group
    // (General, headers, diff non-body groups) starts collapsed. Groups with nothing but an
    // `empty` message stay open by default too — collapsing them would hide nothing. An explicit
    // user toggle (persisted in `openState`/networkGroupOpen) always wins over this default.
    const isBodyish = /^(payload|body)$/i.test(group.label);
    const onlyEmptyMessage = !group.kvs && !group.code && group.empty;
    const state = openState[key];
    const open = state === undefined ? (isBodyish || onlyEmptyMessage) : state;
    const count = group.kvs ? group.kvs.length : group.code ? group.code.length : 0;
    const bodyHtml = !open
      ? `<span class="detail-group-empty">${count ? count + ' line' + (count === 1 ? '' : 's') + ' hidden' : 'hidden'}</span>`
      : group.kvs
        ? kvGridHtml(group.kvs)
        : group.body
          ? bodyViewerHtml(group.body.raw, group.body.contentType, group.code, group.label, false, key, group.body.diffInfo, group.body.diffSig)
          : group.code
            ? codeBlockHtml(group.code, false, group.label)
            : group.empty
              ? `<span class="detail-group-empty">${esc(group.empty)}</span>`
              : '';
    return `<div class="detail-group">
      <div class="detail-group-head">
        <button type="button" class="detail-group-toggle" data-group-toggle="${esc(key)}" aria-expanded="${open}" title="${open ? 'Hide' : 'Show'} ${esc(
          group.label.toLowerCase(),
        )}">${icon('chevron')}<span class="detail-group-label">${esc(group.label)}</span></button>
        <span class="detail-group-rule"></span>
        <span class="detail-group-meta">${esc(group.meta || '')}</span>
        ${group.copyLabel && open && count ? `<button type="button" class="detail-group-copy" data-copy-group="${esc(group.copyLabel)}" title="Copy ${esc(group.copyLabel)}" aria-label="Copy ${esc(group.copyLabel)}">${icon('copy')}</button>` : ''}
      </div>
      ${bodyHtml}
    </div>`;
  }

  // ================================================================
  // Card-grid views: Overview, SDK Health, Composer, Mocks, Capture rules, State & Flags,
  // Preferences, Database, Files, Session & Security, Evidence tray. `cardHtml(c)` renders every
  // content block from a plain-object model built from real fetched data; callers never
  // hard-code example literals. Span capping lives in CSS (container queries), not here.
  // ================================================================
  function gateBannerHtml(gate) {
    if (!gate) return '';
    return `<div class="gate-banner">${icon('alert', 'ic-sm')}<div><div class="gate-banner-title">${esc(gate.title)}</div><div class="gate-banner-body">${esc(
      gate.body,
    )}</div></div><span class="gate-banner-code">${esc(gate.code)}</span></div>`;
  }
  /** Containers already carry class="metrics-strip" — return bare cells. */
  function metricsStripHtml(cells) {
    if (!cells || !cells.length) return '';
    return cells.map((m) => metricHtml(m.label, m.val, m.sub, m.tone || 'ink')).join('');
  }
  function cardStackHtml(segs) {
    if (!segs || !segs.length) return '';
    return `<div class="card-stack"><span class="card-stack-bar">${segs
      .map((s) => `<span class="tone-bg-${s.tone}" title="${esc(s.label)}" style="width:${s.pct}%"></span>`)
      .join('')}</span><div class="card-stack-legend">${segs
      .map((s) => `<span class="card-stack-legend-item"><span class="card-stack-dot tone-bg-${s.tone}"></span>${esc(s.label)}<span class="val">${esc(s.val)}</span></span>`)
      .join('')}</div></div>`;
  }
  /** `r.click`, when set, makes the row a real `<button>` dispatching that id through
   * wireCardGrid's `onRow` — a button rather than a clickable div so focus, Enter/Space and the
   * screen-reader role all come for free. Rows without it render exactly as before. */
  function cardRowsHtml(rows) {
    if (!rows || !rows.length) return '';
    return `<div class="card-rows">${rows
      .map((r) => {
        const inner = `<span class="card-row-k">${esc(r.k)}</span><span class="card-row-v tone-text-${r.tone || 'ink'}">${esc(r.v)}</span>${
          r.tag ? `<span class="card-row-tag tone-text-${r.tagTone || 'muted'}">${esc(r.tag)}</span>` : '<span></span>'
        }`;
        return r.click
          ? `<button type="button" class="card-row card-row-click" data-card-row="${esc(r.click)}" title="${esc(r.clickTitle || r.k)}">${inner}</button>`
          : `<div class="card-row">${inner}</div>`;
      })
      .join('')}</div>`;
  }
  function cardMetricsHtml(metrics) {
    if (!metrics || !metrics.length) return '';
    return `<div class="card-metrics">${metrics
      .map((m) => `<div class="card-metric"><div class="card-metric-label">${esc(m.label)}</div><div class="card-metric-val tone-text-${m.tone || 'ink'}">${esc(m.val)}</div></div>`)
      .join('')}</div>`;
  }
  /** `radioLabel` switches this from a set of independent `role="switch"` toggles (mocks rules,
   * state flags — any combination may be on) into a single-choice `role="radiogroup"` of
   * `role="radio"` options (evidence severity — exactly one is ever on) with roving tabindex:
   * only the checked option is a tab stop, matching the ARIA APG radio-group pattern. Arrow-key
   * navigation between options is wired once per container in wireCardGrid. */
  function cardTogglesHtml(toggles, radioLabel) {
    if (!toggles || !toggles.length) return '';
    const rows = toggles
      .map(
        (t) =>
          `<div class="card-toggle-row"><span class="card-toggle-text"><span class="card-toggle-k">${esc(t.k)}</span><span class="card-toggle-sub">${esc(
            t.sub || '',
          )}</span></span>${
            t.editable
              ? `<button type="button" class="row-flag" data-card-edit="${esc(t.id)}" title="Edit ${esc(t.k)}" aria-label="Edit ${esc(t.k)}">${icon('pencil', 'ic-sm')}</button>`
              : ''
          }${
            t.deletable
              ? `<button type="button" class="row-flag" data-card-del="${esc(t.id)}" title="Delete ${esc(t.k)}" aria-label="Delete ${esc(t.k)}">${icon('trash', 'ic-sm')}</button>`
              : ''
          }<button type="button" class="toggle-switch" role="${radioLabel ? 'radio' : 'switch'}" data-card-toggle="${esc(t.id)}" aria-checked="${!!t.checked}" ${
            radioLabel ? `tabindex="${t.checked ? '0' : '-1'}" ` : ''
          }${t.disabled ? 'disabled' : ''} title="${esc(t.title || t.k)}"><span class="knob"></span></button></div>`,
      )
      .join('');
    return radioLabel
      ? `<div class="card-toggles" role="radiogroup" aria-label="${esc(radioLabel)}">${rows}</div>`
      : `<div class="card-toggles">${rows}</div>`;
  }
  function cardTableHtml(table) {
    if (!table || !table.rows || !table.rows.length) return '';
    return `<div class="card-table-wrap"><table class="card-table"><thead><tr>${table.cols
      .map((h) => `<th scope="col">${esc(h)}</th>`)
      .join('')}</tr></thead><tbody>${table.rows
      .map((r) => `<tr>${r.map((c) => `<td class="tone-text-${c.tone || 'ink'}" title="${esc(c.v)}">${esc(c.v)}</td>`).join('')}</tr>`)
      .join('')}</tbody></table></div>`;
  }
  function cardFindingsHtml(findings) {
    if (!findings || !findings.length) return '';
    return `<div class="card-findings">${findings
      .map(
        (f) =>
          `<div class="card-finding card-finding-${f.tone}"><span class="card-finding-sev tone-text-${f.tone} tone-border-${f.tone}">${esc(
            f.sev,
          )}</span><div class="min-w0"><div class="card-finding-head"><span class="card-finding-title">${esc(f.title)}</span>${
            f.where ? `<span class="card-finding-where">${esc(f.where)}</span>` : ''
          }</div><div class="card-finding-body">${esc(f.body)}</div>${
            f.fix ? `<div class="card-finding-fix">${icon('check', 'ic-sm')}<span>${esc(f.fix)}</span></div>` : ''
          }</div></div>`,
      )
      .join('')}</div>`;
  }
  function cardTreeHtml(nodes, treeMax) {
    if (!nodes || !nodes.length) return '';
    return `<div class="card-tree"${treeMax ? ` style="--tree-max:${esc(String(treeMax))}"` : ''}>${nodes
      .map(
        (n) =>
          `<button type="button" class="card-tree-node${n.selected ? ' selected' : ''}" data-tree-id="${esc(n.id)}"${
            n.selected ? ' aria-current="true"' : ''
          } style="padding-left:${
            n.indent ?? 8
          }px" title="${esc(n.label)}">${n.icon ? icon(n.icon, 'ic-sm') : ''}<span class="card-tree-node-label tone-text-${n.tone || 'muted'}">${esc(
            n.label,
          )}</span><span class="card-tree-node-meta">${esc(n.meta || '')}</span></button>`,
      )
      .join('')}</div>`;
  }
  function cardButtonsHtml(buttons) {
    if (!buttons || !buttons.length) return '';
    return `<div class="card-buttons">${buttons
      .map(
        (b) =>
          `<button type="button" class="${b.kind === 'primary' ? 'primary' : b.kind === 'danger' ? 'danger' : ''}" data-card-btn="${esc(
            b.id,
          )}" ${b.disabled ? 'disabled' : ''} title="${esc(b.title || b.label)}">${b.icon ? icon(b.icon, 'ic-sm') : ''}${esc(b.label)}</button>`,
      )
      .join('')}</div>`;
  }
  function cardHtml(c) {
    return `<section class="card-shell"${c.span > 1 ? ` data-span="${c.span}"` : ''}>
      <div class="card-shell-head">${c.icon ? icon(c.icon, `tone-text-${c.iconTone || 'signal'}`) : ''}<h2 class="card-shell-title">${esc(
        c.title,
      )}</h2>${c.badge ? `<span class="card-shell-badge tone-text-${c.badgeTone || 'muted'}">${esc(c.badge)}</span>` : ''}</div>
      <div class="card-shell-body">
        ${c.lede ? `<p class="card-lede">${esc(c.lede)}</p>` : ''}
        ${c.fieldsHtml || ''}
        ${cardStackHtml(c.stack)}
        ${cardRowsHtml(c.rows)}
        ${cardMetricsHtml(c.metrics)}
        ${cardTogglesHtml(c.toggles, c.radioLabel)}
        ${c.code ? codeBlockHtml(c.code, true, c.title) : ''}
        ${cardTableHtml(c.table)}
        ${cardFindingsHtml(c.findings)}
        ${cardTreeHtml(c.tree, c.treeMax)}
        ${c.bodyHtml || ''}
        ${cardButtonsHtml(c.buttons)}
      </div>
    </section>`;
  }
  /** Renders a card list into `containerId`. Every view with a real capability gate to show
   * (composer/mocks/captureRules/state) renders its gateBannerHtml() into its own dedicated
   * `*Gate` element instead — see loadMockRules() etc. — since the gate's visibility needs to
   * survive independently of whatever else re-renders the card grid; no call site here has ever
   * needed a gate banner interleaved with the cards themselves. */
  function cardsGridHtml(containerId, cards) {
    const container = $(containerId);
    // Inputs marked data-preserve keep what the user typed across wholesale re-renders (e.g.
    // clicking an Evidence severity toggle must not reset the summary being written).
    const saved = {};
    container.querySelectorAll('[data-preserve][id]').forEach((el) => { saved[el.id] = el.value; });
    const focusSnap = captureFocus(container);
    container.innerHTML = `<div class="cards-grid">${cards.map(cardHtml).join('')}</div>`;
    Object.entries(saved).forEach(([id, v]) => { const el = $(id); if (el) el.value = v; });
    restoreFocus(focusSnap, container);
  }
  /** Delegated click wiring shared by every card-grid view: `data-card-toggle` (switch buttons),
   * `data-card-btn` (action buttons) and `data-tree-id` (tree rows) dispatch through the handler
   * map the caller supplies, mirroring how split-view row flag buttons are wired. */
  /** Idempotent per container: callers may re-invoke this on every render (handlers often close
   * over just-fetched data) without ever stacking a second DOM listener — the real listener is
   * attached once and always reads the latest handler set out of `cardGridHandlers`. */
  const cardGridHandlers = {};
  const wiredCardGrids = new Set();
  function wireCardGrid(containerId, handlers = {}) {
    cardGridHandlers[containerId] = handlers;
    if (wiredCardGrids.has(containerId)) return;
    wiredCardGrids.add(containerId);
    $(containerId).addEventListener('click', (e) => {
      const { onToggle, onButton, onTree, onDelete, onEdit, onRow } = cardGridHandlers[containerId];
      const ed = e.target.closest('[data-card-edit]');
      if (ed && onEdit) {
        onEdit(ed.dataset.cardEdit);
        return;
      }
      const d = e.target.closest('[data-card-del]');
      if (d && onDelete) {
        onDelete(d.dataset.cardDel);
        return;
      }
      const t = e.target.closest('[data-card-toggle]');
      if (t && onToggle) {
        onToggle(t.dataset.cardToggle);
        return;
      }
      const b = e.target.closest('[data-card-btn]');
      if (b && onButton) {
        onButton(b.dataset.cardBtn);
        return;
      }
      const row = e.target.closest('[data-card-row]');
      if (row && onRow) {
        onRow(row.dataset.cardRow);
        return;
      }
      const n = e.target.closest('[data-tree-id]');
      if (n && onTree) onTree(n.dataset.treeId);
    });
    // Roving-tabindex arrow-key navigation for any `role="radiogroup"` this container renders
    // (only the evidence-severity card today) — Up/Left and Down/Right move focus to the
    // adjacent option and select it in one step, per the ARIA APG radio-group pattern. The click
    // this dispatches re-renders the whole grid via cardsGridHtml, which already restores focus
    // to whatever `data-card-toggle` value was focused when the click fired (captureFocus).
    $(containerId).addEventListener('keydown', (e) => {
      if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key)) return;
      const radio = e.target.closest('[role="radio"]');
      if (!radio) return;
      const group = radio.closest('[role="radiogroup"]');
      if (!group) return;
      const radios = [...group.querySelectorAll('[role="radio"]')];
      const idx = radios.indexOf(radio);
      if (idx === -1) return;
      e.preventDefault();
      const dir = e.key === 'ArrowUp' || e.key === 'ArrowLeft' ? -1 : 1;
      const next = radios[(idx + dir + radios.length) % radios.length];
      next.focus();
      next.click();
    });
  }

  // ================================================================
  // Theme (persisted, no inline bootstrap script allowed by CSP)
  // ================================================================
  const THEME_STORAGE_KEY = 'devconsole-theme';
  const media = window.matchMedia('(prefers-color-scheme: dark)');

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
    return media.matches ? 'dark' : 'light';
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
    media.addEventListener('change', (event) => {
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

  // ================================================================
  // Simple / Advanced mode (persisted, next to the theme). Simple is the default;
  // nothing is ever deleted, Advanced just un-hides the rail's "Advanced" group and a handful of
  // per-view toolbar/detail controls. Every trim below is CSS (body.mode-simple/-advanced in
  // dashboard.css) except the rail regrouping, which genuinely moves DOM nodes (see
  // applyRailAdvancedGrouping) since the eight advanced-only views visually relocate into one
  // collapsed group rather than merely hiding in place.
  // ================================================================
  const RAIL_ADVANCED_IDS = ['viewSdkHealth', 'viewComposer', 'viewCaptureRules', 'viewState', 'viewRemoteConfig', 'viewPreferences', 'viewDatabase', 'viewFiles', 'viewSession'];
  const RAIL_ADVANCED_VIEWS = new Set(['sdkHealth', 'composer', 'captureRules', 'state', 'remoteConfig', 'preferences', 'database', 'files', 'session']);

  // ================================================================
  // Capture-category gating: which rail buttons/views a server-reported disabled category (see
  // /api/v1/meta's `captureCategories`) hides. Note the deliberate socket/mqtt overlap -- both
  // categories share the single Sockets rail entry, which stays visible as long as either is on
  // (see applyCaptureCategoryGating and the Sockets protocol-chip logic in renderSocketChips).
  // ================================================================
  const CATEGORY_RAIL = {
    network: { ids: ['viewNetwork'], views: ['network'] },
    socket: { ids: ['viewSockets'], views: ['socket'] },
    mqtt: { ids: ['viewSockets'], views: ['socket'] },
    push: { ids: ['viewPush'], views: ['push'] },
    logs: { ids: ['viewTimeline'], views: ['timeline'] },
    crashes: { ids: ['viewCrashes'], views: ['crashes'] },
    // Remote Config shares the STATE category with state providers and feature flags -- it is the
    // same kind of data, and the route is gated identically.
    state: { ids: ['viewState', 'viewRemoteConfig'], views: ['state', 'remoteConfig'] },
    inspection: { ids: ['viewPreferences', 'viewDatabase', 'viewFiles'], views: ['preferences', 'database', 'files'] },
    mocks: { ids: ['viewMocks', 'viewCaptureRules'], views: ['mocks', 'captureRules'] },
  };

  /** True when `view` belongs to at least one CATEGORY_RAIL entry and every one of its owning
   * categories is disabled -- i.e. navigating there would hit a route that now answers 403
   * CATEGORY_DISABLED. Views absent from CATEGORY_RAIL (overview, sdkHealth, session, evidence,
   * composer, ...) are never gated and always return false. Fail-open, matching
   * applyCaptureCategoryGating: an unresolved/unknown category set never blocks navigation. */
  function isViewDisabledByCapture(view) {
    if (enabledCaptureCategories == null) return false;
    const owningCategories = Object.keys(CATEGORY_RAIL).filter((category) => CATEGORY_RAIL[category].views.includes(view));
    if (owningCategories.length === 0) return false;
    return !owningCategories.some((category) => enabledCaptureCategories.includes(category));
  }

  /** Records each advanced-only rail button's original {parent, next-sibling} exactly once, so
   * switching back to Advanced mode can restore its exact source-order position instead of just
   * appending it at the end of a differently-ordered group (e.g. Composer belongs before Mocks). */
  function captureRailAdvancedHome() {
    if (railAdvancedHome) return;
    railAdvancedHome = new Map();
    RAIL_ADVANCED_IDS.forEach((id) => {
      const btn = $(id);
      if (btn) railAdvancedHome.set(id, { parent: btn.parentElement, next: btn.nextElementSibling });
    });
  }
  function applyRailAdvancedGrouping() {
    captureRailAdvancedHome();
    const body = $('railAdvancedBody');
    const group = $('railGroupAdvanced');
    const data = $('railGroupData');
    if (!body || !group) return;
    if (uiMode === 'simple') {
      RAIL_ADVANCED_IDS.forEach((id) => { const btn = $(id); if (btn && btn.parentElement !== body) body.appendChild(btn); });
      group.hidden = false;
      if (data) data.hidden = true;
    } else {
      // Reversed: some homes' `next` anchor is itself another advanced-only button (e.g. the
      // Data group's State→Preferences→Database→Files run) — restoring last-to-first guarantees
      // each `next` reference is either a stationary sibling (e.g. Mocks) or already back in its
      // parent by the time it's used, so insertBefore never targets a still-detached node.
      [...RAIL_ADVANCED_IDS].reverse().forEach((id) => {
        const btn = $(id);
        const home = railAdvancedHome.get(id);
        if (btn && home && btn.parentElement !== home.parent) home.parent.insertBefore(btn, home.next);
      });
      group.hidden = true;
      if (data) data.hidden = false;
    }
  }
  /**
   * Server-driven capture-category gating (see /api/v1/meta's `captureCategories`, added
   * alongside `capabilities`). `categories` is the exact enabled-category wire-name array from
   * meta; anything else (missing field, non-array -- an older SDK build) means "unknown" and is
   * fail-open: nothing gets hidden. Idempotent and safe to call again on every reconnect -- every
   * managed button's hidden state is recomputed from scratch each call, never accumulated. Unlike
   * applyRailAdvancedGrouping, this never relocates a button in the DOM: a disabled rail entry is
   * simply skipped via `.hidden`, the same trim mechanism every other CSS-driven rail rule uses.
   */
  function applyCaptureCategoryGating(categories) {
    enabledCaptureCategories = Array.isArray(categories) ? categories : null;
    const idsToCategories = new Map();
    Object.entries(CATEGORY_RAIL).forEach(([category, rail]) => {
      rail.ids.forEach((id) => {
        if (!idsToCategories.has(id)) idsToCategories.set(id, []);
        idsToCategories.get(id).push(category);
      });
    });
    idsToCategories.forEach((owningCategories, id) => {
      const btn = $(id);
      if (!btn) return;
      const enabled =
        enabledCaptureCategories == null ||
        owningCategories.some((category) => enabledCaptureCategories.includes(category));
      btn.hidden = !enabled;
    });
    // Re-derive the Data group's mode-driven baseline first (see applyRailAdvancedGrouping just
    // above), then layer the category-driven override on top so the two mechanisms never fight: a
    // group that's visible for the current mode still collapses once every one of its own buttons
    // is gated off by category. #railGroupData also holds viewState (gated independently by the
    // `state` category, not `inspection`), so it must be included here too -- otherwise a host
    // that disables INSPECTION but keeps STATE enabled would hide viewState along with the group.
    applyRailAdvancedGrouping();
    const dataGroup = $('railGroupData');
    const dataButtons = [...CATEGORY_RAIL.state.ids, ...CATEGORY_RAIL.inspection.ids].map((id) => $(id)).filter(Boolean);
    if (dataGroup && dataButtons.length > 0 && dataButtons.every((btn) => btn.hidden)) {
      dataGroup.hidden = true;
    }
    // Sockets view: pin the protocol filter to whichever single protocol is enabled, and hide the
    // protocol chip row entirely, when only one of socket/mqtt is on -- a disabled protocol's data
    // must never be one click away again. When both (or neither, fail-open/unknown) are on, the
    // row stays visible and whatever filter it currently holds is left alone.
    const socketOn = enabledCaptureCategories == null || enabledCaptureCategories.includes('socket');
    const mqttOn = enabledCaptureCategories == null || enabledCaptureCategories.includes('mqtt');
    if (socketOn && !mqttOn) socketProtocolFilter = 'websocket';
    else if (!socketOn && mqttOn) socketProtocolFilter = 'mqtt';
    const protocolGroup = $('socketProtocolGroup');
    if (protocolGroup) protocolGroup.hidden = enabledCaptureCategories != null && !(socketOn && mqttOn);
    const protocolSeg = $('socketProtocolSeg');
    if (protocolSeg) {
      const activeValue = socketProtocolFilter === 'all' ? '' : socketProtocolFilter;
      protocolSeg.querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.value === activeValue));
    }
  }
  /** Persisted independently of the mode itself — collapsed by default, but once a user opens it
   * (or navigation auto-expands it, see `show()`) it stays open across reloads. */
  function setRailAdvancedOpen(open) {
    railAdvancedOpen = open;
    const bodyEl = $('railAdvancedBody');
    if (bodyEl) bodyEl.hidden = !open;
    $('railAdvancedToggle')?.setAttribute('aria-expanded', String(open));
    $('railGroupAdvanced')?.classList.toggle('open', open);
    try {
      localStorage.setItem('devconsole-rail-advanced-open', open ? '1' : '0');
    } catch {
      /* storage unavailable */
    }
  }
  function applyModeIcon() {
    const btn = $('modeToggle');
    if (!btn) return;
    const advanced = uiMode === 'advanced';
    btn.innerHTML = icon(advanced ? 'collapse' : 'expand');
    btn.setAttribute('aria-pressed', String(advanced));
    btn.title = (advanced ? 'Advanced mode on — showing every view (a)' : 'Advanced mode off — Simple view (a)');
  }
  /** The Diff tab is allowed to stay in Simple mode (it needs a baseline the user pinned via the
   * `b` shortcut, which keeps working in both modes); Headers/Timing/Related do not survive a
   * switch to Simple, so land back on the always-visible Request & response tab rather than
   * leaving the detail pane rendering a tab with no visible way to reach it. */
  function normalizeNetworkTabForMode() {
    if (uiMode === 'simple' && !['compare', 'summary', 'diff'].includes(networkTab)) {
      networkTab = 'compare';
      if (currentView === 'network' && selectedTransactionDetail) renderNetworkDetail();
    }
  }
  function applyUiMode() {
    document.body.classList.toggle('mode-simple', uiMode === 'simple');
    document.body.classList.toggle('mode-advanced', uiMode === 'advanced');
    applyRailAdvancedGrouping();
    // Switching to simple while ON an advanced view must not strand the active rail entry
    // (and its aria-current) inside the collapsed group — same rule show() applies.
    if (uiMode === 'simple' && RAIL_ADVANCED_VIEWS.has(currentView) && !railAdvancedOpen) setRailAdvancedOpen(true);
    applyModeIcon();
    normalizeNetworkTabForMode();
    // Simple/Advanced scale --d-row-h and --d-trace-h; every virtualized list
    // must re-measure so a mode switch mid-scroll repaints at the new row height instead of
    // corrupting row positions against a stale one.
    virtualLists.forEach((v) => v.remeasure());
  }
  function initMode() {
    let saved = null;
    let openSaved = null;
    try {
      saved = localStorage.getItem('devconsole-ui-mode');
      openSaved = localStorage.getItem('devconsole-rail-advanced-open');
    } catch {
      /* storage unavailable */
    }
    uiMode = saved === 'advanced' ? 'advanced' : 'simple';
    applyUiMode();
    setRailAdvancedOpen(openSaved === '1');
  }
  function toggleMode() {
    uiMode = uiMode === 'simple' ? 'advanced' : 'simple';
    try {
      localStorage.setItem('devconsole-ui-mode', uiMode);
    } catch {
      /* storage unavailable */
    }
    applyUiMode();
    toast(uiMode === 'advanced' ? 'Advanced mode on' : 'Advanced mode off');
  }

  // ================================================================
  // Toast
  // ================================================================
  let toastTimer = null;
  let toastHideAt = 0;
  let toastRemainingMs = 0;
  /** (Re)arms the auto-dismiss timer for `ms` from now, tracking the absolute deadline so
   * hover-pause (below) can compute how much time was left when the pointer entered. */
  function scheduleToastDismiss(el, ms) {
    clearTimeout(toastTimer);
    toastHideAt = Date.now() + ms;
    toastTimer = setTimeout(() => el.classList.remove('show'), ms);
  }
  /**
   * @param message - plain text, always esc()'d before insertion — see composer response tree
   *   for the innerHTML-avoidance pattern this follows.
   * @param severity - 'success' (default, green check, brief) or 'error' (red alert glyph,
   *   persists ~3x longer with a close button and pause-on-hover, since the ~20 error-path call
   *   sites — "Save failed: 500" and friends — need to actually be readable, not flash by).
   */
  function toast(message, severity) {
    const el = $('toast');
    if (!el) return;
    const isError = severity === 'error';
    el.classList.toggle('toast-error', isError);
    el.innerHTML = icon(isError ? 'alert' : 'check', 'ic-sm') + '<span>' + esc(message) + '</span>';
    if (isError) {
      const closeBtn = document.createElement('button');
      closeBtn.type = 'button';
      closeBtn.className = 'toast-close';
      closeBtn.setAttribute('aria-label', 'Dismiss');
      closeBtn.innerHTML = icon('close', 'ic-sm');
      closeBtn.onclick = () => { clearTimeout(toastTimer); el.classList.remove('show'); };
      el.appendChild(closeBtn);
    }
    el.classList.add('show');
    // A fresh toast always gets its own full timer via scheduleToastDismiss below, regardless of
    // pointer position. But if the pointer is already resting on the toast element when this
    // fires (no mouseenter/mouseleave cycle happens in between — it never left), any leftover
    // toastRemainingMs from a *previous* toast's hover-pause would otherwise survive untouched;
    // a later mouseleave would then rearm this toast using that stale, unrelated duration instead
    // of leaving its own freshly-scheduled timer alone.
    toastRemainingMs = 0;
    scheduleToastDismiss(el, isError ? 6000 : 1900);
  }
  // Pause auto-dismiss while the pointer is over the toast — otherwise a reader who moves the
  // mouse toward an error toast to read it can have it vanish mid-read.
  (() => {
    const el = $('toast');
    if (!el) return;
    el.addEventListener('mouseenter', () => {
      if (!el.classList.contains('show')) return;
      toastRemainingMs = Math.max(0, toastHideAt - Date.now());
      clearTimeout(toastTimer);
    });
    el.addEventListener('mouseleave', () => {
      if (!el.classList.contains('show') || !toastRemainingMs) return;
      scheduleToastDismiss(el, toastRemainingMs);
      toastRemainingMs = 0;
    });
  })();

  // ================================================================
  // Confirm modal (replaces window.confirm for destructive actions)
  // ================================================================
  function openConfirm(title, message, confirmLabel) {
    const overlay = $('confirmModal');
    if (!overlay) return Promise.resolve(window.confirm(message));
    $('confirmTitle').textContent = title;
    $('confirmMessage').textContent = message;
    $('confirmSubmit').textContent = confirmLabel || 'Confirm';
    overlay.hidden = false;
    return new Promise((resolve) => {
      const cleanup = (result) => {
        overlay.hidden = true;
        okBtn.removeEventListener('click', onOk);
        cancelBtn.removeEventListener('click', onCancel);
        closeBtn.removeEventListener('click', onCancel);
        overlay.removeEventListener('click', onOverlay);
        document.removeEventListener('keydown', onKey);
        resolve(result);
      };
      const okBtn = $('confirmSubmit');
      const cancelBtn = $('confirmCancel');
      const closeBtn = $('confirmClose');
      const onOk = () => cleanup(true);
      const onCancel = () => cleanup(false);
      const onOverlay = (e) => {
        if (e.target === overlay) cleanup(false);
      };
      const onKey = (e) => {
        if (e.key === 'Escape') { cleanup(false); return; }
        if (e.key !== 'Tab') return;
        const focusable = [...overlay.querySelectorAll('button, input, select, textarea')].filter((el) => !el.disabled && el.offsetParent !== null);
        if (!focusable.length) return;
        const first = focusable[0], last = focusable[focusable.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      };
      okBtn.addEventListener('click', onOk);
      cancelBtn.addEventListener('click', onCancel);
      closeBtn.addEventListener('click', onCancel);
      overlay.addEventListener('click', onOverlay);
      document.addEventListener('keydown', onKey);
      okBtn.focus();
    });
  }

  // ================================================================
  // Code block fullscreen overlay — every codeBlockHtml() output (network req/res/diff bodies,
  // socket frame payload, push payload, state app-state block, files preview, mocks dialog
  // preview, evidence steps) gets the same top-right expand button (wired once, delegated). The
  // overlay clones the already-escaped .code-block markup rather than re-deriving it from data.
  // ================================================================
  let codeFullscreenOpenerEl = null;
  // Only ever one focusable in this modal (the close button — the cloned .code-block markup
  // carries no interactive elements), so the trap just keeps Tab/Shift+Tab pinned there instead
  // of leaking out to the page underneath.
  function codeFullscreenKeydown(e) {
    if (e.key !== 'Tab') return;
    e.preventDefault();
    $('codeFullscreenClose').focus();
  }
  function openCodeFullscreen(sourceBlock, title) {
    const overlay = $('codeFullscreenModal');
    if (!overlay || !sourceBlock) return;
    $('codeFullscreenTitle').textContent = title || 'JSON body';
    // Clones the already-escaped markup rather than re-deriving it from data, so this cannot
    // disagree with what the page underneath is showing.
    $('codeFullscreenBody').innerHTML = `<div class="code-block code-block-lg">${sourceBlock.innerHTML}</div>`;
    codeFullscreenOpenerEl = document.activeElement;
    overlay.hidden = false;
    document.addEventListener('keydown', codeFullscreenKeydown);
    $('codeFullscreenClose').focus();
  }
  function closeCodeFullscreen() {
    const overlay = $('codeFullscreenModal');
    if (!overlay || overlay.hidden) return;
    overlay.hidden = true;
    $('codeFullscreenBody').innerHTML = '';
    document.removeEventListener('keydown', codeFullscreenKeydown);
    codeFullscreenOpenerEl?.focus?.();
    codeFullscreenOpenerEl = null;
  }
  function wireCodeFullscreen() {
    document.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-code-fullscreen]');
      if (!btn) return;
      const block = btn.closest('.code-block-wrap')?.querySelector('.code-block');
      openCodeFullscreen(block, btn.dataset.codeTitle);
    });
    $('codeFullscreenClose').onclick = closeCodeFullscreen;
    $('codeFullscreenModal').addEventListener('click', (e) => { if (e.target.id === 'codeFullscreenModal') closeCodeFullscreen(); });
  }

  // ================================================================
  // Mock rule dialog (new rule / edit rule / "Mock this response") — a second modal on the same
  // .modal-overlay/.modal shell as #confirmModal, with its own focus trap and opener-focus
  // restore (confirmModal also traps Tab across its 3 buttons, but doesn't restore opener focus
  // on close). `rule` fields are always plain strings/numbers destined for `.value` — never
  // innerHTML — so no esc() is needed at assignment; the live JSON preview goes through
  // codeBlockHtml, which already esc()s every token.
  // ================================================================
  const MOCK_RULE_ID_RE = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/;
  const MOCK_HEADER_NAME_RE = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;
  function mockRuleFieldEls() {
    return {
      id: $('mockRuleId'), method: $('mockRuleMethod'), scheme: $('mockRuleScheme'), host: $('mockRuleHost'),
      path: $('mockRulePath'), status: $('mockRuleStatus'), delay: $('mockRuleDelay'), headers: $('mockRuleHeaders'),
      body: $('mockRuleBody'), priority: $('mockRulePriority'), scope: $('mockRuleScope'),
    };
  }
  function setMockDialogError(msg) {
    const el = $('mockRuleFormError');
    el.textContent = msg || '';
    el.hidden = !msg;
  }
  /** Points a field's aria-describedby at #mockRuleFormError while it's failing validation (in
   * addition to `helpId`, the field's own static help text, when it has one), and restores it to
   * just `helpId` — or drops the attribute entirely for fields with no help text — once it's
   * valid again. */
  function setFieldError(el, invalid, helpId) {
    el.setAttribute('aria-invalid', String(invalid));
    const ids = invalid ? [helpId, 'mockRuleFormError'].filter(Boolean) : helpId ? [helpId] : [];
    if (ids.length) el.setAttribute('aria-describedby', ids.join(' '));
    else el.removeAttribute('aria-describedby');
  }
  function syncMockRuleDialogGate() {
    const btn = $('mockRuleSave');
    const ok = canEditMocks();
    btn.disabled = !ok;
    btn.title = ok ? 'Save rule' : hasSession() ? 'The mocks capability is off for this build' : 'Sign in and enable the mocks capability';
  }
  /** Same rule server's `validMockHeader` (DevConsoleKtorModule.kt) enforces at the POST boundary
   * — validated here too so a typo surfaces as an inline dialog error instead of a round trip. */
  function validateMockRuleHeaders(text) {
    const lines = text.split('\n').map((l) => l.trim()).filter(Boolean);
    if (lines.length > 50) return 'Too many response headers (maximum 50).';
    for (const line of lines) {
      const idx = line.indexOf(':');
      const name = idx > 0 ? line.slice(0, idx).trim() : '';
      const value = idx > 0 ? line.slice(idx + 1).trim() : '';
      const valueOk = idx > 0 && [...value].every((ch) => ch === '\t' || (ch >= ' ' && ch <= '~'));
      if (!name || !MOCK_HEADER_NAME_RE.test(name) || !valueOk) {
        return `Malformed header line "${line}" — expected "Name: value" with an RFC-token name and printable-ASCII value.`;
      }
    }
    return null;
  }
  function refreshMockBodyEditor() {
    const raw = $('mockRuleBody').value;
    const statusEl = $('mockRuleBodyStatus');
    const previewEl = $('mockRuleBodyPreview');
    if (!raw.trim()) { statusEl.textContent = ''; statusEl.className = 'card-field-help'; previewEl.innerHTML = ''; return; }
    try {
      const parsed = JSON.parse(raw);
      statusEl.textContent = 'valid JSON';
      statusEl.className = 'card-field-help tone-text-signal';
      previewEl.innerHTML = codeBlockHtml(formatJsonLines(parsed), false, 'Mock rule body preview');
    } catch (err) {
      statusEl.textContent = 'not JSON — will be sent verbatim: ' + err.message;
      statusEl.className = 'card-field-help tone-text-warn';
      previewEl.innerHTML = '';
    }
  }
  function formatMockRuleBody() {
    const el = $('mockRuleBody');
    if (!el.value.trim()) return;
    try {
      el.value = JSON.stringify(JSON.parse(el.value), null, 2);
    } catch {
      /* non-JSON bodies are legitimate — leave verbatim, refreshMockBodyEditor reports it below */
    }
    refreshMockBodyEditor();
  }
  /** Only a plain (optionally delayed) static response with an untruncated body survives a round
   * trip through this dialog -- richer actions (ConnectionFailure/Timeout/TemplateResponse/etc.)
   * and truncated bodies have no representation in the form, so saving one back would silently
   * collapse it into StaticResponse(200, ""). Shared by the mock-rule-list edit-button gate, the
   * open guard, and the save-time id-collision guard below so all three agree on the same rule. */
  function isMockRuleEditable(rule) {
    return Boolean(rule) && (rule.action === 'StaticResponse' || rule.action === 'Delay') && rule.bodyTruncated !== true;
  }
  function openMockRuleDialog(rule, { editing = false } = {}) {
    const overlay = $('mockRuleModal');
    if (!overlay) return;
    mockRuleDraftSourceBodySnapshot = rule?.sourceBodySnapshot || null;
    const f = mockRuleFieldEls();
    f.id.value = rule?.id || '';
    f.id.disabled = editing;
    f.method.value = rule?.method || '';
    f.scheme.value = rule?.scheme || '';
    f.host.value = rule?.host || '';
    f.path.value = rule?.path || '';
    f.status.value = rule?.status ?? 200;
    f.delay.value = rule?.delayMs ?? '';
    f.headers.value = rule?.headers || '';
    f.body.value = rule?.body || '';
    f.priority.value = rule?.priority ?? 0;
    f.scope.value = rule?.scope || 'SESSION';
    setFieldError(f.id, false, 'mockRuleIdHelp');
    setFieldError(f.status, false);
    setFieldError(f.headers, false);
    setMockDialogError('');
    $('mockRuleModalTitleText').textContent = editing ? 'Edit mock rule' : 'New mock rule';
    refreshMockBodyEditor();
    syncMockRuleDialogGate();
    mockDialogOpenerEl = document.activeElement;
    overlay.hidden = false;
    document.addEventListener('keydown', mockDialogKeydown);
    overlay.addEventListener('click', mockDialogOverlayClick);
    (editing ? f.method : f.id).focus();
  }
  function closeMockRuleDialog() {
    const overlay = $('mockRuleModal');
    if (!overlay || overlay.hidden) return;
    overlay.hidden = true;
    document.removeEventListener('keydown', mockDialogKeydown);
    overlay.removeEventListener('click', mockDialogOverlayClick);
    mockDialogOpenerEl?.focus?.();
    mockDialogOpenerEl = null;
  }
  function mockDialogOverlayClick(e) {
    if (e.target === $('mockRuleModal')) closeMockRuleDialog();
  }
  function mockDialogKeydown(e) {
    if (e.key === 'Escape') { e.preventDefault(); closeMockRuleDialog(); return; }
    if (e.key !== 'Tab') return;
    const focusable = [...$('mockRuleModal').querySelectorAll('button, input, select, textarea')].filter((el) => !el.disabled && el.offsetParent !== null);
    if (!focusable.length) return;
    const first = focusable[0], last = focusable[focusable.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }
  function editMockRule(id) {
    const rule = mockRulesCache.find((r) => r.id === id);
    if (!rule) return;
    // Defense in depth alongside the pencil button's own `editable` gate (cardTogglesHtml/
    // loadMockRules) -- this is the only function that actually opens the dialog for an existing
    // rule, so it refuses on its own rather than trusting the button was never reachable.
    if (!isMockRuleEditable(rule)) {
      toast('This rule uses ' + rule.action + ' and can’t be edited here — saving would replace it with a plain 200 response. Delete and recreate it instead.', 'error');
      return;
    }
    openMockRuleDialog(
      {
        id: rule.id, method: rule.method || '', scheme: rule.scheme || '', host: rule.host || '',
        path: rule.path, status: rule.statusCode, delayMs: rule.delayMs,
        headers: Object.entries(rule.headers || {}).map(([k, v]) => k + ': ' + v).join('\n'),
        body: rule.body || '', priority: rule.priority, scope: rule.scope,
        // `sourceBodySnapshotTruncated` (see MockRule.json()) means the 64KB-capped value below is
        // an unparseable prefix of the real snapshot, not the snapshot itself — carrying it into
        // the draft (and back out to the server on save) would permanently clobber the engine's
        // full in-memory copy with garbage. Omit it here so saveMockRuleDialog leaves the
        // `sourceBodySnapshot` param off the POST entirely and the server keeps what it already has.
        sourceBodySnapshot: rule.sourceBodySnapshotTruncated ? null : rule.sourceBodySnapshot || null,
      },
      { editing: true },
    );
  }
  async function saveMockRuleDialog() {
    if (!canEditMocks()) return;
    const f = mockRuleFieldEls();
    const id = f.id.value.trim();
    const status = Number(f.status.value);
    const idValid = MOCK_RULE_ID_RE.test(id);
    const statusValid = Number.isInteger(status) && status >= 100 && status <= 599;
    setFieldError(f.id, !idValid, 'mockRuleIdHelp');
    setFieldError(f.status, !statusValid);
    if (!idValid || !statusValid) {
      setMockDialogError(!idValid ? 'Rule id must start with a letter or digit and contain only letters, digits, dot, underscore, or hyphen.' : 'Status must be an integer between 100 and 599.');
      (!idValid ? f.id : f.status).focus();
      return;
    }
    // POST /api/v1/mocks/rules upserts by id, so a "new rule" save whose id happens to collide
    // with an existing non-editable rule (a fault-injection/template action, or a truncated body)
    // would silently replace it with this form's plain StaticResponse -- exactly the destructive
    // save the pencil-button gate exists to prevent, just reached from the New-rule dialog instead.
    const collision = mockRulesCache.find((r) => r.id === id);
    if (collision && !isMockRuleEditable(collision)) {
      const msg = 'A rule named "' + id + '" already exists as ' + collision.action + ' — saving here would replace it with a plain 200 response. Delete it first or choose a different id.';
      setMockDialogError(msg);
      toast(msg, 'error');
      return;
    }
    const headerErr = f.headers.value.trim() ? validateMockRuleHeaders(f.headers.value) : null;
    setFieldError(f.headers, Boolean(headerErr));
    if (headerErr) { setMockDialogError(headerErr); f.headers.focus(); return; }
    const params = new URLSearchParams({
      id, status: String(status), path: f.path.value.trim() || '.*',
      priority: String(Number(f.priority.value) || 0), scope: f.scope.value || 'SESSION', body: f.body.value,
    });
    if (f.method.value) params.set('method', f.method.value);
    if (f.scheme.value.trim()) params.set('scheme', f.scheme.value.trim());
    if (f.host.value.trim()) params.set('host', f.host.value.trim());
    if (f.delay.value.trim()) params.set('delayMs', f.delay.value.trim());
    if (f.headers.value.trim()) params.set('headers', f.headers.value);
    // Carries through as captured at dialog-open time — never re-derived from the (possibly
    // user-edited) body field, since the whole point is a diff against the untouched original.
    if (mockRuleDraftSourceBodySnapshot) params.set('sourceBodySnapshot', mockRuleDraftSourceBodySnapshot);
    const r = await fetch('/api/v1/mocks/rules', {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params,
    });
    if (!r.ok) {
      let detail = 'Save failed: ' + r.status;
      try { const body = await r.json(); if (body.code) detail = 'Save failed: ' + body.code; } catch { /* non-JSON error body */ }
      setMockDialogError(detail);
      toast(detail, 'error');
      return;
    }
    toast('Mock rule saved.');
    closeMockRuleDialog();
    const focusSnap = captureFocus();
    await loadMockRules();
    restoreFocus(focusSnap);
  }

  // ================================================================
  // Collapsible JSON tree (used for detail panes instead of raw <pre>)
  // ================================================================
  const BRACE_LEVELS = 5;
  function jsonLeaf(text, cls) {
    const span = document.createElement('span');
    span.className = cls;
    span.textContent = text;
    return span;
  }
  function jsonBrace(ch, depth) {
    const span = document.createElement('span');
    span.className = 'brace brace-' + (depth % BRACE_LEVELS);
    span.textContent = ch;
    return span;
  }
  /** Canonical string form of a JSON-tree key-path array, used to key `diffInfo`'s `hits`/
   * `ancestors` sets -- `JSON.stringify` escapes every key exactly once regardless of its
   * content, so this is safe even for keys containing punctuation no hand-picked separator
   * could rule out. */
  function pathKey(path) {
    return JSON.stringify(path);
  }
  /**
   * `diffInfo` (optional) is `{hits, ancestors}` from `diffMockBody` — a mocked response's diff
   * against the transaction it was created from, each a `Set` of JSON-encoded key-path arrays
   * (see `pathKey`). `path` is this node's own key-path array, extended one key at a time by
   * `jsonContainer` as it recurses. Both are threaded through only so a row can be flagged as the
   * one that changed; when `diffInfo` is omitted (every call site except the mocked response body
   * viewer) `path` is never even read, so ordinary rendering pays nothing for this.
   */
  function jsonNode(value, depth, diffInfo, path) {
    if (value === null || value === undefined) return jsonLeaf('null', 'json-null');
    if (Array.isArray(value)) return jsonContainer(value.map((v, i) => [String(i), v]), '[', ']', depth, true, diffInfo, path);
    if (typeof value === 'object') return jsonContainer(Object.entries(value), '{', '}', depth, false, diffInfo, path);
    if (typeof value === 'string') return jsonLeaf(JSON.stringify(value), 'json-string');
    if (typeof value === 'number') return jsonLeaf(String(value), 'json-number');
    if (typeof value === 'boolean') return jsonLeaf(String(value), 'json-boolean');
    return jsonLeaf(String(value), 'json-null');
  }
  /** Nodes past this depth start collapsed so a deeply-nested real-world payload renders as a
   * skimmable outline instead of a wall of text; anything shallower stays open since that's
   * usually exactly what the developer came to read. */
  const JSON_AUTO_COLLAPSE_DEPTH = 2;
  /** Ceiling on how many sibling entries a single object/array container builds as real DOM
   * before stopping and leaving a clickable "… N more" stub for the rest. Without this, one huge
   * flat container (e.g. a 300KB `[1,1,1,…]` response body — depth 0, so JSON_AUTO_COLLAPSE_DEPTH
   * doesn't touch it) builds one DOM node per element with no ceiling at all — hundreds of
   * thousands of nodes, synchronously, on every render. */
  const JSON_TREE_CHUNK_SIZE = 200;
  function jsonContainer(entries, open, close, depth, isArray, diffInfo, path) {
    // Only the root call can arrive with diffInfo set and no path yet (every recursive call below
    // always passes an array, even an empty one, once diffInfo is active).
    if (diffInfo && !path) path = [];
    // True when this container itself -- not one of its children -- is the diff hit (an added
    // object/array; see diffMockBody). `path.length` excludes the root container: a whole-body
    // type mismatch was never highlighted before this change (there's no parent `line` for the
    // root to be marked on) and this keeps that unchanged rather than expanding scope. Guards
    // renderChunk's per-entry `line` highlight below from ever double-covering the same hit --
    // see the isContainerVal branch there for why a container hit is marked here instead.
    const selfHit = Boolean(diffInfo && path.length && diffInfo.hits.has(pathKey(path)));
    const box = document.createElement('div');
    box.className = 'json-tree-node';
    if (!entries.length) {
      box.append(jsonBrace(open, depth), jsonBrace(close, depth));
      if (selfHit) box.classList.add('json-diff-hit');
      return box;
    }
    const toggle = document.createElement('span');
    toggle.className = 'json-toggle';
    const arrow = document.createElement('span');
    arrow.className = 'json-arrow';
    toggle.append(arrow, jsonBrace(open, depth));
    // Collapsed-state summary — e.g. "12 keys" / "40 items" — so a collapsed node still says
    // what it's hiding instead of just vanishing into an open brace.
    const summary = document.createElement('span');
    summary.className = 'json-summary';
    const noun = isArray ? (entries.length === 1 ? 'item' : 'items') : entries.length === 1 ? 'key' : 'keys';
    summary.textContent = ' …' + close + ' ' + entries.length + ' ' + noun;
    toggle.append(summary);
    box.append(toggle);
    const children = document.createElement('div');
    children.className = 'json-children';
    box.append(children);
    const closeLine = document.createElement('div');
    closeLine.append(jsonBrace(close, depth));
    box.append(closeLine);
    // Scoped to the container's own open/close brace rows -- mirrors Compose's
    // appendContainerRows, which marks only the ContainerStart/ContainerEnd rows for an added
    // container, leaving its (unrelated, already-present-in-neither-diff) interior untinted.
    if (selfHit) {
      toggle.classList.add('json-diff-hit');
      closeLine.classList.add('json-diff-hit');
    }
    // Children are built on demand, not eagerly: a container that starts collapsed
    // (depth >= JSON_AUTO_COLLAPSE_DEPTH) never pays to construct its subtree until the user
    // actually expands it — CSS `display:none` alone doesn't skip DOM construction, only
    // painting, so this is the part that actually keeps a never-opened subtree free. Even once
    // building starts, it happens in JSON_TREE_CHUNK_SIZE-entry chunks behind a "… N more" stub
    // so a single container's own breadth (a huge flat array, still open by default at low
    // depth) is bounded too.
    let builtThrough = 0;
    let stub = null;
    function renderChunk() {
      const from = builtThrough;
      const upto = Math.min(entries.length, from + JSON_TREE_CHUNK_SIZE);
      for (let index = from; index < upto; index++) {
        const [key, val] = entries[index];
        const line = document.createElement('div');
        const keySpan = document.createElement('span');
        keySpan.className = 'json-key';
        keySpan.textContent = JSON.stringify(key) + ': ';
        // diffInfo is only ever set for the one mocked-response body viewer that requests it
        // (see diffMockBody) — every other jsonNode/jsonContainer call in the app passes nothing,
        // so childPath stays undefined and this is just an extra falsy check per row.
        const childPath = diffInfo ? path.concat([key]) : undefined;
        // A hit whose value is a container (object/array) is marked on that container's own
        // open/close brace rows by jsonContainer's own selfHit check once jsonNode recurses into
        // it below, not here -- marking the whole `line` would also cover every descendant row
        // nested inside it (the entire subtree renders as this `line`'s DOM children), painting a
        // solid block instead of just flagging the one new field. A scalar hit has no such
        // subtree, so it keeps the row-level highlight on `line` itself.
        const isContainerVal = val !== null && typeof val === 'object';
        if (diffInfo && !isContainerVal && diffInfo.hits.has(pathKey(childPath))) line.classList.add('json-diff-hit');
        line.append(keySpan, jsonNode(val, depth + 1, diffInfo, childPath));
        if (index < entries.length - 1) line.append(document.createTextNode(','));
        children.append(line);
      }
      builtThrough = upto;
      if (stub) { stub.remove(); stub = null; }
      if (builtThrough < entries.length) {
        stub = document.createElement('button');
        stub.type = 'button';
        stub.className = 'json-more-stub';
        stub.textContent = '… ' + (entries.length - builtThrough) + ' more';
        stub.addEventListener('click', renderChunk);
        children.append(stub);
      }
    }
    let childrenBuilt = false;
    const buildChildren = () => {
      if (childrenBuilt) return;
      childrenBuilt = true;
      renderChunk();
    };
    const setCollapsed = (collapsed) => {
      box.classList.toggle('json-collapsed', collapsed);
      arrow.textContent = collapsed ? '▸ ' : '▾ ';
      if (!collapsed) buildChildren();
    };
    // A container past the auto-collapse depth still starts open if a diff highlight lives
    // somewhere inside it -- otherwise the very thing this feature exists to surface would sit
    // behind a manual click on every payload with more than two levels of nesting.
    const forceOpenForDiff = diffInfo && diffInfo.ancestors.has(pathKey(path));
    setCollapsed(depth >= JSON_AUTO_COLLAPSE_DEPTH && !forceOpenForDiff);
    toggle.addEventListener('click', () => setCollapsed(!box.classList.contains('json-collapsed')));
    return box;
  }
  function renderJson(container, value, emptyText) {
    container.innerHTML = '';
    if (value === undefined || value === null) {
      container.textContent = emptyText || '—';
      return;
    }
    const wrap = document.createElement('div');
    wrap.className = 'json-tree';
    wrap.appendChild(jsonNode(value, 0));
    container.appendChild(wrap);
  }

  // ================================================================
  // Body viewer: JSON/XML pretty-print + collapsible-tree, layered as a Pretty/Raw toggle over
  // network request/response bodies and text socket frames (task: "JSON/XML beautification +
  // collapsible objects"). Raw mode is always the pre-existing esc()'d code-block view — nothing
  // that isn't detected JSON/XML (or that's over the size guard) changes appearance at all.
  // ================================================================
  const MAX_BODY_FORMAT_BYTES = 300 * 1024;
  /** `String.length` counts UTF-16 code units, not bytes — it undercounts any body with
   * multi-byte characters (accented text, CJK, emoji), letting them slip past the intended
   * ~300KB guard into the parser/DOM-builder below. TextEncoder gives the exact UTF-8 byte
   * count the guard is meant to measure. */
  function utf8ByteLength(s) {
    return new TextEncoder().encode(s).length;
  }
  /** DOMParser, not a regex/string check — a body that merely starts with `<` (an HTML error
   * page, a stray tag in a text body) must not be mis-rendered as if it were well-formed XML. */
  function looksLikeXml(text) {
    try {
      const doc = new DOMParser().parseFromString(text, 'application/xml');
      return !doc.querySelector('parsererror');
    } catch {
      return false;
    }
  }
  function detectBodyKind(raw, contentType) {
    const trimmed = (raw || '').trim();
    if (!trimmed) return 'text';
    const ct = (contentType || '').toLowerCase();
    if (ct.includes('json') || /^[[{]/.test(trimmed)) {
      try {
        JSON.parse(trimmed);
        return 'json';
      } catch {
        /* not valid JSON after all — fall through to the XML/text checks below */
      }
    }
    if ((ct.includes('xml') || /^<\?xml/i.test(trimmed) || trimmed.startsWith('<')) && looksLikeXml(trimmed)) return 'xml';
    return 'text';
  }
  /** Walks the parsed DOM and rebuilds indented (2-space) source — the only reliable way to
   * "pretty-print" XML, since unlike JSON there's no whitespace-preserving stringify for it.
   * Returns null on anything unparsable so the caller falls back to the raw body untouched. */
  function prettyPrintXml(text) {
    let doc;
    try {
      doc = new DOMParser().parseFromString(text, 'application/xml');
    } catch {
      return null;
    }
    if (!doc?.documentElement || doc.querySelector('parsererror')) return null;
    const lines = [];
    // `&` must be escaped first so it doesn't double-escape the entities produced by the next
    // two replacements. Purely for well-formed-looking reconstructed source text — the result
    // only ever reaches the page via a <pre> textContent assignment (mountBodyViewers), never
    // innerHTML, so this isn't a sink; it's just correctness for what the user reads/copies.
    const escXmlAttr = (v) => v.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
    const attrString = (el) => [...el.attributes].map((a) => ` ${a.name}="${escXmlAttr(a.value)}"`).join('');
    // DOMParser is iterative and happily parses a pathologically deep-but-well-formed document
    // (thousands of nested elements); this walk is recursive and would blow the call stack on
    // exactly that input. Cap explicitly and bail to the raw-body fallback the caller already
    // has for anything else this function can't handle, rather than let a RangeError propagate
    // out of mountBodyViewers mid-render and leave the pane half-initialised.
    const MAX_WALK_DEPTH = 256;
    const walk = (node, depth) => {
      if (depth > MAX_WALK_DEPTH) throw new RangeError('prettyPrintXml: max walk depth exceeded');
      const pad = '  '.repeat(depth);
      if (node.nodeType === Node.COMMENT_NODE) {
        lines.push(pad + '<!--' + node.textContent + '-->');
        return;
      }
      if (node.nodeType !== Node.ELEMENT_NODE) return;
      const childElements = [...node.childNodes].filter((n) => n.nodeType === Node.ELEMENT_NODE || n.nodeType === Node.COMMENT_NODE);
      const textContent = [...node.childNodes]
        .filter((n) => n.nodeType === Node.TEXT_NODE)
        .map((n) => n.textContent)
        .join('')
        .trim();
      const open = '<' + node.nodeName + attrString(node);
      if (!childElements.length && !textContent) {
        lines.push(pad + open + '/>');
      } else if (!childElements.length) {
        lines.push(pad + open + '>' + textContent + '</' + node.nodeName + '>');
      } else {
        lines.push(pad + open + '>');
        childElements.forEach((child) => walk(child, depth + 1));
        lines.push(pad + '</' + node.nodeName + '>');
      }
    };
    try {
      walk(doc.documentElement, 0);
    } catch {
      return null;
    }
    return lines.join('\n');
  }
  // Placeholders registered while a detail pane's HTML string is being assembled, then mounted
  // as real DOM (createElement/textContent — never innerHTML of body content) right after the
  // pane's innerHTML is assigned. Reset per render so stale entries from a previous selection
  // can never leak into a later pane.
  let bodyViewerSeq = 0;
  const pendingBodyViewers = new Map();
  // NOT cleared by mountBodyViewers — a render pass's entries stay valid (for the copy/fullscreen
  // toolbar buttons, which look raw text up by mountKey on click, arbitrarily later) until the
  // *next* render pass starts and resets them here. Mount keys are always reassigned from 'bv0'
  // in the same order each render, so at most a render's worth of entries is ever held.
  function resetBodyViewers() {
    pendingBodyViewers.clear();
    bodyViewerSeq = 0;
  }
  // Persists ACROSS render passes (unlike pendingBodyViewers) — without this, re-rendering the
  // very same transaction's body (a find-input keystroke, a live-tail refresh touching unrelated
  // rows) rebuilds the whole Pretty tree from scratch even though nothing about that body
  // changed, since renderNetworkDetail() always reassigns pane.innerHTML wholesale. Keyed on the
  // logical slot (group key / frame id) plus the exact raw text, so a different transaction or
  // frame — different raw content — always misses and rebuilds correctly; a hit is reattached via
  // plain `appendChild` (a DOM move), which keeps the toggle/"more" button listeners already
  // wired to it intact — cloning would lose them. Capped and dropped wholesale past a small
  // ceiling so a long session paging through many distinct bodies can't grow this unboundedly.
  const bodyViewerTreeCache = new Map();
  const BODY_VIEWER_TREE_CACHE_MAX = 30;
  function buildOrReuseBodyViewerTree(groupKey, kind, raw, diffSig, build) {
    // `diffSig` (see `renderNetworkDetail`'s `mockDiffSig`) folds the mock rule id + its source
    // snapshot into the key for a diff-highlighted tree, so a rule/snapshot change always misses
    // and rebuilds instead of silently reusing a tree highlighted for a different diff — while an
    // unchanged diff (or no diff at all: '') still hits like any other body.
    const cacheKey = kind + ':' + groupKey + ':' + (diffSig || '') + ':' + raw;
    let node = bodyViewerTreeCache.get(cacheKey);
    if (node) return node;
    node = build();
    if (bodyViewerTreeCache.size >= BODY_VIEWER_TREE_CACHE_MAX) bodyViewerTreeCache.clear();
    bodyViewerTreeCache.set(cacheKey, node);
    return node;
  }
  function mountBodyViewers(root) {
    root.querySelectorAll('[data-body-mount]').forEach((el) => {
      const entry = pendingBodyViewers.get(el.dataset.bodyMount);
      if (!entry) return;
      if (entry.kind === 'json') {
        try {
          // `diffSig` (see buildOrReuseBodyViewerTree) folds the mock rule id + snapshot into the
          // cache key, so a diff-highlighted tree is reused across re-renders just like a plain
          // one instead of always being rebuilt — a stale hit is impossible because the key itself
          // changes whenever the rule or its snapshot does.
          const wrap = buildOrReuseBodyViewerTree(entry.groupKey, 'json', entry.raw, entry.diffSig, () => {
            const w = document.createElement('div');
            w.className = 'json-tree';
            w.appendChild(jsonNode(JSON.parse(entry.raw), 0, entry.diffInfo));
            return w;
          });
          el.appendChild(wrap);
        } catch {
          el.textContent = entry.raw;
        }
      } else if (entry.kind === 'xml') {
        try {
          const pre = buildOrReuseBodyViewerTree(entry.groupKey, 'xml', entry.raw, undefined, () => {
            const pretty = prettyPrintXml(entry.raw);
            const p = document.createElement('pre');
            p.className = 'body-viewer-xml';
            p.textContent = pretty ?? entry.raw;
            return p;
          });
          el.appendChild(pre);
        } catch {
          el.textContent = entry.raw;
        }
      }
    });
  }
  /** `codeLines` is the pane's existing mock-style highlighted representation (same one used
   * everywhere else) — reused verbatim for Raw mode so find-highlighting, diff-highlighting, and
   * the fullscreen/copy affordances all keep working unchanged. Pretty mode is additive: shown
   * by default only when the body is detected JSON/XML and under the size guard. `groupKey` is a
   * stable identifier for this body's slot (e.g. "request/Payload", a socket frame id) — used only
   * to namespace the tree cache above, never rendered. `diffInfo` (optional, see `getMockDiff`)
   * highlights this body's Pretty-mode JSON tree against a mock rule's source snapshot; only ever
   * passed for a mocked transaction's response body. `diffSig` is `diffInfo`'s cache-key
   * counterpart (see `renderNetworkDetail`'s `mockDiffSig`) — required whenever `diffInfo` is set,
   * so the tree-reuse cache below can tell "same diff as last render" apart from "different rule
   * or snapshot, must rebuild". */
  function bodyViewerHtml(raw, contentType, codeLines, title, large, groupKey, diffInfo, diffSig) {
    const rawBlock = codeLines && codeLines.length
      ? codeBlockHtml(codeLines, Boolean(large), title)
      : `<span class="detail-group-empty">${raw ? esc(raw) : '(empty body)'}</span>`;
    if (raw == null) return rawBlock;
    // Fast path: raw.length (UTF-16 code units) is always <= the exact UTF-8 byte count, so it's
    // a valid lower bound — if it already exceeds the guard, the real byte count must too, and
    // running a full TextEncoder pass just to confirm what's already known would be exactly the
    // cost the guard exists to avoid (e.g. a 10MB body). Only bodies in the ambiguous range (short
    // in UTF-16 length but potentially long in UTF-8 bytes — heavy CJK/emoji content) pay for the
    // exact check below.
    if (raw.length > MAX_BODY_FORMAT_BYTES) {
      return `<div class="detail-group-empty">Body is ${formatBytes(raw.length)}+ — too large to format. Showing raw.</div>${rawBlock}`;
    }
    const byteLength = utf8ByteLength(raw);
    if (byteLength > MAX_BODY_FORMAT_BYTES) {
      return `<div class="detail-group-empty">Body is ${formatBytes(byteLength)} — too large to format. Showing raw.</div>${rawBlock}`;
    }
    const kind = detectBodyKind(raw, contentType);
    if (kind === 'text') return rawBlock;
    const mountKey = 'bv' + bodyViewerSeq++;
    pendingBodyViewers.set(mountKey, { raw, kind, groupKey: groupKey || title || mountKey, diffInfo, diffSig });
    return `<div class="body-viewer">
      <div class="body-viewer-toolbar">
        <div class="seg body-viewer-seg" role="group" aria-label="Body format">
          <button type="button" class="active" data-body-mode="pretty">Pretty</button>
          <button type="button" data-body-mode="raw">Raw</button>
        </div>
        <div class="body-viewer-tools">
          <button type="button" class="body-viewer-tool-btn" data-body-copy="${esc(mountKey)}" title="Copy body" aria-label="Copy body">${icon('copy', 'ic-sm')}</button>
          <button type="button" class="body-viewer-tool-btn" data-body-fullscreen="${esc(mountKey)}" data-body-title="${esc(title || '')}" title="Full screen" aria-label="Full screen">${icon('expand', 'ic-sm')}</button>
        </div>
      </div>
      <div class="body-viewer-pretty" data-body-mount="${esc(mountKey)}"></div>
      <div class="body-viewer-raw" hidden>${rawBlock}</div>
    </div>`;
  }

  // ================================================================
  // Resizable list | splitter | detail panes: a 5px draggable splitter between the list and
  // detail panes of each `.split-shell` grid.
  //
  // Beyond plain resizing, the splitter can collapse either pane entirely: drag it past a
  // threshold near an edge (or press Home/End, or double-click to reset) and that pane's grid
  // column zeroes out via a `.collapse-list` / `.collapse-detail` class on `.split-shell` (see
  // dashboard.css) while the splitter itself stays put at the edge, still draggable/focusable, so
  // the gesture is reversible. Width and collapsed state persist per view (keyed off the nearest
  // `[id$="View"]` ancestor, e.g. "network") under 'devconsole-split-<view>'.
  // ================================================================
  // Reassigned by initSplitters(); show() calls it after toggling `.active` because aria values
  // are measured from live geometry — at boot every split view is display:none, so the initial
  // sync reads all-zero rects and the visible splitter would report aria-valuenow="0" until the
  // first drag, keypress or window resize.
  let syncSplitterAria = () => {};
  function initSplitters() {
    const MIN_WIDTH = 360;
    const maxWidth = () => Math.max(MIN_WIDTH, window.innerWidth - 420);
    // How far past the normal MIN_WIDTH/maxWidth() stop the pointer has to travel before a
    // release commits to fully collapsing that pane — matches the "under ~160px" spec.
    const COLLAPSE_MARGIN = 160;
    const splitters = [...document.querySelectorAll('.splitter')];
    const syncFns = [];
    splitters.forEach((splitter) => {
      const shell = splitter.parentElement;
      const listPane = splitter.previousElementSibling;
      const viewKey = shell.closest('[id$="View"]')?.id.replace(/View$/, '') || null;
      const storageKey = viewKey ? `devconsole-split-${viewKey}` : null;

      // 'list' | 'detail' | null (null = normal, resizable state).
      let collapsed = null;
      // Last dragged/keyed width, kept even while collapsed so restoring (drag back out,
      // Arrow key, double-click) lands somewhere sane instead of snapping to a default.
      let lastWidth = null;

      const readSaved = () => {
        if (!storageKey) return null;
        try {
          const raw = localStorage.getItem(storageKey);
          const parsed = raw ? JSON.parse(raw) : null;
          return parsed && typeof parsed === 'object' ? parsed : null;
        } catch {
          return null;
        }
      };
      const persist = () => {
        if (!storageKey) return;
        try {
          localStorage.setItem(storageKey, JSON.stringify({ width: lastWidth, collapsed }));
        } catch {
          /* storage unavailable */
        }
      };

      const currentWidth = () => listPane.getBoundingClientRect().width;
      const applyCollapseClass = () => {
        shell.classList.toggle('collapse-list', collapsed === 'list');
        shell.classList.toggle('collapse-detail', collapsed === 'detail');
      };
      const restoreFallback = () => (lastWidth != null ? lastWidth : Math.round((MIN_WIDTH + maxWidth()) / 2));

      /** Keeps aria-valuemin/max/now (and a valuetext for the two collapsed states) in sync;
       * called after every state change and on resize. */
      const syncAria = () => {
        if (collapsed === 'list') {
          splitter.setAttribute('aria-valuemin', '0');
          splitter.setAttribute('aria-valuemax', String(Math.round(maxWidth())));
          splitter.setAttribute('aria-valuenow', '0');
          splitter.setAttribute('aria-valuetext', 'List pane collapsed');
        } else if (collapsed === 'detail') {
          const shellW = shell.getBoundingClientRect().width;
          const splitW = splitter.getBoundingClientRect().width || 5;
          const full = Math.max(MIN_WIDTH, Math.round(shellW - splitW));
          splitter.setAttribute('aria-valuemin', String(MIN_WIDTH));
          splitter.setAttribute('aria-valuemax', String(full));
          splitter.setAttribute('aria-valuenow', String(full));
          splitter.setAttribute('aria-valuetext', 'Detail pane collapsed');
        } else {
          splitter.setAttribute('aria-valuemin', String(MIN_WIDTH));
          splitter.setAttribute('aria-valuemax', String(Math.round(maxWidth())));
          splitter.setAttribute('aria-valuenow', String(Math.round(currentWidth())));
          splitter.removeAttribute('aria-valuetext');
        }
      };
      syncFns.push(syncAria);

      // Custom property, not an inline grid-template-columns: the `body.detail-zoom` and
      // <960px stacking rules must still be able to override the dragged/keyed width.
      const setWidth = (width) => {
        const clamped = Math.max(MIN_WIDTH, Math.min(width, maxWidth()));
        collapsed = null;
        applyCollapseClass();
        shell.style.setProperty('--list-w', clamped + 'px');
        lastWidth = clamped;
        syncAria();
        return clamped;
      };
      const collapse = (which) => {
        collapsed = which;
        applyCollapseClass();
        syncAria();
      };

      // ---- Restore persisted width/collapse state before this splitter's first paint. ----
      const saved = readSaved();
      if (saved && (saved.collapsed === 'list' || saved.collapsed === 'detail')) {
        lastWidth = typeof saved.width === 'number' ? saved.width : null;
        if (lastWidth != null) shell.style.setProperty('--list-w', Math.max(MIN_WIDTH, Math.min(lastWidth, maxWidth())) + 'px');
        collapsed = saved.collapsed;
        applyCollapseClass();
      } else if (saved && typeof saved.width === 'number') {
        setWidth(saved.width);
      }
      syncAria();

      let startX = 0;
      let startWidth = 0;
      let dragging = false;
      // Set mid-drag once the pointer is past COLLAPSE_MARGIN on either side; committed on
      // release, so a drag that merely grazes the threshold and comes back never collapses.
      // Gated behind rAF like the virtualizer's onScroll above (~471-484): pointermove can fire far
      // faster than layout needs updating, and setWidth (plus the collapse-threshold class toggles
      // below) triggers layout/paint. Only the latest pending raw value per frame is applied;
      // endDrag below still flushes whatever value arrived after the last painted frame so the
      // drag never ends stale or skips its collapse check.
      let pendingCollapse = null;
      let dragRafId = 0;
      let pendingWidth = null;
      const applyPendingDrag = () => {
        dragRafId = 0;
        if (pendingWidth == null) return;
        const raw = pendingWidth;
        pendingWidth = null;
        // Shell-relative, not window-relative: `raw` is already ~clientX - shellLeft (it's seeded
        // from currentWidth(), the list pane's own width), so the detail-collapse threshold has to
        // be measured against the shell's own width too. maxWidth() (window.innerWidth - 420) only
        // coincides with the shell's actual right edge when the shell starts flush at the window's
        // left edge; with the nav rail expanded and/or `.main-inner`'s centering max-width, the
        // shell's real edge sits well short of that, making the old window-relative check
        // unreachable at common viewport widths.
        const splitterWidth = splitter.getBoundingClientRect().width || 5;
        const shellDetailMax = Math.max(MIN_WIDTH, shell.getBoundingClientRect().width - splitterWidth);
        const willList = raw < MIN_WIDTH - COLLAPSE_MARGIN;
        const willDetail = !willList && raw > shellDetailMax + COLLAPSE_MARGIN;
        pendingCollapse = willList ? 'list' : willDetail ? 'detail' : null;
        shell.classList.toggle('collapsing-list', willList);
        shell.classList.toggle('collapsing-detail', willDetail);
        // In either collapse zone, freeze the visible width at the nearer normal bound (setWidth
        // already clamps there) so the pane doesn't visually vanish before the drag is committed.
        setWidth(raw);
      };
      const onPointerMove = (e) => {
        if (!dragging) return;
        pendingWidth = startWidth + e.clientX - startX;
        if (dragRafId) return;
        dragRafId = requestAnimationFrame(applyPendingDrag);
      };
      const endDrag = (e) => {
        if (!dragging) return;
        dragging = false;
        if (dragRafId) cancelAnimationFrame(dragRafId);
        // Flushes synchronously (not via rAF) so the collapse check below sees the pointer's true
        // final position even if the drag ends between frames.
        applyPendingDrag();
        splitter.classList.remove('dragging');
        shell.classList.remove('collapsing-list', 'collapsing-detail');
        document.body.style.cursor = '';
        if (pendingCollapse) collapse(pendingCollapse);
        pendingCollapse = null;
        persist();
        if (splitter.hasPointerCapture?.(e.pointerId)) splitter.releasePointerCapture(e.pointerId);
        splitter.removeEventListener('pointermove', onPointerMove);
        splitter.removeEventListener('pointerup', endDrag);
        splitter.removeEventListener('pointercancel', endDrag);
      };
      // Pointer Events (not mousedown/mousemove) so mouse, touch and pen all drive the same
      // handlers; pointer capture keeps the drag tracking even if the pointer leaves the 5px
      // splitter itself mid-gesture.
      splitter.addEventListener('pointerdown', (e) => {
        if (e.button != null && e.button !== 0) return;
        e.preventDefault();
        dragging = true;
        pendingCollapse = null;
        // Seed startWidth at the pane's current visual width (0 / full while collapsed) so the
        // pointer tracks it 1:1, but leave the collapsed state itself alone until the pointer
        // actually moves — applyPendingDrag's setWidth is what clears it. A press-and-release
        // with no travel must stay collapsed: every dblclick is preceded by two pointerdowns,
        // so un-collapsing here would make the dblclick handler's restore-to-remembered-width
        // branch unreachable (and persist a width that was never rendered).
        if (collapsed === 'list') {
          startWidth = 0;
        } else if (collapsed === 'detail') {
          const shellW = shell.getBoundingClientRect().width;
          const splitW = splitter.getBoundingClientRect().width || 5;
          startWidth = Math.max(MIN_WIDTH, shellW - splitW);
        } else {
          startWidth = currentWidth();
        }
        startX = e.clientX;
        splitter.setPointerCapture?.(e.pointerId);
        splitter.classList.add('dragging');
        document.body.style.cursor = 'col-resize';
        splitter.addEventListener('pointermove', onPointerMove);
        splitter.addEventListener('pointerup', endDrag);
        splitter.addEventListener('pointercancel', endDrag);
      });
      // Restores a collapsed pane, or resets a dragged width back to the CSS default, without
      // needing to find and drag the now-edge-hugging splitter precisely.
      splitter.addEventListener('dblclick', () => {
        if (collapsed) {
          setWidth(restoreFallback());
        } else {
          collapsed = null;
          applyCollapseClass();
          shell.style.removeProperty('--list-w');
          lastWidth = null;
          syncAria();
        }
        persist();
      });

      const STEP = 16;
      splitter.addEventListener('keydown', (e) => {
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return;
        e.preventDefault();
        if (e.key === 'Home') collapse('list');
        else if (e.key === 'End') collapse('detail');
        else if (collapsed) setWidth(restoreFallback()); // first Arrow press after a collapse just restores; it doesn't also step
        else if (e.key === 'ArrowLeft') setWidth(currentWidth() - STEP);
        else setWidth(currentWidth() + STEP);
        persist();
      });
    });
    syncSplitterAria = () => syncFns.forEach((sync) => sync());
    window.addEventListener('resize', syncSplitterAria);
  }

  /** `f` shortcut + each detail pane's expand/collapse button: toggles the shared zoom class that
   * hides the list pane and splitter of whichever `.split-shell` is currently visible. */
  function toggleDetailZoom() {
    document.body.classList.toggle('detail-zoom');
    renderCurrentDetailHeaders();
  }
  /** Re-renders just the header of whichever split view is active, so its expand button's
   * pressed/icon state stays in sync after `f` or the topbar toggles zoom. Cheap no-op guard for
   * views that haven't loaded a selection yet. */
  function renderCurrentDetailHeaders() {
    if (currentView === 'timeline') renderEventDetail();
    else if (currentView === 'network') renderNetworkDetail();
    else if (currentView === 'socket') renderSocketDetail();
    else if (currentView === 'push') renderPushDetail();
  }

  // ================================================================
  // Timeline
  // ================================================================
  function timelineMetrics(rows) {
    const warn = events.filter((e) => e.severity === 2).length;
    const err = events.filter((e) => e.severity === 3).length;
    const cells = [metricHtml('Events', String(events.length), '', 'ink'), metricHtml('Warnings', String(warn), '', 'warn'), metricHtml('Errors', String(err), '', 'error')];
    const stamps = events.map((e) => e.wallTimeMs).filter(Boolean);
    if (stamps.length > 1) {
      const spanSec = (Math.max(...stamps) - Math.min(...stamps)) / 1000;
      if (spanSec > 0) cells.push(metricHtml('Rate', (events.length / spanSec).toFixed(1), '/s', 'ink'));
    }
    $('timelineMetrics').innerHTML = cells.join('');
    $('timelineBadge').textContent = events.length + ' events';
  }

  function timelineChips(shownCount) {
    const chips = [];
    const q = $('search').value.trim();
    if (q) chips.push({ label: '"' + q + '"', title: 'Clear the search', on: () => { $('search').value = ''; render(); } });
    if (timelineSeverityFilter)
      chips.push({ label: 'level ' + (SEVERITY_SHORT[Number(timelineSeverityFilter)] || timelineSeverityFilter), title: 'Clear the level filter', on: () => { timelineSeverityFilter = ''; $('timelineSeveritySeg').querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.value === '')); render(); } });
    if (timelineSourceFilter)
      chips.push({ label: timelineSourceFilter, title: 'Clear the source filter', on: () => { timelineSourceFilter = ''; renderTimelineSourceSeg(); render(); } });
    if (timelineBookmarkedOnly) chips.push({ label: 'bookmarked only', title: 'Show every event again', on: () => { timelineBookmarkedOnly = false; updateBookmarkOnlyButton(); render(); } });
    appliedFiltersHtml(
      'timelineChips', chips,
      () => {
        $('search').value = '';
        timelineSeverityFilter = '';
        timelineSourceFilter = '';
        timelineBookmarkedOnly = false;
        $('timelineSeveritySeg').querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.value === ''));
        updateBookmarkOnlyButton();
        renderTimelineSourceSeg();
        render();
      },
      shownCount + ' of ' + events.length + ' shown ·',
    );
  }
  function updateBookmarkOnlyButton() {
    $('timelineBookmarkOnly').classList.toggle('primary', timelineBookmarkedOnly);
    $('timelineBookmarkOnly').setAttribute('aria-pressed', String(timelineBookmarkedOnly));
  }

  const TIMELINE_EMPTY_HTML = '<div class="list-empty">' + icon('activity', 'ic-lg') + '<div class="list-empty-title">No events match</div><div class="list-empty-sub">Clear a filter to see the other captured events.</div></div>';
  const render = () => {
    const q = $('search').value.toLowerCase();
    const rows = events.filter(
      (e) =>
        (!q || e.summary.toLowerCase().includes(q)) &&
        (!timelineSourceFilter || e.pluginId === timelineSourceFilter) &&
        (!timelineSeverityFilter || String(e.severity) === timelineSeverityFilter) &&
        (!timelineBookmarkedOnly || bookmarkedIds.has(e.id)),
    ).reverse(); // newest first
    timelineOrder = rows.map((e) => e.id);
    if (!selectedEventId || !timelineOrder.includes(selectedEventId)) selectedEventId = timelineOrder.length ? timelineOrder[0] : '';
    virtualList('events', { onNearEnd: loadMoreEventsIfPossible }).update(
      rows.length,
      (i, total) => {
        const e = rows[i];
        const lvl = SEVERITY_SHORT[e.severity] || 'INF';
        let mainText = e.summary;
        if (e.pluginId === 'network') {
          try {
            const urlMatch = e.summary.match(/(https?:\/\/[^\s]+)/);
            if (urlMatch) {
              const url = new URL(urlMatch[1]);
              mainText = e.summary.replace(urlMatch[1], url.pathname + url.search);
            }
          } catch (err) {}
        }
        return rowHtml({
          id: e.id, selected: selectedEventId === e.id,
          badgeText: lvl, badgeTone: levelTone(lvl),
          mainText: mainText, tagText: e.pluginId.toUpperCase(), tagTone: 'muted',
          duration: e.type, statusText: time(e.wallTimeMs).replace(/:\d\d /, ' ').slice(0, 8), sTone: 'muted',
          flagKind: 'timeline', flagLabel: mainText,
          posinset: i + 1, setsize: total,
        });
      },
      TIMELINE_EMPTY_HTML,
    );
    $('timelineFootLeft').textContent = rows.length + ' of ' + events.length + (q || timelineSourceFilter || timelineSeverityFilter || timelineBookmarkedOnly ? ' shown' : ' · newest first');
    $('timelineFootRight').textContent = paused ? 'paused' : 'tailing';
    timelineMetrics(rows);
    timelineChips(rows.length);
    renderEventDetail();
  };

  viewControllers.timeline = {
    containerId: 'events',
    order: () => timelineOrder,
    getSelected: () => selectedEventId,
    select: (id) => { selectedEventId = id; render(); },
    flagCurrent: () => { if (selectedEventId) toggleEvidenceFlag('timeline', selectedEventId, (events.find((e) => e.id === selectedEventId) || {}).summary || selectedEventId); },
  };

  function renderTimelineSourceSeg() {
    const sources = [...new Set(events.map((e) => e.pluginId))].sort();
    if (!sources.includes(timelineSourceFilter)) timelineSourceFilter = '';
    const counts = {};
    events.forEach((e) => { counts[e.pluginId] = (counts[e.pluginId] || 0) + 1; });
    $('timelineSourceSeg').innerHTML =
      `<button type="button" class="${timelineSourceFilter === '' ? 'active' : ''}" data-value="" aria-pressed="${timelineSourceFilter === ''}">All</button>` +
      sources.map((s) => `<button type="button" class="${timelineSourceFilter === s ? 'active' : ''}" data-value="${esc(s)}" aria-pressed="${timelineSourceFilter === s}">${esc(s)}<span style="margin-left:8px;color:var(--text-3);font-size:10.5px">${counts[s]}</span></button>`).join('');
  }
  const refreshSources = renderTimelineSourceSeg;

  // ================================================================
  // Event payload (message + stack trace for log/crash events) — TimelineLogSink and
  // CrashCapture (sdk/full) write this onto StoredEvent.payloadJson, but the plain
  // GET /api/v1/events list the timeline itself is built from deliberately omits it (same as the
  // live event.appended stream frame). The durable retained-capture store is the one place the
  // browser can still read it from — see server-ktor's `retainedJson()` / `/api/v1/retained-events`
  // — so this is a best-effort backfill: hosts that never wire retention (`retainedCaptures`) just
  // get an empty list back and the detail pane quietly stays at the summary-only view below.
  // ================================================================
  const PAYLOAD_KIND_PLUGIN_IDS = new Set(['logs', 'crash']);
  const eventPayloadsById = new Map();
  // Screenshot events ("screenshot" plugin) carry an attachmentId but no textual payload — the
  // plain GET /api/v1/events list omits attachmentId (same gap as everything else on this list;
  // see the module doc above), so this reads it off the same retained-events fetch used for
  // log/crash payloads rather than adding a second network round trip.
  const eventAttachmentIdsById = new Map();
  // Authoritative (7ef109d): retainedJson() now carries a live `redactionApplicability` sibling
  // for any row with an attachmentId ("APPLIED"/"NOT_APPLICABLE"/omitted-as-null when unknown) —
  // never inferred from pluginId here. Only meaningful when eventAttachmentIdsById also has the id.
  const eventRedactionApplicabilityById = new Map();
  // Negative cache: event ids renderEventDetail() has already scheduled exactly one
  // ensureEventPayloadsLoaded().then(rerender) for. Without this, an event whose payload is
  // permanently absent (host never wired retention, or it aged out of the retained store — both
  // expected, not exceptional) causes an infinite loop: once the 2s throttle window is active,
  // ensureEventPayloadsLoaded() returns an ALREADY-RESOLVED promise, so .then(renderEventDetail)
  // fires as a same-tick microtask; that render call finds the payload still missing and
  // schedules another .then(...), forever — an unbounded microtask chain that never yields to a
  // macrotask (setTimeout never fires) and hangs the tab. Capped defensively so a very long
  // session stepping through thousands of distinct events can't grow this unboundedly.
  const eventPayloadAttempted = new Set();
  const EVENT_PAYLOAD_ATTEMPTED_MAX = 2000;
  let eventPayloadsFetch = null;
  let eventPayloadsFetchedAt = 0;
  const EVENT_PAYLOAD_REFETCH_MIN_MS = 2000;
  // Set once the retained-events fetch has actually come back with at least one row, i.e. this
  // host does have retainedCaptures wired. `/api/v1/retained-events` is capped at 500 rows total
  // *across every plugin*, sorted by recency — a log-heavy session can crowd a real, in-window
  // event's payload out of that shared cache even though retention is working fine, so this flag
  // is what lets a "missing payload" message tell "this host never wired retention" (a true
  // negative) apart from "retention is wired, this row just wasn't in the last 500".
  // Crashes no longer depend on it: loadCrashes() fetches with pluginId=crash and reads each
  // row's own payloadJson, so a crash cannot be crowded out by unrelated plugins. Timeline still
  // reads through this cache and still needs the distinction.
  let retainedStoreConfirmedWired = false;
  /** Dedup'd, throttled fetch — stepping through many timeline rows shouldn't fire a request per
   * click. `force` bypasses the throttle for the one caller (a still-uncached selection) that
   * actually needs a fresh answer right now. */
  function ensureEventPayloadsLoaded(force) {
    if (eventPayloadsFetch) return eventPayloadsFetch;
    if (!token) return Promise.resolve();
    if (!force && Date.now() - eventPayloadsFetchedAt < EVENT_PAYLOAD_REFETCH_MIN_MS) return Promise.resolve();
    eventPayloadsFetchedAt = Date.now();
    eventPayloadsFetch = fetch('/api/v1/retained-events?limit=500', { headers: auth() })
      .then((r) => (r.ok ? r.json() : null))
      .then((body) => {
        const rows = body?.data || [];
        if (rows.length) retainedStoreConfirmedWired = true;
        rows.forEach((e) => {
          if (e.payloadJson) eventPayloadsById.set(e.id, e.payloadJson);
          if (e.attachmentId) {
            eventAttachmentIdsById.set(e.id, e.attachmentId);
            eventRedactionApplicabilityById.set(e.id, e.redactionApplicability ?? null);
          }
        });
      })
      .catch(() => { /* host doesn't wire retention, or a transient network error — stay summary-only */ })
      .finally(() => { eventPayloadsFetch = null; });
    return eventPayloadsFetch;
  }

  /** Detail pane for the selected timeline event: header/facts/actions, plain kvs (no tabs), and
   * a real note textarea wired to the existing bookmark/note endpoints. */
  function renderEventDetail() {
    const pane = $('eventDetail');
    const event = events.find((e) => e.id === selectedEventId);
    if (!event) {
      renderEmpty(pane, 'activity', 'No event selected', 'Select a timeline event from the list to inspect it.');
      return;
    }
    const lvl = SEVERITY_SHORT[event.severity] || 'INF';
    const zoom = document.body.classList.contains('detail-zoom');
    const query = eventDetailQuery;
    // Log/crash events carry a message + (for crashes and ANRs) a stack trace on
    // StoredEvent.payloadJson — see the module doc above ensureEventPayloadsLoaded(). Parsed
    // once here; message folds into the summary kv grid, stackTrace gets its own preformatted
    // block below since it's frequently dozens of lines.
    let payload = null;
    const isScreenshotEvent = event.pluginId === 'screenshot';
    if (PAYLOAD_KIND_PLUGIN_IDS.has(event.pluginId) || isScreenshotEvent) {
      const rawPayload = eventPayloadsById.get(event.id);
      if (rawPayload !== undefined) {
        try { payload = JSON.parse(rawPayload); } catch { payload = null; }
      } else if (!eventPayloadAttempted.has(event.id)) {
        // Exactly one scheduled re-check per event id — see eventPayloadAttempted above. If the
        // payload still isn't there when this fires, the render below just falls back to the
        // summary-only view; it will NOT schedule another attempt for this id.
        if (eventPayloadAttempted.size >= EVENT_PAYLOAD_ATTEMPTED_MAX) eventPayloadAttempted.clear();
        eventPayloadAttempted.add(event.id);
        // force: true — this selection is still uncached and the whole reason we're here, so the
        // 2s dedup throttle (meant to stop rapid list-stepping from firing a request per click)
        // must not swallow it. eventPayloadAttempted above is what actually bounds the recursion
        // (at most one scheduled re-check per event id, ever) — that bound is independent of
        // force and holds either way, so forcing this one call site is safe.
        ensureEventPayloadsLoaded(true).then(() => {
          if (selectedEventId === event.id) renderEventDetail();
        });
      }
    }
    const kvs = markKvHits(
      [
        { k: 'summary', v: event.summary },
        { k: 'level', v: lvl, tone: levelTone(lvl) },
        { k: 'pluginId', v: event.pluginId },
      ]
        .concat(event.correlationId ? [{ k: 'correlationId', v: event.correlationId }] : [])
        .concat(payload?.message && payload.message !== event.summary ? [{ k: 'message', v: payload.message }] : [])
        .map((r) => ({ k: r.k, v: String(r.v), tone: r.tone })),
      query,
    );
    const stackTraceHtml = payload?.stackTrace
      ? `<div class="block-title">Stack trace</div><pre class="detail-stacktrace">${esc(payload.stackTrace)}</pre>`
      : '';
    // UNREDACTED is authoritative now (7ef109d), read from the retained-events row's
    // `redactionApplicability` sibling field — never inferred from pluginId. attachmentId comes
    // from the same best-effort retained-events fetch as the crash/log payload above (see
    // eventAttachmentIdsById). The badge starts hidden (`hidden` attribute) or shown from that
    // field, then mountEvidenceThumbnails() below reconciles it against the live
    // X-DevConsole-Redaction-Applicability header once the image bytes are actually fetched — the
    // single most authoritative source, since it comes from the exact request that reads the row.
    const screenshotAttachmentId = isScreenshotEvent ? eventAttachmentIdsById.get(event.id) : null;
    const screenshotUnredacted = screenshotAttachmentId && eventRedactionApplicabilityById.get(event.id) === 'NOT_APPLICABLE';
    const screenshotHtml = isScreenshotEvent
      ? screenshotAttachmentId
        ? `<div class="block-title">Screenshot</div>
           <div class="evidence-thumbs"><div class="evidence-thumb-card"><span class="evidence-thumb-badge"${screenshotUnredacted ? '' : ' hidden'}>UNREDACTED</span>
             <button type="button" class="evidence-thumb-btn" data-action="open-screenshot" title="Open full size" aria-label="Open screenshot full size"><img data-thumb-for="${esc(screenshotAttachmentId)}" alt="${esc(event.summary)}"></button>
           </div></div>`
        : `<div class="block-title">Screenshot</div><p class="card-lede">Image unavailable — this host has not wired durable retention (retainedCaptures), or this event has aged out of the retained store.</p>`
      : '';
    const focusSnap = captureFocus(pane);
    pane.innerHTML = `<div class="detail-head">
        <div class="detail-head-row">
          <span class="detail-badge badge-${levelTone(lvl)}">${esc(lvl)}</span>
          <span class="detail-head-title">${esc(event.summary)}</span>
          <span class="detail-head-status tone-text-muted">${esc(event.pluginId)}</span>
          <button type="button" class="detail-expand${zoom ? ' active' : ''}" data-action="toggle-zoom" aria-pressed="${zoom}" title="${zoom ? 'Restore the split view (f)' : 'Expand this pane to full width (f)'}">${icon(zoom ? 'collapse' : 'expand', 'ic-sm')}</button>
        </div>
        <div class="detail-facts">
          <span class="detail-fact"><span class="detail-fact-k">time</span><span class="detail-fact-v">${time(event.wallTimeMs)}</span></span>
          <span class="detail-fact"><span class="detail-fact-k">type</span><span class="detail-fact-v">${esc(event.type)}</span></span>
          <span class="detail-fact"><span class="detail-fact-k">source</span><span class="detail-fact-v">${esc(event.pluginId)}</span></span>
          <span class="detail-fact"><span class="detail-fact-k">seq</span><span class="detail-fact-v">#${esc(event.sequence ?? '—')}</span></span>
        </div>
      </div>${findBarHtml(query, countPaneHits([{ groups: [{ kvs }] }]), 'eventDetailFindInput')}
      <div class="detail-body">
        ${screenshotHtml}
        ${kvGridHtml(kvs, true)}
        ${stackTraceHtml}
        <label class="detail-note"><span>Note — stored in the session export, never on a server</span><textarea id="eventNoteInput" rows="2" placeholder="Add a non-sensitive note…" ${token ? '' : 'disabled'}>${esc(event.note || '')}</textarea></label>
        <div class="detail-actions-row"><button type="button" id="eventNoteSaveBtn" ${token ? '' : 'disabled'} title="${token ? 'Save note' : 'Sign in required'}">${icon('check', 'ic-sm')}Save note</button></div>
      </div>`;
    restoreFocus(focusSnap, pane);
    // toggle-zoom is handled by the delegated listener wired once in wireEvents (`$('eventDetail')
    // .addEventListener('click', ...)`), not rebound here on every render.
    $('eventNoteSaveBtn').onclick = saveNote;
    if (screenshotAttachmentId) {
      mountEvidenceThumbnails(pane);
      pane.querySelector('[data-action="open-screenshot"]').onclick = () => openAttachmentFullSize(screenshotAttachmentId);
    }
  }

  // Client-side ceiling on the accumulated `events` array — was 500 (== the old per-request
  // limit), raised to 5,000 (a realistic full-session size) now that DOM
  // size is decoupled from array size by the Timeline virtualizer below. Applied both to live-tail
  // merges (mergeEvents) and to scroll-triggered "load more" (loadMoreEventsIfPossible) so neither
  // path can grow the in-memory array past what a long unattended session should reasonably hold.
  const EVENTS_CLIENT_CAP = 5000;
  const mergeEvents = (incoming) => {
    const merged = new Map(events.map((e) => [e.id, e]));
    incoming.forEach((raw) => {
      const e = { ...raw, wallTimeMs: raw.wallTimeMs ?? raw.timestampEpochMs ?? 0 };
      merged.set(e.id, e);
    });
    events = [...merged.values()]
      .sort((a, b) => (a.monotonicNanos ?? 0) - (b.monotonicNanos ?? 0) || (a.sequence ?? 0) - (b.sequence ?? 0) || String(a.id).localeCompare(String(b.id)))
      .slice(-EVENTS_CLIENT_CAP);
    refreshSources();
    render();
    setNavCount('navCountTimeline', events.length);
  };

  // Live-tail for Network/WebSockets/Push: Timeline already tails every event via
  // pendingLiveEvents above (unconditionally, regardless of the visible view); these three only
  // refresh the view that's actually on screen, debounced so a burst of frames/pushes/transactions
  // coalesces into one reload instead of thrashing the list, and queue (rather than refresh) while
  // paused -- mirroring Timeline's own queue/merge contract on the same `paused` toggle.
  const LIVE_TAIL_KIND_BY_PLUGIN = { network: 'network', websocket: 'socket', push: 'push' };
  // Network live-tail: skip refresh entirely when the operator paged into history (a cursor is
  // active) — yanking them back to page 1 loses selection and place. Fresh page = safe refresh.
  // Scroll is preserved around the reload for all three list bodies.
  const LIVE_TAIL_LOADERS = {
    network: () => { if (!networkCursorActive()) liveTailPreservingScroll('transactions', loadNetwork); },
    socket: () => liveTailPreservingScroll('sockets', liveTailRefreshSockets),
    push: () => liveTailPreservingScroll('pushEvents', loadPush),
  };
  function networkCursorActive() { return networkPagedIntoHistory; }
  async function liveTailPreservingScroll(listId, loader) {
    const el = $(listId);
    const top = el ? el.scrollTop : 0;
    await loader();
    const after = $(listId);
    if (after) after.scrollTop = top;
  }
  const liveTailDirty = { network: false, socket: false, push: false };
  const liveTailTimers = {};
  const LIVE_TAIL_DEBOUNCE_MS = 750;
  // Trailing debounce alone starves under sustained traffic (every event pushes the timer out);
  // the max-wait guarantees a refresh at least this often while events keep arriving.
  const LIVE_TAIL_MAX_WAIT_MS = 2500;
  const liveTailFirstScheduled = {};

  /** Frames sort newest-first (`socketSelectedIndex` is a plain array index into that list), so a
   * plain loadSockets() would silently reselect whatever now sits at the old index once a live
   * frame prepends. Re-locates the previously selected frame by its (connection, direction,
   * timestamp) identity after reloading so it stays selected instead of swapping under the user. */
  async function liveTailRefreshSockets() {
    const prev = socketSelectedIndex >= 0 ? socketMessages_[socketSelectedIndex] : null;
    await loadSockets();
    if (!prev) return;
    const idx = socketMessages_.findIndex((m) => m.connectionId === prev.connectionId && m.direction === prev.direction && m.timestampEpochMs === prev.timestampEpochMs);
    if (idx >= 0 && idx !== socketSelectedIndex) { socketSelectedIndex = idx; renderSockets(); }
  }
  function scheduleLiveTailRefresh(kind) {
    if (paused) { liveTailDirty[kind] = true; clearTimeout(liveTailTimers[kind]); liveTailFirstScheduled[kind] = 0; return; }
    if (currentView !== kind) return;
    const now = Date.now();
    if (!liveTailFirstScheduled[kind]) liveTailFirstScheduled[kind] = now;
    const elapsed = now - liveTailFirstScheduled[kind];
    const delay = Math.max(0, Math.min(LIVE_TAIL_DEBOUNCE_MS, LIVE_TAIL_MAX_WAIT_MS - elapsed));
    clearTimeout(liveTailTimers[kind]);
    liveTailTimers[kind] = setTimeout(() => {
      liveTailFirstScheduled[kind] = 0;
      if (currentView === kind) LIVE_TAIL_LOADERS[kind]();
    }, delay);
  }
  /** Mirrors setPaused's pendingLiveEvents flush below: resuming replays whatever arrived while
   * paused, once, for each live-tailed view that's currently visible. */
  function flushLiveTailRefreshes() {
    Object.keys(liveTailDirty).forEach((kind) => {
      if (liveTailDirty[kind]) {
        liveTailDirty[kind] = false;
        if (currentView === kind) LIVE_TAIL_LOADERS[kind]();
      }
    });
  }

  // Timeline live-tail batching: each WS frame used to call mergeEvents([value.event]) synchronously
  // (full Map rebuild + sort + slice(-EVENTS_CLIENT_CAP) + render(), the latter running two full-array
  // filters) -- one such pass per message, unconditionally, regardless of which view is on screen.
  // Buffers arrivals instead and coalesces them through the same debounce/max-wait pair
  // Network/Sockets/Push already use above (LIVE_TAIL_DEBOUNCE_MS / LIVE_TAIL_MAX_WAIT_MS), so a
  // burst of events settles into one merge instead of one per frame. Runs independent of
  // `currentView`/`scheduleLiveTailRefresh` since Timeline live-tail stays live regardless of which
  // view is visible (see the pendingLiveEvents comment above).
  let pendingTimelineMergeEvents = [];
  let timelineMergeTimer = null;
  let timelineMergeFirstScheduled = 0;
  function scheduleTimelineMerge() {
    const now = Date.now();
    if (!timelineMergeFirstScheduled) timelineMergeFirstScheduled = now;
    const elapsed = now - timelineMergeFirstScheduled;
    const delay = Math.max(0, Math.min(LIVE_TAIL_DEBOUNCE_MS, LIVE_TAIL_MAX_WAIT_MS - elapsed));
    clearTimeout(timelineMergeTimer);
    timelineMergeTimer = setTimeout(flushTimelineMerge, delay);
  }
  /** Fires once the debounce settles. Re-checks `paused` here (rather than only at buffer-time)
   * because a pause can land after events were already queued but before this timer fired -- when
   * that happens they belong in pendingLiveEvents, to be replayed by setPaused on resume like every
   * other paused arrival, not merged into `events` while the view is supposed to be frozen. */
  function flushTimelineMerge() {
    timelineMergeFirstScheduled = 0;
    if (!pendingTimelineMergeEvents.length) return;
    const queued = pendingTimelineMergeEvents;
    pendingTimelineMergeEvents = [];
    if (paused) {
      const merged = new Map(pendingLiveEvents.map((e) => [e.id, e]));
      queued.forEach((e) => merged.set(e.id, e));
      pendingLiveEvents = [...merged.values()].slice(-500);
      $('status').textContent = `LIVE / PAUSED (${pendingLiveEvents.length} pending)`;
    } else {
      mergeEvents(queued);
    }
  }

  function updateRecordButton() {
    const live = $('streamLive');
    const stopped = $('streamPaused');
    if (!live || !stopped) return;
    live.classList.toggle('active', !paused);
    live.setAttribute('aria-pressed', String(!paused));
    stopped.classList.toggle('active', paused);
    stopped.setAttribute('aria-pressed', String(paused));
    // Note: the session-pill dot (#lamp) intentionally reflects real WebSocket connectivity
    // (set in connectStream below), not the record/pause toggle — a paused-but-connected
    // stream is a more useful signal than a simplified live/paused color mapping.
  }

  /** Switches the live-tail state; used by the topbar LIVE/PAUSED segment and the `p` shortcut. */
  async function setPaused(next) {
    if (paused === next) return;
    paused = next;
    updateRecordButton();
    if (!paused) {
      await load();
      if (pendingLiveEvents.length) {
        const queued = pendingLiveEvents;
        pendingLiveEvents = [];
        mergeEvents(queued);
      }
      flushLiveTailRefreshes();
    }
    $('status').textContent = paused ? 'LIVE / PAUSED' : 'LIVE / TAILING';
  }

  function noteSequenceSeen(seq) {
    if (typeof seq === 'number' && seq > lastKnownSequence) lastKnownSequence = seq;
  }
  /** Fired only on a *reconnect* whose server.welcome reports a currentSequence past what this
   * browser last saw (see connectStream) -- events were appended to the Timeline while the socket
   * was down, and the WS frames that would have delivered them are gone for good, so this pulls a
   * fresh page instead of leaving a silent gap. Timeline always re-syncs via load(); Network/
   * Sockets/Push reuse their own live-tail loaders (which already respect "paged into history",
   * scroll position, etc.) but only for whichever of them is actually on screen. */
  function reconcileStreamGap() {
    load();
    const backfill = LIVE_TAIL_LOADERS[currentView];
    if (backfill) backfill();
  }
  function connectStream() {
    if (!token || stream?.readyState === WebSocket.OPEN || stream?.readyState === WebSocket.CONNECTING) return;
    clearTimeout(streamRetry);
    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
    stream = new WebSocket(`${scheme}//${location.host}/api/v1/stream`);
    stream.onopen = () => stream.send('{"type":"client.hello","protocolVersion":1}');
    stream.onmessage = (message) => {
      let value;
      try {
        value = JSON.parse(message.data);
      } catch {
        return;
      }
      if (value.type === 'server.welcome') {
        $('lamp').classList.remove('expired');
        $('lamp').classList.add('live');
        $('status').textContent = paused ? 'LIVE / PAUSED' : 'LIVE / TAILING';
        // lastKnownSequence starts at -1, so this only fires on a genuine reconnect (one that
        // already saw at least one sequence number before now) -- a fresh first connect has
        // nothing to backfill and every view loads its own initial data anyway.
        if (typeof value.currentSequence === 'number' && lastKnownSequence >= 0 && value.currentSequence > lastKnownSequence) {
          reconcileStreamGap();
        }
        return;
      }
      if (value.type !== 'event.appended' || !value.event) return;
      noteSequenceSeen(value.event.sequence);
      if (paused) {
        const queued = new Map(pendingLiveEvents.map((e) => [e.id, e]));
        queued.set(value.event.id, value.event);
        pendingLiveEvents = [...queued.values()].slice(-500);
        $('status').textContent = `LIVE / PAUSED (${pendingLiveEvents.length} pending)`;
      } else {
        pendingTimelineMergeEvents.push(value.event);
        scheduleTimelineMerge();
      }
      const liveTailKind = LIVE_TAIL_KIND_BY_PLUGIN[value.event.pluginId];
      if (liveTailKind) scheduleLiveTailRefresh(liveTailKind);
    };
    stream.onclose = (event) => {
      $('lamp').classList.remove('live');
      // A queued merge flush must not fire after the socket that queued it is gone -- but the
      // frames it buffered were already delivered by that socket, so flush them synchronously now
      // instead of discarding: reconnect never re-fetches, and the buffered batch is exactly the
      // one that matters when the app process dies right after emitting a crash event.
      clearTimeout(timelineMergeTimer);
      timelineMergeTimer = null;
      flushTimelineMerge();
      // 1008 (VIOLATED_POLICY) + AUTH_REQUIRED/AUTH_REVOKED means the server itself has given up on
      // this session -- blindly retrying every 1.5s would just collect the same close forever, so
      // this is routed to the same dead-session state repeated 401s reach (handleSessionExpired)
      // instead of the ordinary reconnect loop below. ORIGIN_REJECTED shares the 1008 code but is a
      // config problem, not an expired session, so it deliberately falls through to that loop.
      if (event.code === 1008 && (event.reason === 'AUTH_REQUIRED' || event.reason === 'AUTH_REVOKED')) {
        handleSessionExpired();
        return;
      }
      if (token) {
        $('status').textContent = 'RECONNECTING LIVE STREAM';
        streamRetry = setTimeout(connectStream, 1500);
      }
    };
  }

  // ================================================================
  // Session expiry
  // ================================================================
  // The session rides its server-side TTL (30 minutes); the dashboard does NOT proactively rotate
  // the token. Rotating in place would invalidate the already-open /api/v1/stream socket (it
  // re-checks the original token server-side every ~100ms), closing it 1008/AUTH_REVOKED — i.e.
  // auto-refresh destroyed the very session it renewed. Instead, expiry is handled gracefully below.
  /** Reached from two independent signals that the session died server-side: a WS close carrying
   * AUTH_REQUIRED/AUTH_REVOKED (connectStream's onclose) and repeated 401s from ordinary
   * authenticated requests (the fetch wrapper near hasSession). Stops every retry loop instead of
   * spinning forever and leaves a state the operator can actually act on. */
  function handleSessionExpired() {
    if (sessionExpired) return;
    sessionExpired = true;
    consecutiveAuthFailures = 0;
    clearTimeout(streamRetry);
    token = '';
    csrf = '';
    stream?.close();
    stream = null;
    $('lamp').classList.remove('live');
    $('lamp').classList.add('expired');
    setStatus('Session expired', "Session expired — get a fresh connect code from the device's More screen");
    toast('Session expired — get a fresh connect code from the device.', 'error');
    updateControlUi();
  }

  // Raised from the historical limit=100 to the server's own TimelineQuery.MAX_PAGE_LIMIT (500) —
  // the highest a single request can return; going further means more requests, which
  // loadMoreEventsIfPossible below issues as the operator scrolls toward the end of the list
  // instead of clicking "Older" repeatedly. Safe now that virtualization has decoupled DOM size
  // from fetched-row count.
  const TIMELINE_FETCH_LIMIT = 500;
  let timelineLoadingMore = false;
  async function load(cursor = null) {
    if (!token) return;
    const params = new URLSearchParams({ limit: String(TIMELINE_FETCH_LIMIT), sort: 'DESC' });
    if (cursor) params.set('cursor', cursor);
    const r = await fetch('/api/v1/events?' + params, { headers: auth() });
    if (!r.ok) return;
    const body = await r.json();
    events = (body.data || [])
      .map((raw) => ({ ...raw, wallTimeMs: raw.wallTimeMs ?? raw.timestampEpochMs ?? 0 }))
      .sort((a, b) => (a.monotonicNanos ?? 0) - (b.monotonicNanos ?? 0) || (a.sequence ?? 0) - (b.sequence ?? 0) || String(a.id).localeCompare(String(b.id)));
    events.forEach((e) => noteSequenceSeen(e.sequence));
    timelineCursor = body.page?.nextCursor || null;
    $('timelineOlder').disabled = !body.page?.hasMore;
    paused = Boolean(cursor) || paused;
    updateRecordButton();
    refreshSources();
    render();
    setNavCount('navCountTimeline', events.length);
    $('status').textContent = paused ? 'LIVE / PAUSED' : 'LIVE / TAILING';
    // Best-effort priming so a log/crash event's message + stack trace (see
    // ensureEventPayloadsLoaded above) is usually already cached by the time something is
    // clicked; renderEventDetail() re-renders itself once this resolves if it's still relevant.
    ensureEventPayloadsLoaded().then(() => { if (selectedEventId) renderEventDetail(); });
  }

  /** Infinite-scroll companion to the Older/Newest pager: fired by the Timeline virtualizer when
   * the operator scrolls near the bottom (oldest end) of what's currently loaded. Merges the next
   * cursor page into `events` (rather than replacing it, which is what the explicit Older button
   * still does) so scrolling keeps revealing older history without a click — Older/Newest are left
   * exactly as they were, a manual "jump to a fresh page" escape hatch that also pauses live-tail,
   * which a background scroll-triggered load should not do on its own. */
  async function loadMoreEventsIfPossible() {
    if (!token || !timelineCursor || timelineLoadingMore || events.length >= EVENTS_CLIENT_CAP) return;
    timelineLoadingMore = true;
    try {
      const params = new URLSearchParams({ limit: String(TIMELINE_FETCH_LIMIT), sort: 'DESC', cursor: timelineCursor });
      const r = await fetch('/api/v1/events?' + params, { headers: auth() });
      if (!r.ok) return;
      const body = await r.json();
      const merged = new Map(events.map((e) => [e.id, e]));
      (body.data || []).forEach((raw) => merged.set(raw.id, { ...raw, wallTimeMs: raw.wallTimeMs ?? raw.timestampEpochMs ?? 0 }));
      events = [...merged.values()].sort((a, b) => (a.monotonicNanos ?? 0) - (b.monotonicNanos ?? 0) || (a.sequence ?? 0) - (b.sequence ?? 0) || String(a.id).localeCompare(String(b.id)));
      events.forEach((e) => noteSequenceSeen(e.sequence));
      timelineCursor = body.page?.nextCursor || null;
      $('timelineOlder').disabled = !body.page?.hasMore;
      refreshSources();
      render();
      setNavCount('navCountTimeline', events.length);
    } finally {
      timelineLoadingMore = false;
    }
  }

  // ================================================================
  // Overview
  // ================================================================
  function percentile(sortedAsc, p) {
    if (!sortedAsc.length) return null;
    return sortedAsc[Math.min(sortedAsc.length - 1, Math.floor(p * (sortedAsc.length - 1)))];
  }
  /** Overview owns a small independent sample of network transactions + events (not the
   * Network/Timeline views' shared state) so it renders correctly whether or not those views have
   * been visited yet — same "each view loads its own data" convention every other view follows. */
  async function loadOverview() {
    $('overviewBadge').textContent = hasSession() ? 'connected' : 'not connected';
    if (!hasSession()) {
      $('overviewMetrics').innerHTML = '';
      renderOverview(null, {}, [], [], null);
      $('overviewCrashBanner').hidden = true;
      return;
    }
    const [overviewRes, metaRes, networkRes, eventsRes, runsRes] = await Promise.all([
      fetch('/api/v1/overview', { headers: auth() }),
      fetch('/api/v1/meta', { headers: auth() }),
      fetch('/api/v1/network/transactions?limit=100', { headers: auth() }),
      fetch('/api/v1/events?limit=6&sort=DESC', { headers: auth() }),
      // The "previous-run-crashed banner": GET /api/v1/runs reports every
      // retained run's StoredSessionStatus, sorted newest-first by the server. The most recent
      // *non-active* run is the "previous run".
      fetch('/api/v1/runs', { headers: auth() }),
    ]);
    if (!overviewRes.ok) return;
    const meta = metaRes.ok ? await metaRes.json() : {};
    // Fail-open when captureCategories is missing (older SDK) or malformed -- see
    // applyCaptureCategoryGating's own null-means-unknown handling.
    applyCaptureCategoryGating(meta.captureCategories);
    const netSample = networkRes.ok ? (await networkRes.json()).data || [] : [];
    const eventSample = eventsRes.ok ? (await eventsRes.json()).data || [] : [];
    const runs = runsRes.ok ? (await runsRes.json()).data || [] : [];
    const previousRun = runs.find((run) => run.status !== 'ACTIVE') || null;
    renderOverview(await overviewRes.json(), meta, netSample, eventSample, previousRun);
  }
  /** Keeps the session-pill's visible label and its hover tooltip (the full text, for when the
   * pill has ellipsised at a narrow width — see the topbar breakpoint ladder in dashboard.css) in
   * lock-step, so a stale "open the device's More screen" tooltip never lingers once connected. */
  function setStatus(text, title) {
    const el = $('status');
    if (!el) return;
    el.textContent = text;
    el.title = title || text;
  }
  async function exchangeSessionCode(code, source) {
    if (!code) return;
    setStatus('Connecting…');
    const r = await fetch('/api/v1/auth/session-code/exchange', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'code=' + encodeURIComponent(code) + '&browserLabel=' + encodeURIComponent(navigator.userAgent.slice(0, 96)),
    });
    if (!r.ok) {
      setStatus(
        r.status === 401 ? 'Code expired or invalid' : 'Code rejected',
        (r.status === 401 ? 'Code expired or invalid' : 'Code rejected') + " — get a fresh one from the device's More screen (codes are single-use and expire after five minutes)",
      );
      if (source === 'manual') toast('Session code rejected.', 'error');
      // Codes are single-use, so a code that just failed exchange is burned either way — strip it
      // from the URL on the auto-connect path same as the success branch below does, or a refresh
      // just resubmits the same dead code and repeats this failure forever.
      if (source !== 'manual') history.replaceState(null, '', location.pathname);
      return;
    }
    const b = await r.json();
    token = b.accessToken;
    csrf = b.csrfToken;
    sessionExpired = false;
    consecutiveAuthFailures = 0;
    if (source !== 'manual') history.replaceState(null, '', location.pathname);
    setStatus('Connected');
    toast('Connected to DevConsole session.');
    updateControlUi();
    connectStream();
    // `paused` starts true because there is nothing to tail before a session exists. Connecting is
    // the moment that stops being true, and a console labelled LIVE / TAILING that quietly tails
    // nothing until you notice the toggle is worse than one that never offered to. Paging into
    // history still re-pauses (see the cursor check in the timeline load).
    setPaused(false);
    loadOverview();
    // Prime the mocks/composer capability flags so "Mock this response"/"Resend" aren't dead
    // until Mocks/Composer are visited.
    loadMockRules();
    loadComposerHostAllowlist();
    refreshHostLine();
    // Prime the evidence cache immediately on connect (not lazily on first Evidence-tray visit)
    // so every row's flag button and the rail count are already correct across every view.
    loadEvidenceFlags();
  }
  /** The previous-run-crashed banner, driven by GET /api/v1/runs — `previousRun` is
   * already "the most recent non-active run" by the time it gets here (see loadOverview). Shown
   * only when that run's real StoredSessionStatus is CRASHED; a COMPLETED (or any other) previous
   * run shows nothing. Now that crashes are addressable per run (GET /api/v1/retained-events?
   * pluginId=crash carries sessionId — see the Crashes module doc), the click handler below jumps
   * straight to that run's crash via focusCrashesOnRun() instead of just opening the Crashes view
   * in general, which is all a plain `show('crashes')` used to be able to do. */
  function renderOverviewCrashBanner(previousRun) {
    const el = $('overviewCrashBanner');
    if (!el) return;
    if (!previousRun || previousRun.status !== 'CRASHED') { el.hidden = true; el.innerHTML = ''; return; }
    el.hidden = false;
    const when = previousRun.endedAtEpochMs || previousRun.startedAtEpochMs;
    const app = [previousRun.applicationId, previousRun.appVersionName].filter(Boolean).join(' ');
    el.innerHTML = `<button type="button" class="gate-banner gate-banner-btn" id="overviewCrashBannerBtn">${icon('alert', 'ic-sm')}<div><div class="gate-banner-title">The previous run crashed</div><div class="gate-banner-body">${
      app ? esc(app) + ' — ' : ''
    }${esc(time(when))}${previousRun.deviceModel ? ' · ' + esc(previousRun.deviceModel) : ''}. Open Crashes for the breadcrumb trail and full dump.</div></div><span class="gate-banner-code">VIEW</span></button>`;
    $('overviewCrashBannerBtn').onclick = () => focusCrashesOnRun(previousRun.id);
  }
  function renderOverview(body, meta, netSample, eventSample, previousRun) {
    renderOverviewCrashBanner(previousRun);
    if (!body) {
      cardsGridHtml('overviewCards', [
        {
          icon: 'lock', iconTone: 'warn', title: 'Connect this browser', span: 2,
          // fieldsHtml (unlike `lede`) is inserted raw, not HTML-escaped — the only field on this
          // card that can carry <code>/<strong> markup (device path, the
          // adb forward command, and the #code= credential note) ahead of the actual connect
          // input, since cardHtml() always renders fieldsHtml before its own buttons/lede order
          // isn't ours to change (it's shared by every card-grid view, not scoped to Overview).
          fieldsHtml: `
            <p class="card-lede">This session is unauthenticated. On the device, open DevConsole → <strong>More</strong> — it shows the connect QR and an 8-character, single-use code that expires after five minutes. Scan the QR, or paste the code below.</p>
            <ul class="connect-steps">
              <li>The <code class="inline-code">#code=</code> fragment in that link is the credential — a bare <code class="inline-code">http://host:port/</code> address alone stays unauthenticated forever.</li>
              <li>Emulator or firewalled device? Forward the port first: <code class="inline-code">adb forward tcp:8080 tcp:8080</code> — 8080 is only the first port tried; the server takes the next free one up to 8099, so confirm the exact address on the device's More screen.</li>
              <li>Logcat deliberately never prints the code — read it from the device screen, not the log.</li>
            </ul>
            <div class="card-fields mt-12"><label class="field"><span>Session code</span><input id="connectCode" placeholder="e.g. 7K2QF9" maxlength="12" autocomplete="off"></label></div>`,
          buttons: [{ id: 'connect', label: 'Connect', icon: 'plug', kind: 'primary' }],
        },
      ]);
      wireCardGrid('overviewCards', { onButton: (id) => { if (id === 'connect') exchangeSessionCode($('connectCode').value.trim(), 'manual'); } });
      return;
    }
    const durations = netSample.map((t) => t.durationMs).filter((d) => d != null).sort((a, b) => a - b);
    const p95 = percentile(durations, 0.95);
    const failCount = netSample.filter(failing).length;
    $('overviewMetrics').innerHTML = metricsStripHtml([
      { label: 'Requests', val: String(netSample.length), sub: 'sample', tone: 'ink' },
      { label: 'Failing', val: String(failCount), sub: netSample.length ? Math.round((failCount / netSample.length) * 100) + '%' : '', tone: 'error' },
      { label: 'p95', val: p95 != null ? (p95 / 1000).toFixed(2) : '—', sub: p95 != null ? 's' : '', tone: 'warn' },
      { label: 'Flagged', val: String(evidenceFlags.size), sub: 'as evidence', tone: 'signal' },
    ]);
    cardsGridHtml('overviewCards', [
      overviewNeedsAttentionCard(netSample, failCount),
      overviewTrafficHealthCard(netSample, durations, p95, failCount),
      overviewServerCard(body, meta),
      overviewSignalsCard(eventSample),
      overviewHealthCard(body.sdkHealth),
    ]);
    wireCardGrid('overviewCards', {
      onButton: (id) => {
        if (id === 'open-failures') { networkFailOnly = true; show('network'); loadNetwork(); }
        else if (id === 'flag-all') {
          netSample.filter(failing).forEach((t) => { if (!isFlagged('network', t.id)) toggleEvidenceFlag('network', t.id, t.method + ' ' + t.path); });
          toast(failCount + ' capture' + (failCount === 1 ? '' : 's') + ' flagged as evidence');
          loadOverview();
        }
      },
    });
  }
  function overviewNeedsAttentionCard(netSample, failCount) {
    const bad = netSample.filter(failing).slice(0, 8);
    return {
      icon: 'alert', iconTone: failCount ? 'error' : 'muted', title: 'Needs attention', span: 2,
      badge: failCount + ' failing', badgeTone: failCount ? 'error' : 'muted',
      lede: failCount
        ? 'Everything a tester would open first: the calls that failed in the last ' + netSample.length + ' captured. Flag any of them to build a bug report from real captures.'
        : 'No failing or errored requests in the last ' + netSample.length + ' captured.',
      rows: bad.map((t) => ({
        k: t.method + ' ' + t.path.split('?')[0], v: !t.status ? t.error || 'Request failed' : t.status + ' · ' + (t.durationMs ?? '?') + ' ms',
        tone: statusTone(t.status), tag: isFlagged('network', t.id) ? 'FLAGGED' : false, tagTone: 'signal',
      })),
      buttons: failCount
        ? [
            { id: 'open-failures', label: 'Open failures in Network', icon: 'network', kind: 'primary' },
            { id: 'flag-all', label: 'Flag all failing', icon: 'flag' },
          ]
        : [],
    };
  }
  function overviewTrafficHealthCard(netSample, durations, p95, failCount) {
    const total = netSample.length;
    const buckets = { 2: 0, 3: 0, 4: 0, 5: 0 };
    let failed = 0;
    let mocked = 0;
    netSample.forEach((t) => {
      if (!t.status) failed++;
      else if (buckets[Math.floor(t.status / 100)] !== undefined) buckets[Math.floor(t.status / 100)]++;
      if (t.tags && t.tags.mocked === 'true') mocked++;
    });
    const stack = !total
      ? []
      : [
          { label: '2xx', val: String(buckets[2]), pct: ((buckets[2] / total) * 100).toFixed(1), tone: 'signal' },
          { label: '3xx', val: String(buckets[3]), pct: ((buckets[3] / total) * 100).toFixed(1), tone: 'put' },
          { label: '4xx', val: String(buckets[4]), pct: ((buckets[4] / total) * 100).toFixed(1), tone: 'warn' },
          { label: '5xx', val: String(buckets[5]), pct: ((buckets[5] / total) * 100).toFixed(1), tone: 'error' },
          { label: 'failed', val: String(failed), pct: ((failed / total) * 100).toFixed(1), tone: 'muted' },
        ].filter((s) => Number(s.val) > 0);
    const p50 = percentile(durations, 0.5);
    return {
      icon: 'network', iconTone: 'signal', title: 'Traffic health', badge: total ? 'last ' + total : 'no data', badgeTone: 'muted',
      lede: total ? false : 'No network transactions captured yet.',
      stack,
      metrics: [
        { label: 'p50 latency', val: p50 != null ? p50 + ' ms' : '—', tone: 'ink' },
        { label: 'p95 latency', val: p95 != null ? p95 + ' ms' : '—', tone: 'warn' },
        { label: 'failure rate', val: total ? Math.round((failCount / total) * 100) + '%' : '—', tone: 'error' },
        { label: 'mocked', val: String(mocked), tone: 'put' },
      ],
    };
  }
  function overviewServerCard(body, meta) {
    const endpoint = meta.endpoint;
    return {
      icon: 'grid', iconTone: 'signal', title: 'Server & app',
      rows: [
        { k: 'Package', v: body.app?.packageName || '—', tone: 'ink' },
        { k: 'Version', v: body.app?.versionName || '—', tone: 'ink' },
        { k: 'Bound to', v: endpoint ? endpoint.host + ':' + endpoint.port : location.host, tone: 'ink', tag: endpoint?.bindingMode === 'LAN' ? 'LAN' : false, tagTone: 'warn' },
        { k: 'Protocol', v: meta.protocolVersion ? 'devconsole/' + meta.protocolVersion : '—', tone: 'muted' },
        { k: 'Build', v: meta.build?.variant || '—', tone: 'muted' },
        { k: 'Mocks', v: body.mocks?.enabled ? body.mocks.ruleCount + ' rule' + (body.mocks.ruleCount === 1 ? '' : 's') + ' active' : 'disabled', tone: body.mocks?.enabled ? 'put' : 'muted' },
        body.sessionIntegrity ? { k: 'Session', v: body.sessionIntegrity.pristine ? 'pristine' : 'modified', tone: body.sessionIntegrity.pristine ? 'signal' : 'warn', tag: body.sessionIntegrity.pristine ? false : 'MODIFIED', tagTone: 'warn' } : false,
      ].filter(Boolean),
    };
  }
  function overviewSignalsCard(eventSample) {
    return {
      icon: 'activity', iconTone: 'signal', title: 'Latest signals', badge: eventSample.length ? 'last ' + eventSample.length : false, badgeTone: 'muted',
      lede: eventSample.length ? false : 'No timeline events captured yet.',
      rows: eventSample.map((e) => {
        const lvl = SEVERITY_SHORT[e.severity] || 'INF';
        return { k: time(e.wallTimeMs).slice(0, 8), v: e.summary, tone: 'muted', tag: lvl, tagTone: levelTone(lvl) };
      }),
    };
  }
  function overviewHealthCard(sdkHealth) {
    return {
      icon: 'health', iconTone: 'signal', title: 'SDK health',
      metrics: sdkHealth
        ? [
            { label: 'State', val: sdkHealth.state || 'UNKNOWN', tone: sdkHealth.state === 'RUNNING' ? 'signal' : 'muted' },
            { label: 'Published', val: String(sdkHealth.publishedEventCount ?? 0), tone: 'ink' },
            { label: 'Dropped', val: String(sdkHealth.droppedEventCount ?? 0), tone: sdkHealth.droppedEventCount > 0 ? 'warn' : 'ink' },
            { label: 'Principals', val: String(sdkHealth.activePrincipalCount ?? 0), tone: 'ink' },
          ]
        : [],
      lede: sdkHealth ? false : 'SDK health is unavailable.',
    };
  }

  // ================================================================
  // Network
  // ================================================================
  function wireSeg(container, onSelect) {
    // Initial state: segmented controls are toggle groups, so each button carries aria-pressed —
    // set once from whichever button already has `.active` in the static markup.
    container.querySelectorAll('button').forEach((b) => b.setAttribute('aria-pressed', String(b.classList.contains('active'))));
    container.addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-value]');
      if (!btn) return;
      container.querySelectorAll('button').forEach((b) => {
        b.classList.toggle('active', b === btn);
        b.setAttribute('aria-pressed', String(b === btn));
      });
      onSelect(btn.dataset.value);
    });
  }
  const failing = (t) => !t.status || t.status >= 400;

  /** Fetches the broadest page for the server-side filters (search/time/path/content-type/
   * duration/error/correlation/tag) — Status, Method and Service are applied client-side below so
   * those controls can show live facet counts without extra round-trips. */
  let networkPagedIntoHistory = false;
  // Raised from the historical limit=100 to the server's own NetworkTransactionQuery.MAX_PAGE_LIMIT
  // (500) — the highest a single request can return; going further means more requests, which
  // loadMoreNetworkIfPossible below issues as the operator scrolls toward the end of the list
  // instead of clicking "Older" repeatedly. Safe now that virtualization has decoupled DOM size
  // from fetched-row count.
  const NETWORK_FETCH_LIMIT = 500;
  // Client-side ceiling on how much scroll-triggered "load more" will accumulate in `networkPage`
  // for one session — a sanity backstop, not a UX target (5,000 matches this feature's acceptance
  // scenario). Explicit Older/Newest pager clicks always work regardless of this cap; only the
  // automatic near-end fetch stops once it's reached.
  const NETWORK_CLIENT_CAP = 5000;
  let networkLoadingMore = false;

  /** Shared by loadNetwork and loadMoreNetworkIfPossible so a scroll-triggered "load more"
   * request always respects exactly the filters the operator has applied — never a subtly
   * different set. */
  function networkFilterEntries() {
    return [
      ['query', 'networkSearch'],
      ['from', 'networkFrom'],
      ['to', 'networkTo'],
      ['method', 'networkMethod'],
      ['statusFrom', 'networkStatusFrom'],
      ['statusTo', 'networkStatusTo'],
      ['host', 'networkHost'],
      ['path', 'networkPath'],
      ['contentType', 'networkContentType'],
      ['minDurationMs', 'networkMinDuration'],
      ['maxDurationMs', 'networkMaxDuration'],
      ['error', 'networkError'],
      ['correlationId', 'networkCorrelation'],
      ['tag', 'networkTag'],
    ]
      .map(([name, id]) => [name, $(id).value.trim()])
      .filter(([, value]) => value);
  }
  async function loadNetwork(cursor = null) {
    if (!token) return;
    networkPagedIntoHistory = Boolean(cursor);
    const params = new URLSearchParams({ limit: String(NETWORK_FETCH_LIMIT) });
    if (cursor) params.set('cursor', cursor);
    networkFilterEntries().forEach(([name, value]) => params.append(name, value));
    const r = await fetch('/api/v1/network/transactions?' + params, { headers: auth() });
    if (!r.ok) {
      // Never clobber the detail pane (a background live-tail refresh hitting a 429 would wipe
      // the transaction the operator is reading, permanently). Surface on the transient channel.
      toast(r.status === 429 ? 'Refresh rate-limited — retrying shortly.' : 'Network filter rejected: ' + r.status, 'error');
      return;
    }
    const body = await r.json();
    networkPage = body.data || [];
    networkCursor = body.page?.nextCursor || null;
    $('networkOlder').disabled = !body.page?.hasMore;
    if (networkHostFilter.size) {
      // Drop hosts from the selection that no longer appear on this page.
      [...networkHostFilter].forEach((h) => { if (!networkPage.some((t) => t.host === h)) networkHostFilter.delete(h); });
    }
    if (networkSelectedIds.size) {
      // Same pruning rule as networkHostFilter above: an exported-selection checkbox can only ever
      // refer to a row the operator can currently see evidence of.
      [...networkSelectedIds].forEach((id) => { if (!networkPage.some((t) => t.id === id)) networkSelectedIds.delete(id); });
    }
    setNavCount('navCountNetwork', networkPage.length);
    applyNetworkFilters();
  }

  /** Infinite-scroll companion to the Older/Newest pager: fired by the Network virtualizer when
   * the operator scrolls near the bottom of what's currently loaded. Appends the next cursor page
   * onto `networkPage` (rather than replacing it, which is what the explicit Older button still
   * does) so scrolling keeps revealing older history without a click. Older/Newest are left
   * exactly as they were — a manual "jump to a fresh page" escape hatch — because rebuilding them
   * onto an accumulate-and-append model would touch the facet counts, the export-selection
   * pruning, and "select all matching filter", all of which reason about `networkPage` as one
   * coherent page today; appending underneath them is the smaller, less disruptive change. */
  async function loadMoreNetworkIfPossible() {
    if (!token || !networkCursor || networkLoadingMore || networkPage.length >= NETWORK_CLIENT_CAP) return;
    networkLoadingMore = true;
    try {
      const params = new URLSearchParams({ limit: String(NETWORK_FETCH_LIMIT), cursor: networkCursor });
      networkFilterEntries().forEach(([name, value]) => params.append(name, value));
      const r = await fetch('/api/v1/network/transactions?' + params, { headers: auth() });
      if (!r.ok) return;
      const body = await r.json();
      const seen = new Set(networkPage.map((t) => t.id));
      (body.data || []).forEach((t) => { if (!seen.has(t.id)) networkPage.push(t); });
      networkCursor = body.page?.nextCursor || null;
      $('networkOlder').disabled = !body.page?.hasMore;
      setNavCount('navCountNetwork', networkPage.length);
      applyNetworkFilters();
    } finally {
      networkLoadingMore = false;
    }
  }

  // Zero-config category chips: a capability derived entirely client-side from already-loaded
  // transactions, not a server filter — no new endpoint, matching every other client-only facet
  // here. Media extensions win regardless of position; otherwise the first meaningful path segment
  // wins, skipping version/`api` prefixes; `/graphql` groups by operation name when the path
  // carries one (`/graphql/<name>`), else just "graphql".
  const NETWORK_CATEGORY_MEDIA_EXT = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'ico', 'bmp', 'mp4', 'mov', 'webm', 'mp3', 'wav', 'ogg', 'woff', 'woff2', 'ttf', 'otf', 'eot']);
  /** Applies Status/Method/Service/Failures-only client-side over the broad `networkPage`,
   * recomputing `transactions` (the visible subset), then re-renders everything downstream. */
  function applyNetworkFilters() {
    transactions = networkPage.filter((t) => {
      if (networkFailOnly && !failing(t)) return false;
      if (networkMethodFilter && t.method !== networkMethodFilter) return false;
      if (networkHostFilter.size && !networkHostFilter.has(t.host)) return false;
      if (networkStatusFilter) {
        if (networkStatusFilter === 'fail') { if (t.status) return false; }
        else if (String(t.status || '')[0] !== networkStatusFilter[0]) return false;
      }
      return true;
    });
    renderNetwork();
  }

  /** Facet counts respect every *other* active dimension, so picking a facet shows how many rows
   * it would add rather than a static total. */
  function networkFacetBase(excludeDims) {
    return networkPage.filter((t) => {
      if (!excludeDims.includes('failOnly') && networkFailOnly && !failing(t)) return false;
      if (!excludeDims.includes('method') && networkMethodFilter && t.method !== networkMethodFilter) return false;
      if (!excludeDims.includes('host') && networkHostFilter.size && !networkHostFilter.has(t.host)) return false;
      if (!excludeDims.includes('status') && networkStatusFilter) {
        if (networkStatusFilter === 'fail') { if (t.status) return false; }
        else if (String(t.status || '')[0] !== networkStatusFilter[0]) return false;
      }
      return true;
    });
  }
  function updateNetworkFacetCounts() {
    const statusCounts = { '2xx': 0, '3xx': 0, '4xx': 0, '5xx': 0, fail: 0 };
    networkFacetBase(['status']).forEach((t) => {
      if (!t.status) statusCounts.fail++;
      else { const key = String(t.status)[0] + 'xx'; if (statusCounts[key] !== undefined) statusCounts[key]++; }
    });
    $('networkStatusSeg').querySelectorAll('button[data-value]').forEach((b) => {
      const v = b.dataset.value;
      const label = v === '' ? 'All' : v;
      const count = statusCounts[v];
      b.innerHTML = esc(label) + (count ? `<span class="seg-count">${count}</span>` : '');
    });
    const methodCounts = {};
    networkFacetBase(['method']).forEach((t) => { methodCounts[t.method] = (methodCounts[t.method] || 0) + 1; });
    $('networkMethodSeg').querySelectorAll('button[data-value]').forEach((b) => {
      const v = b.dataset.value;
      const label = v === '' ? 'ALL' : v === 'DELETE' ? 'DEL' : v;
      const count = v && methodCounts[v];
      b.innerHTML = esc(label) + (count ? `<span class="seg-count">${count}</span>` : '');
    });
  }

  let networkOrder = [];
  const NETWORK_EMPTY_HTML = '<div class="list-empty">' + icon('funnel', 'ic-lg') + '<div class="list-empty-title">No transactions match</div><div class="list-empty-sub">Clear a filter or widen the status/method selection to see the other captures.</div></div>';
  function renderNetwork() {
    $('networkMainColLabel').textContent = 'Path';
    // Normalize the selection BEFORE painting, like the other four list views: rows gate tabindex
    // on `selected`, so painting with a stale id would leave zero tabbable rows until
    // showTransaction()'s async re-render -- or forever, if that detail fetch rejects.
    const networkSelectionStale = !selectedTransactionId || !transactions.some((t) => t.id === selectedTransactionId);
    if (networkSelectionStale && transactions.length) selectedTransactionId = transactions[0].id;
    virtualList('transactions', { onNearEnd: loadMoreNetworkIfPossible }).update(
      transactions.length,
      (i, total) => {
        const t = transactions[i];
        return rowHtml({
          id: t.id, selected: selectedTransactionId === t.id,
          checkbox: true, checked: networkSelectedIds.has(t.id),
          badgeText: t.method, badgeTone: methodTone(t.method),
          mainText: t.path,
          // Single row-tag slot: a pinned diff baseline always wins over the mocked marker.
          tagText: networkPinnedId === t.id ? 'BASE' : t.tags?.mocked === 'true' ? 'MOCK' : false,
          tagTone: 'put',
          duration: t.durationMs != null ? t.durationMs + ' ms' : t.error ? 'failed' : '—',
          statusText: t.status != null ? String(t.status) : t.error ? 'ERR' : '—', sTone: statusTone(t.status),
          flagKind: 'network', flagLabel: t.method + ' ' + t.path,
          posinset: i + 1, setsize: total,
        });
      },
      NETWORK_EMPTY_HTML,
    );
    networkOrder = transactions.map((t) => t.id);
    $('networkFootLeft').textContent = transactions.length + ' of ' + networkPage.length + ' · newest first';
    $('networkFootRight').textContent = networkHostFilter.size ? networkHostFilter.size + ' service' + (networkHostFilter.size === 1 ? '' : 's') + ' selected' : 'all services';
    $('networkBadge').textContent = networkPage.length + ' captured';
    updateNetworkFacetCounts();
    renderNetworkMetrics();
    renderNetworkChips();
    renderNetworkHostDrop();
    renderNetworkSelectionBar();
    if (networkSelectionStale) {
      if (transactions.length) showTransaction(selectedTransactionId);
      else renderEmpty($('networkDetailPane'), 'network', 'No transaction selected', 'Select a transaction from the list to inspect its redacted metadata.');
    }
  }

  // ----------------------------------------------------------------
  // Network export selection (checkboxes + HAR/Postman for the selection or everything)
  // ----------------------------------------------------------------
  function toggleNetworkSelection(id) {
    if (networkSelectedIds.has(id)) networkSelectedIds.delete(id);
    else networkSelectedIds.add(id);
    renderNetwork();
  }
  function clearNetworkSelection() {
    if (!networkSelectedIds.size) return;
    networkSelectedIds.clear();
    renderNetwork();
  }
  /** Header checkbox: selects/deselects every row currently visible (i.e. `transactions`, the
   * already-filtered subset), matching the browser convention that a "select all" checkbox in a
   * table header only ever reaches the rows on screen. */
  function toggleSelectAllVisibleNetwork() {
    const allVisibleSelected = transactions.length > 0 && transactions.every((t) => networkSelectedIds.has(t.id));
    if (allVisibleSelected) transactions.forEach((t) => networkSelectedIds.delete(t.id));
    else transactions.forEach((t) => networkSelectedIds.add(t.id));
    renderNetwork();
  }
  /**
   * Widens the selection from "every row currently loaded" to "every transaction matching the
   * current filter", up to the server's MAX_PAGE_LIMIT (500) — the same bound and the same
   * `X-DevConsole-Export-Truncated` signal the export routes themselves use, so a selection that
   * hits the bound is reported the same way an export that hits it would be.
   *
   * Status "fail" (no status/timeout) and the failures-only quick filter have no server-side query
   * param equivalent (see networkTransactionQuery on the server: it filters by exact status codes
   * or a statusFrom/statusTo range, never "status is absent"), so those two cases fall back to
   * selecting exactly the rows already loaded and visible client-side instead of round-tripping —
   * correct, just bounded to what's currently paged in, and called out in the toast.
   */
  async function selectAllMatchingFilterNetwork() {
    if (!token) return;
    if (networkStatusFilter === 'fail' || networkFailOnly) {
      transactions.forEach((t) => networkSelectedIds.add(t.id));
      renderNetwork();
      toast('Selected ' + transactions.length + ' loaded — "fail" status has no server-side filter equivalent, so this can\'t widen past what\'s already loaded.');
      return;
    }
    const params = new URLSearchParams({ limit: String(500) });
    [
      ['query', 'networkSearch'],
      ['from', 'networkFrom'],
      ['to', 'networkTo'],
      ['path', 'networkPath'],
      ['contentType', 'networkContentType'],
      ['minDurationMs', 'networkMinDuration'],
      ['maxDurationMs', 'networkMaxDuration'],
      ['error', 'networkError'],
      ['correlationId', 'networkCorrelation'],
      ['tag', 'networkTag'],
    ].forEach(([name, id]) => {
      const value = $(id).value.trim();
      if (value) params.append(name, value);
    });
    if (networkMethodFilter) params.append('method', networkMethodFilter);
    networkHostFilter.forEach((h) => params.append('host', h));
    if (networkStatusFilter) {
      const from = Number(networkStatusFilter[0]) * 100;
      params.append('statusFrom', String(from));
      params.append('statusTo', String(from + 99));
    }
    const r = await fetch('/api/v1/network/transactions?' + params, { headers: auth() });
    if (!r.ok) { toast('Select-all-matching-filter failed: ' + r.status, 'error'); return; }
    const body = await r.json();
    (body.data || []).forEach((t) => networkSelectedIds.add(t.id));
    renderNetwork();
    toast(
      'Selected ' + (body.data || []).length + ' matching transaction(s)' +
        (body.page?.hasMore ? ' — more than 500 matched; only the first 500 were selected.' : '.'),
    );
  }
  function renderNetworkSelectionBar() {
    const bar = $('networkSelectionBar');
    if (!bar) return;
    const count = networkSelectedIds.size;
    const allVisibleSelected = transactions.length > 0 && transactions.every((t) => networkSelectedIds.has(t.id));
    const headCheck = $('networkSelectAllVisible');
    if (headCheck) {
      headCheck.setAttribute('aria-checked', String(allVisibleSelected));
      headCheck.innerHTML = allVisibleSelected ? icon('check', 'ic-sm') : '';
      headCheck.title = allVisibleSelected ? 'Deselect all visible' : 'Select all visible';
    }
    const toolbarHar = $('networkHarDownload');
    const toolbarPostman = $('networkPostmanDownload');
    const scopeTitle = count ? `Export the ${count} selected transaction(s)` : 'Export every captured transaction (up to 500)';
    if (toolbarHar) toolbarHar.title = scopeTitle;
    if (toolbarPostman) toolbarPostman.title = scopeTitle;
    bar.hidden = count === 0;
    if (count === 0) return;
    bar.innerHTML = `<span class="selection-count">${count} selected</span>
      <button type="button" data-sel-action="select-all-filtered">Select all matching filter</button>
      <span class="selection-spacer"></span>
      <button type="button" data-sel-action="export-har">${icon('download', 'ic-sm')}HAR</button>
      <button type="button" data-sel-action="export-postman">${icon('download', 'ic-sm')}Postman</button>
      <button type="button" data-sel-action="clear">Clear</button>`;
  }

  function renderNetworkMetrics() {
    const failCount = networkPage.filter(failing).length;
    const c4 = networkPage.filter((t) => t.status >= 400 && t.status < 500).length;
    const c5 = networkPage.filter((t) => t.status >= 500).length;
    const cErr = networkPage.filter((t) => !t.status).length;
    const flaggedCount = [...evidenceFlags.values()].filter((v) => v.kind === 'network').length;
    $('networkMetrics').innerHTML = [
      metricHtml('Shown', String(transactions.length), 'of ' + networkPage.length, 'ink'),
      metricHtml('Failing', String(failCount), c4 + ' 4xx, ' + c5 + ' 5xx, ' + cErr + ' failed', 'error'),
      metricHtml('Flagged', String(flaggedCount), 'as evidence', 'signal'),
    ].join('');
  }

  function renderNetworkChips() {
    const chips = [];
    const q = $('networkSearch').value.trim();
    if (q) chips.push({ label: '"' + q + '"', title: 'Clear the search', on: () => { $('networkSearch').value = ''; loadNetwork(); } });
    if (networkStatusFilter) chips.push({ label: 'status ' + (networkStatusFilter === 'fail' ? 'failed' : networkStatusFilter), title: 'Clear the status filter', on: () => { networkStatusFilter = ''; $('networkStatusSeg').querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.value === '')); applyNetworkFilters(); } });
    if (networkMethodFilter) chips.push({ label: 'method ' + networkMethodFilter, title: 'Clear the method filter', on: () => { networkMethodFilter = ''; $('networkMethodSeg').querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.value === '')); applyNetworkFilters(); } });
    networkHostFilter.forEach((h) => chips.push({ label: h, title: 'Remove ' + h, on: () => { networkHostFilter.delete(h); applyNetworkFilters(); } }));
    if (networkFailOnly) chips.push({ label: 'failures only', title: 'Show all statuses again', on: () => { networkFailOnly = false; applyNetworkFilters(); } });
    appliedFiltersHtml(
      'networkChips', chips,
      () => {
        $('networkSearch').value = '';
        networkStatusFilter = ''; networkMethodFilter = ''; networkHostFilter.clear(); networkFailOnly = false;
        $('networkStatusSeg').querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.value === ''));
        $('networkMethodSeg').querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.value === ''));
        loadNetwork();
      },
      transactions.length + ' of ' + networkPage.length + ' shown ·',
    );
  }

  viewControllers.network = {
    containerId: 'transactions',
    order: () => networkOrder,
    getSelected: () => selectedTransactionId,
    select: (id) => showTransaction(id),
    flagCurrent: () => { const t = transactions.find((x) => x.id === selectedTransactionId); if (t) toggleEvidenceFlag('network', t.id, t.method + ' ' + t.host + t.path); },
    pinCurrent: () => pinNetworkBaseline(selectedTransactionId),
  };

  /** Generic labeled multi-select dropdown (the `.drop*` classes): a button
   * with a live selection summary + count pill, and a fixed-position checkbox panel with a
   * Select-all/Clear footer. `items` is `[{ value, label, count, dot }]`; `selected` a Set. */
  function dropdownHtml({ btnId, label, items, selected, open, dotForItem }) {
    const summary = !selected.size ? 'All ' + label.toLowerCase() + 's' : selected.size === 1 ? [...selected][0] : selected.size + ' ' + label.toLowerCase() + 's selected';
    // aria-controls only while the panel exists in the DOM -- emitted unconditionally it would be
    // a dangling IDREF in the (default) closed state, which axe flags under aria-valid-attr-value.
    return `<button type="button" class="drop-btn${selected.size ? ' has-selection' : ''}" id="${btnId}" aria-haspopup="true"${open ? ` aria-controls="${btnId}Panel"` : ''} aria-expanded="${open}" title="Choose which ${esc(label.toLowerCase())}s to show">
        <span class="drop-summary">${esc(summary)}</span>
        ${selected.size ? `<span class="drop-count">${selected.size}</span>` : ''}
        <span class="ic-chevron">${icon('chevron')}</span>
      </button>
      ${
        open
          ? `<div class="drop-panel" id="${btnId}Panel" role="group" aria-label="${esc(label)}">
        <div class="drop-list">${items
          .map((it) => {
            const on = selected.has(it.value);
            return `<button type="button" class="drop-item" role="checkbox" aria-checked="${on}" data-drop-value="${esc(it.value)}">
              <span class="drop-box">${on ? icon('check') : ''}</span>
              ${dotForItem ? `<span class="drop-dot" style="background:${dotForItem(it)}"></span>` : ''}
              <span class="drop-item-label"${it.rtl ? ' style="direction:rtl;text-align:left"' : ''}>${esc(it.label)}</span>
              <span class="drop-item-count">${esc(it.count ?? '')}</span>
            </button>`;
          })
          .join('')}</div>
        <div class="drop-footer"><button type="button" data-drop-action="select-all">${label === 'Connection' ? 'Open only' : 'Select all'}</button><button type="button" data-drop-action="clear">Clear</button></div>
      </div>`
          : ''
      }`;
  }
  function positionDropPanel(btnId) {
    requestAnimationFrame(() => {
      const btn = $(btnId), panel = $(btnId + 'Panel');
      if (!btn || !panel) return;
      const rc = btn.getBoundingClientRect();
      const vh = window.innerHeight;
      const below = vh - rc.bottom - 16;
      const useBelow = below >= 180 || below >= rc.top - 16;
      panel.style.left = Math.max(8, rc.left) + 'px';
      if (useBelow) { panel.style.top = rc.bottom + 5 + 'px'; panel.style.bottom = 'auto'; }
      else { panel.style.bottom = vh - rc.top + 5 + 'px'; panel.style.top = 'auto'; }
    });
  }
  /** Every click inside a `.drop*` panel re-renders its container (toggling a checkbox, hitting
   * Select-all/Clear, or the toggle button itself), which detaches `e.target` from the live tree
   * before this document-level listener runs. `closest()` on a detached node always misses, so
   * this uses `composedPath()` — the propagation path captured at dispatch time — instead, or
   * every one of those in-panel clicks would be misread as "outside" and immediately re-close
   * the dropdown it just opened. */
  function closeDropdownsOnOutsideClick(e) {
    const path = e.composedPath ? e.composedPath() : [];
    const inside = (id) => path.some((el) => el.id === id);
    if (networkHostDropOpen && !inside('networkHostDrop')) { networkHostDropOpen = false; renderNetworkHostDrop(); }
    if (socketConnDropOpen && !inside('socketConnDrop')) { socketConnDropOpen = false; renderSocketConnDrop(); }
  }

  function renderNetworkHostDrop() {
    const hosts = [...new Set(networkPage.map((t) => t.host))].sort();
    const counts = {};
    networkFacetBase(['host']).forEach((t) => { counts[t.host] = (counts[t.host] || 0) + 1; });
    const items = hosts.map((h) => ({ value: h, label: h, count: counts[h] || 0 }));
    const hostDropEl = $('networkHostDrop');
    const focusSnap = captureFocus(hostDropEl);
    hostDropEl.innerHTML = dropdownHtml({ btnId: 'networkHostDropBtn', label: 'Service', items, selected: networkHostFilter, open: networkHostDropOpen });
    restoreFocus(focusSnap, hostDropEl);
    $('networkHostDropBtn').onclick = () => { networkHostDropOpen = !networkHostDropOpen; renderNetworkHostDrop(); if (networkHostDropOpen) positionDropPanel('networkHostDropBtn'); };
    const panel = $('networkHostDropBtnPanel');
    if (!panel) return;
    panel.addEventListener('click', (e) => {
      const item = e.target.closest('[data-drop-value]');
      if (item) { const v = item.dataset.dropValue; networkHostFilter.has(v) ? networkHostFilter.delete(v) : networkHostFilter.add(v); applyNetworkFilters(); renderNetworkHostDrop(); positionDropPanel('networkHostDropBtn'); return; }
      const action = e.target.closest('[data-drop-action]');
      if (!action) return;
      if (action.dataset.dropAction === 'select-all') hosts.forEach((h) => networkHostFilter.add(h));
      else networkHostFilter.clear();
      applyNetworkFilters(); renderNetworkHostDrop(); positionDropPanel('networkHostDropBtn');
    });
  }

  // ================================================================
  // WebSockets
  // ================================================================
  function listEmptyHtml(iconName, title, sub) {
    return `<div class="list-empty">${icon(iconName, 'ic-lg')}<div class="list-empty-title">${esc(title)}</div><div class="list-empty-sub">${esc(sub)}</div></div>`;
  }
  function shortSocketUrl(url) {
    return (url || '').replace(/^wss?:\/\/[^/]+/, '') || '/';
  }
  // Real messages carry no `id` and no byte-size field for TEXT frames: this keeps a real,
  // honestly-labeled size (character count for text, real bytes for binary) instead of
  // inventing one.
  function frameSizeValue(m) {
    return m.payload?.kind === 'binary' ? m.payload.length : (m.payload?.preview || '').length;
  }
  function frameSizeLabel(m) {
    if (m.payload?.kind === 'binary') { const n = m.payload.length; return n < 1024 ? n + ' B' : (n / 1024).toFixed(1) + ' kB'; }
    return frameSizeValue(m) + ' chars' + (m.payload?.truncated ? '+' : '');
  }
  function framePreviewText(m) {
    if (m.frameType === 'PING' || m.frameType === 'PONG') return m.frameType + ' (control frame)';
    if (m.payload?.kind === 'binary') return m.payload.preview ? m.payload.previewEncoding + ': ' + m.payload.preview : 'Binary preview withheld — host did not opt in.';
    const preview = m.payload?.preview ?? '';
    if (m.textFormat === 'JSON') { try { return JSON.stringify(JSON.parse(preview)); } catch { /* fall through */ } }
    return preview;
  }
  /** Parses JSON text-frame previews in place so downstream code (the detail pane's code block)
   * can syntax-color them; kept under this name because a DashboardAssetsTest checks for it as
   * evidence the socket-message pipeline avoids the browser-only deep-clone API. */
  const formatSocketMessages = (messages) =>
    messages.map((message) => {
      const copy = { ...message, payload: message.payload ? { ...message.payload } : message.payload };
      if (copy.textFormat === 'JSON' && copy.payload?.kind === 'text') {
        try {
          copy.payload.parsed = JSON.parse(copy.payload.preview);
        } catch {
          /* leave preview as text */
        }
      }
      return copy;
    });

  async function loadSockets() {
    if (!token) return;
    const connParams = new URLSearchParams();
    if (socketProtocolFilter !== 'all') connParams.append('protocol', socketProtocolFilter);
    const r = await fetch('/api/v1/websockets/connections?' + connParams, { headers: auth() });
    if (!r.ok) return;
    socketConnections = (await r.json()).data || [];
    [...socketSelectedConnIds].forEach((id) => { if (!socketConnections.some((s) => s.id === id)) socketSelectedConnIds.delete(id); });
    setNavCount('navCountSockets', socketConnections.length);
    await loadSocketMessages();
  }

  /** Broad fetch respecting every server-side filter (connections, frame type, direction, search,
   * time, error) — List/Timeline mode is purely a client-side presentation choice on top. */
  async function loadSocketMessages() {
    if (!token) return;
    const params = new URLSearchParams();
    socketSelectedConnIds.forEach((id) => params.append('connectionId', id));
    if (socketDirectionFilter) params.append('direction', socketDirectionFilter);
    if (socketFrameTypeFilter) params.append('frameType', socketFrameTypeFilter);
    if (socketProtocolFilter !== 'all') params.append('protocol', socketProtocolFilter);
    [['query', 'socketSearch'], ['from', 'socketFrom'], ['to', 'socketTo'], ['error', 'socketError']].forEach(([name, id]) => {
      const value = $(id).value.trim();
      if (value) params.append(name, value);
    });
    const r = await fetch('/api/v1/websockets/messages?' + params, { headers: auth() });
    if (!r.ok) { renderEmpty($('socketMessages'), 'alert', 'Invalid WebSocket filter combination.'); return; }
    socketMessages_ = formatSocketMessages((await r.json()).data || []);
    if (socketSelectedIndex >= socketMessages_.length) socketSelectedIndex = -1;
    renderSockets();
  }

  let socketOrder = [];
  const SOCKETS_EMPTY_HTML = listEmptyHtml('sockets', 'No frames match', 'Clear a filter to see the other captured frames.');
  function renderSockets() {
    const timelineMode = socketMode === 'timeline';
    $('socketListHead').innerHTML = timelineMode
      ? '<span style="flex:0 0 74px;text-align:right">Time</span><span style="flex:0 0 20px"></span><span style="flex:1">Frame · payload size</span>'
      : '<span class="col-badge">Dir</span><span class="col-main">Payload preview</span><span class="col-duration">Size</span><span class="col-status">Time</span><span class="col-flag"></span>';
    // Normalize the selection before painting (not after) so a stale/dropped selectedIndex never
    // leaves every row tabindex=-1 for a frame — both sub-modes below read socketSelectedIndex.
    if (socketSelectedIndex < 0 || !socketMessages_[socketSelectedIndex]) socketSelectedIndex = socketMessages_.length ? 0 : -1;
    // One virtualizer instance serves both sub-modes (they share the #sockets container); the row
    // height var it reads switches with the mode (--d-trace-h vs --d-row-h, both scaled by
    // body.mode-simple/advanced), so a List↔Timeline toggle just changes what `update()` supplies.
    const socketsVirt = virtualList('sockets', { rowHeightVar: () => (socketMode === 'timeline' ? '--d-trace-h' : '--d-row-h') });
    if (timelineMode) {
      const chrono = socketMessages_.map((m, i) => ({ m, i })).sort((a, b) => a.m.timestampEpochMs - b.m.timestampEpochMs);
      const maxBytes = Math.max(1, ...chrono.map((x) => frameSizeValue(x.m)));
      socketsVirt.update(
        chrono.length,
        (n, total) => {
          const x = chrono[n];
          const m = x.m, prev = n ? chrono[n - 1].m : null, rec = m.direction === 'RECEIVED';
          const gapMs = prev ? m.timestampEpochMs - prev.timestampEpochMs : 0;
          const sel = socketSelectedIndex === x.i;
          const pct = Math.max(4, Math.round((frameSizeValue(m) / maxBytes) * 100));
          // Protocol badge only for MQTT (see renderSocketConnDrop) — folded as a text prefix on
          // the existing label span rather than a new CSS-styled badge element this row layout
          // doesn't otherwise have room for.
          const conn = socketConnections.find((s) => s.id === m.connectionId);
          const protoPrefix = conn?.protocol === 'mqtt' ? 'MQTT · ' : '';
          return `<div class="trace-row${sel ? ' selected' : ''}" data-row-id="${x.i}" tabindex="${sel ? '0' : '-1'}" role="option" aria-selected="${sel}" aria-posinset="${n + 1}" aria-setsize="${total}" title="${esc(m.direction + ' ' + m.frameType + ' at ' + time(m.timestampEpochMs))}">

                <span class="trace-time"><span class="trace-t">${time(m.timestampEpochMs)}</span><span class="trace-gap${gapMs > 4000 ? ' warn' : ''}">${prev ? '+' + (gapMs < 1000 ? gapMs + 'ms' : (gapMs / 1000).toFixed(2) + 's') : 'start'}</span></span>
                <span class="trace-line"><span class="trace-dot tone-bg-${rec ? 'put' : 'signal'}">${icon(rec ? 'arrow-down' : 'arrow-up')}</span></span>
                <span class="trace-main"><span class="trace-line1"><span class="trace-dir tone-text-${rec ? 'put' : 'signal'}">${esc(m.direction)}</span><span class="trace-label">${esc(protoPrefix + framePreviewText(m))}</span><span class="trace-sz">${esc(frameSizeLabel(m))}</span></span><span class="trace-bar tone-bg-${m.frameType === 'BINARY' ? 'strong' : rec ? 'put' : 'signal'}" style="width:${pct}%"></span></span>
              </div>`;
        },
        SOCKETS_EMPTY_HTML,
      );
      socketOrder = chrono.map((x) => String(x.i));
    } else {
      socketsVirt.update(
        socketMessages_.length,
        (i, total) => {
          const m = socketMessages_[i];
          const rec = m.direction === 'RECEIVED';
          const conn = socketConnections.find((s) => s.id === m.connectionId);
          // Protocol badge only for MQTT (see renderSocketConnDrop) — folded into the row's single
          // tag slot alongside whatever it already shows (the BIN marker, or the connection URL
          // when more than one connection is in scope), rather than reusing dashboard.css for a
          // second badge slot this row layout doesn't have.
          const protoTag = conn?.protocol === 'mqtt' ? 'MQTT' : null;
          const scopeTag = socketSelectedConnIds.size === 1 ? (m.frameType === 'BINARY' ? 'BIN' : null) : conn ? shortSocketUrl(conn.url) : null;
          return rowHtml({
            id: String(i), selected: socketSelectedIndex === i,
            badgeText: rec ? 'RECV' : 'SENT', badgeTone: rec ? 'put' : 'signal',
            mainText: framePreviewText(m),
            tagText: [protoTag, scopeTag].filter(Boolean).join(' · ') || false,
            tagTone: 'put',
            duration: frameSizeLabel(m), statusText: time(m.timestampEpochMs), sTone: 'muted',
            flagKind: 'socket', flagId: socketFlagId(i, m), flagLabel: m.direction + ' ' + m.frameType,
            posinset: i + 1, setsize: total,
          });
        },
        SOCKETS_EMPTY_HTML,
      );
      socketOrder = socketMessages_.map((_, i) => String(i));
    }
    // "Shown of total" like the other three views: shown is the current (server-filtered)
    // socketMessages_ fetch; total is every frame in the current connection scope (all
    // connections, or just the selected ones) before the frameType/direction/search/time/error
    // filters narrow it down — sentCount/receivedCount are per-connection running totals, not
    // affected by those filters.
    const socketConnScope = socketSelectedConnIds.size
      ? socketConnections.filter((s) => socketSelectedConnIds.has(s.id))
      : socketConnections;
    const socketTotalFrames = socketConnScope.reduce((sum, s) => sum + s.sentCount + s.receivedCount, 0);
    $('socketFootLeft').textContent = timelineMode
      ? socketMessages_.length + ' frames · oldest first'
      : socketMessages_.length + ' of ' + socketTotalFrames + ' frames';
    $('socketFootRight').textContent = timelineMode
      ? 'bar width ∝ payload size'
      : socketSelectedConnIds.size === 1
        ? (() => { const s = socketConnections.find((x) => socketSelectedConnIds.has(x.id)); return s ? s.state + ' · ' + s.sentCount + '↑ ' + s.receivedCount + '↓' : ''; })()
        : socketSelectedConnIds.size ? socketSelectedConnIds.size + ' connections' : socketConnections.length + ' connections';
    renderSocketMetrics();
    renderSocketChips();
    renderSocketConnDrop();
    renderSocketDetail();
  }

  function renderSocketMetrics() {
    const openCount = socketConnections.filter((s) => s.state === 'OPEN').length;
    const errCount = socketConnections.filter((s) => s.state === 'FAILED').length;
    const sent = socketConnections.reduce((a, s) => a + s.sentCount, 0);
    const recv = socketConnections.reduce((a, s) => a + s.receivedCount, 0);
    let busiest = null;
    socketConnections.forEach((s) => { const total = s.sentCount + s.receivedCount; if (!busiest || total > busiest.total) busiest = { total, s }; });
    const cells = [
      metricHtml('Connections', String(socketConnections.length), openCount + ' open' + (errCount ? ' · ' + errCount + ' error' : ''), 'ink'),
      metricHtml('Frames sent', String(sent), '', 'signal'),
      metricHtml('Frames recv', String(recv), '', 'put'),
    ];
    if (busiest) cells.push(metricHtml('Busiest', String(busiest.total), shortSocketUrl(busiest.s.url), 'warn'));
    $('socketMetrics').innerHTML = cells.join('');
    $('socketBadge').textContent = openCount + ' open';
  }

  function renderSocketChips() {
    const chips = [];
    const q = $('socketSearch').value.trim();
    if (q) chips.push({ label: '"' + q + '"', title: 'Clear the search', on: () => { $('socketSearch').value = ''; loadSocketMessages(); } });
    if (socketFrameTypeFilter) chips.push({ label: socketFrameTypeFilter, title: 'Clear the frame-type filter', on: () => { socketFrameTypeFilter = ''; $('socketFrameTypeSeg').querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.value === '')); loadSocketMessages(); } });
    if (socketDirectionFilter) chips.push({ label: socketDirectionFilter.toLowerCase(), title: 'Clear the direction filter', on: () => { socketDirectionFilter = ''; $('socketDirectionSeg').querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.value === '')); loadSocketMessages(); } });
    socketSelectedConnIds.forEach((id) => { const s = socketConnections.find((x) => x.id === id); chips.push({ label: s ? shortSocketUrl(s.url) : id, title: 'Stop showing this connection', on: () => { socketSelectedConnIds.delete(id); loadSocketMessages(); } }); });
    appliedFiltersHtml('socketChips', chips, () => {
      $('socketSearch').value = '';
      socketFrameTypeFilter = ''; socketDirectionFilter = ''; socketSelectedConnIds.clear();
      $('socketFrameTypeSeg').querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.value === ''));
      $('socketDirectionSeg').querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.value === ''));
      loadSocketMessages();
    });
  }

  viewControllers.socket = {
    containerId: 'sockets',
    order: () => socketOrder,
    getSelected: () => String(socketSelectedIndex),
    select: (id) => { socketSelectedIndex = Number(id); renderSockets(); },
    flagCurrent: () => { const m = socketMessages_[socketSelectedIndex]; if (m) toggleEvidenceFlag('socket', socketSelectedIndex + '@' + m.timestampEpochMs, m.direction + ' ' + m.frameType); },
  };

  function renderSocketConnDrop() {
    // Protocol badge only for MQTT: WebSocket stays the unmarked default (the overwhelmingly
    // common case today), so the badge draws the eye only where it's actually new information.
    const items = socketConnections.map((s) => ({ value: s.id, label: (s.protocol === 'mqtt' ? 'MQTT · ' : '') + shortSocketUrl(s.url), count: s.sentCount + '↑ ' + s.receivedCount + '↓', rtl: true }));
    const connDropEl = $('socketConnDrop');
    const focusSnap = captureFocus(connDropEl);
    connDropEl.innerHTML = dropdownHtml({
      btnId: 'socketConnDropBtn', label: 'Connection', items, selected: socketSelectedConnIds, open: socketConnDropOpen,
      dotForItem: (it) => { const s = socketConnections.find((x) => x.id === it.value); return s?.state === 'OPEN' ? 'var(--signal)' : s?.state === 'CLOSING' ? 'var(--warn)' : s?.state === 'FAILED' ? 'var(--error)' : 'var(--border-strong)'; },
    });
    restoreFocus(focusSnap, connDropEl);
    $('socketConnDropBtn').onclick = () => { socketConnDropOpen = !socketConnDropOpen; renderSocketConnDrop(); if (socketConnDropOpen) positionDropPanel('socketConnDropBtn'); };
    const panel = $('socketConnDropBtnPanel');
    if (!panel) return;
    panel.addEventListener('click', (e) => {
      const item = e.target.closest('[data-drop-value]');
      if (item) { const v = item.dataset.dropValue; socketSelectedConnIds.has(v) ? socketSelectedConnIds.delete(v) : socketSelectedConnIds.add(v); loadSocketMessages(); renderSocketConnDrop(); positionDropPanel('socketConnDropBtn'); return; }
      const action = e.target.closest('[data-drop-action]');
      if (!action) return;
      if (action.dataset.dropAction === 'select-all') socketConnections.filter((s) => s.state === 'OPEN').forEach((s) => socketSelectedConnIds.add(s.id));
      else socketSelectedConnIds.clear();
      loadSocketMessages(); renderSocketConnDrop(); positionDropPanel('socketConnDropBtn');
    });
  }

  // Keyed on connection+timestamp, never the list index: live-tail prepends frames and would
  // silently re-point an index-keyed evidence flag at a different frame.
  function socketFlagId(index, m) { return (m.connectionId || 'conn') + '@' + m.timestampEpochMs; }
  function renderSocketDetail() {
    const pane = $('socketMessages');
    const m = socketMessages_[socketSelectedIndex];
    if (!m) { renderEmpty(pane, 'sockets', 'No socket message selected', 'Select a connection or apply filters to inspect frames. JSON text frames are formatted; binary previews appear only when the host opted in.'); return; }
    const conn = socketConnections.find((s) => s.id === m.connectionId);
    const rec = m.direction === 'RECEIVED';
    const flagged = isFlagged('socket', socketFlagId(socketSelectedIndex, m));
    // MQTT carries no WebSocket opcode, but does carry topic/qos (decoded server-side from
    // SocketMessage.contentType — see MqttFrameMetadata) — so the "type" label and the fact list
    // both branch on protocol rather than assuming every frame is a WebSocket frame.
    const isMqtt = conn?.protocol === 'mqtt' || m.topic != null || m.qos != null;
    const head = detailHeadHtml({
      badgeText: rec ? 'RECV' : 'SENT', badgeTone: rec ? 'put' : 'signal',
      title: conn ? conn.url : m.connectionId, statusText: m.frameType, sTone: 'muted',
      facts: [
        { k: 'type', v: isMqtt ? 'MQTT message' : 'WebSocket frame' },
        { k: 'time', v: time(m.timestampEpochMs) },
        { k: 'size', v: frameSizeLabel(m) },
      ]
        .concat(isMqtt ? [] : [{ k: 'opcode', v: m.frameType === 'TEXT' ? '0x1' : m.frameType === 'BINARY' ? '0x2' : m.frameType === 'PING' ? '0x9' : '0xA' }])
        .concat(m.topic != null ? [{ k: 'topic', v: m.topic }] : [])
        .concat(m.qos != null ? [{ k: 'qos', v: String(m.qos) }] : [])
        .concat(conn ? [{ k: 'socket state', v: conn.state }, { k: 'opened', v: time(conn.openedAtEpochMs) }] : []),
      actions: [
        { id: 'flag', label: flagged ? 'Flagged as evidence' : 'Flag as evidence', icon: 'flag', on: flagged, title: 'Attach this frame to the evidence tray (Enter)' },
        { id: 'copy-frame', label: 'Copy frame', icon: 'copy' },
        { id: 'copy-all-frames', label: 'Copy all frames', icon: 'copy' },
      ],
    });
    const query = socketDetailQuery;
    resetBodyViewers();
    let body;
    let lines = null;
    if (m.frameType === 'PING' || m.frameType === 'PONG') body = `<span class="detail-group-empty">Control frame — no payload.</span>`;
    else if (m.payload?.kind === 'binary') body = `<span class="detail-group-empty">${m.payload.preview ? 'Binary preview (' + esc(m.payload.previewEncoding) + '): ' + esc(m.payload.preview) : 'Binary preview withheld — host did not opt in.'}</span>`;
    else {
      const preview = m.payload?.preview ?? '';
      try { lines = formatJsonLines(JSON.parse(preview), query); } catch { lines = formatJsonLines(preview, query); }
      body = bodyViewerHtml(preview, null, lines, conn ? conn.url : 'Socket frame payload', true, 'socket:' + socketFlagId(socketSelectedIndex, m));
    }
    const findBar = findBarHtml(query, lines ? countPaneHits([{ groups: [{ code: lines }] }]) : 0, 'socketDetailFindInput');
    body += `<div class="detail-footnote">${icon('lock', 'ic-sm')}<span>Frame payloads pass through the same redaction allowlist as HTTP bodies.</span></div>`;
    const focusSnap = captureFocus(pane);
    pane.innerHTML = head + findBar + `<div class="detail-body">${body}</div>`;
    mountBodyViewers(pane);
    restoreFocus(focusSnap, pane);
  }
  function wireSocketDetailPane() {
    $('socketMessages').addEventListener('click', (e) => {
      if (e.target.closest('[data-action="toggle-zoom"]')) { toggleDetailZoom(); return; }
      const actionBtn = e.target.closest('[data-detail-action]');
      if (!actionBtn) return;
      const m = socketMessages_[socketSelectedIndex];
      if (!m) return;
      const id = actionBtn.dataset.detailAction;
      if (id === 'flag') { toggleEvidenceFlag('socket', socketFlagId(socketSelectedIndex, m), m.direction + ' ' + m.frameType); renderSocketDetail(); }
      else if (id === 'copy-frame') copyToClipboard(m.payload?.kind === 'binary' ? '[binary frame]' : (m.payload?.preview ?? ''), 'Frame payload');
      else if (id === 'copy-all-frames') copyToClipboard(JSON.stringify(socketMessages_, null, 2), 'All frames');
    });
    $('socketMessages').addEventListener('input', (e) => { if (e.target.id === 'socketDetailFindInput') { socketDetailQuery = e.target.value; renderSocketDetail(); } });
  }

  // ================================================================
  // Push
  // ================================================================
  let pushOrder = [];
  const PUSH_EMPTY_HTML = listEmptyHtml('push', 'No push events match', 'Clear the provider filter or search to see the other captured events.');
  function renderPush() {
    const q = $('pushSearch').value.toLowerCase();
    const rows = pushes
      .map((p, i) => ({ p, i }))
      .filter(
        ({ p }) =>
          (!pushProviderFilter || p.provider === pushProviderFilter) &&
          (!q || `${p.messageId || ''} ${p.source || ''} ${p.lifecycle || ''} ${p.notification?.title || ''}`.toLowerCase().includes(q)),
      );
    pushOrder = rows.map(({ i }) => String(i));
    if (!pushOrder.includes(String(selectedPushIndex))) selectedPushIndex = rows.length ? rows[0].i : -1;
    virtualList('pushEvents').update(
      rows.length,
      (n, total) => {
        const { p, i } = rows[n];
        return rowHtml({
          id: String(i), selected: selectedPushIndex === i,
          badgeText: p.provider.toUpperCase(), badgeTone: p.simulated ? 'warn' : 'signal',
          mainText: p.notification?.title || p.messageId || '(untitled)',
          tagText: p.simulated ? 'SIMULATED' : false, tagTone: 'warn',
          duration: p.lifecycle, statusText: time(p.receivedAtEpochMs), sTone: 'muted',
          flagKind: 'push', flagLabel: p.provider + ' · ' + p.lifecycle,
          posinset: n + 1, setsize: total,
        });
      },
      PUSH_EMPTY_HTML,
    );
    $('pushFootLeft').textContent = rows.length + ' of ' + pushes.length;
    $('pushFootRight').textContent = 'push tokens are redacted';
    $('pushBadge').textContent = pushes.length + ' events';
    renderPushMetrics();
    renderPushDetail();
  }

  function renderPushMetrics() {
    const byLifecycle = (name) => pushes.filter((p) => p.lifecycle === name).length;
    const received = byLifecycle('RECEIVED'), displayed = byLifecycle('DISPLAYED'), opened = byLifecycle('OPENED');
    const simulated = pushes.filter((p) => p.simulated).length;
    $('pushMetrics').innerHTML = [
      metricHtml('Received', String(received), '', 'ink'),
      metricHtml('Displayed', String(displayed), '', 'ink'),
      metricHtml('Opened', String(opened), received ? Math.round((opened / received) * 100) + '%' : '', 'signal'),
      metricHtml('Simulated', String(simulated), '', 'warn'),
    ].join('');
  }

  viewControllers.push = {
    containerId: 'pushEvents',
    order: () => pushOrder,
    getSelected: () => String(selectedPushIndex),
    select: (id) => { selectedPushIndex = Number(id); renderPush(); },
    flagCurrent: () => { const p = pushes[selectedPushIndex]; if (p) toggleEvidenceFlag('push', String(selectedPushIndex), p.provider + ' · ' + p.lifecycle); },
  };

  function renderPushDetail() {
    const pane = $('pushDetail');
    const p = pushes[selectedPushIndex];
    if (!p) { renderEmpty(pane, 'push', 'No push notification selected', 'Select a push event from the list to inspect its payload.'); return; }
    const flagged = isFlagged('push', String(selectedPushIndex));
    const head = detailHeadHtml({
      badgeText: p.provider.toUpperCase(), badgeTone: p.simulated ? 'warn' : 'signal',
      title: p.notification?.title || p.messageId || '(untitled)', statusText: p.lifecycle, sTone: 'muted',
      facts: [{ k: 'time', v: time(p.receivedAtEpochMs) }, { k: 'message id', v: p.messageId || '—' }, { k: 'origin', v: p.simulated ? 'simulated' : 'captured' }]
        .concat(p.notification?.channelId ? [{ k: 'channel', v: p.notification.channelId }] : []),
      actions: [
        { id: 'flag', label: flagged ? 'Flagged as evidence' : 'Flag as evidence', icon: 'flag', on: flagged, title: 'Attach this event to the evidence tray (Enter)' },
        { id: 'replay', label: 'Re-simulate', icon: 'refresh', disabled: !hasSession(), title: hasSession() ? 'Inject this payload as a simulated push through DevConsole (not the app\'s FCM handler)' : 'Sign-in required' },
        { id: 'copy-payload', label: 'Copy payload', icon: 'copy' },
      ],
    });
    const query = pushDetailQuery;
    const lines = formatJsonLines(p, query);
    const findBar = findBarHtml(query, countPaneHits([{ groups: [{ code: lines }] }]), 'pushDetailFindInput');
    const body = codeBlockHtml(lines, true, p.notification?.title || p.messageId || 'Push payload') + `<div class="detail-footnote">${icon('lock', 'ic-sm')}<span>${
      p.simulated
        ? 'This event was injected from the Push view — it never came from a provider.'
        : 'Captured from ' + esc(p.provider.toUpperCase()) + ' on device. Redacted fields are replaced by the on-device allowlist before this browser receives them.'
    }</span></div>`;
    const focusSnap = captureFocus(pane);
    pane.innerHTML = head + findBar + `<div class="detail-body">${body}</div>`;
    restoreFocus(focusSnap, pane);
  }
  function wirePushDetailPane() {
    $('pushDetail').addEventListener('click', (e) => {
      if (e.target.closest('[data-action="toggle-zoom"]')) { toggleDetailZoom(); return; }
      const actionBtn = e.target.closest('[data-detail-action]');
      if (!actionBtn) return;
      const p = pushes[selectedPushIndex];
      if (!p) return;
      const id = actionBtn.dataset.detailAction;
      if (id === 'flag') { toggleEvidenceFlag('push', String(selectedPushIndex), p.provider + ' · ' + p.lifecycle); renderPushDetail(); }
      else if (id === 'replay') replayPush(p);
      else if (id === 'copy-payload') copyToClipboard(JSON.stringify(p, null, 2), 'Payload');
    });
    $('pushDetail').addEventListener('input', (e) => { if (e.target.id === 'pushDetailFindInput') { pushDetailQuery = e.target.value; renderPushDetail(); } });
  }
  async function replayPush(p) {
    if (!hasSession()) return;
    const values = { provider: p.provider, messageId: p.messageId || '' };
    Object.entries(p.data || {}).forEach(([k, v]) => { values['data.' + k] = v; });
    const r = await fetch('/api/v1/push/simulate', {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(values),
    });
    toast(r.ok ? 'Payload re-simulated through DevConsole.' : 'Re-simulate failed: ' + r.status, r.ok ? undefined : 'error');
    if (r.ok) loadPush();
  }

  function refreshPushChips() {
    const providers = [...new Set(pushes.map((p) => p.provider))].sort();
    if (!providers.includes(pushProviderFilter)) pushProviderFilter = '';
    const counts = {};
    pushes.forEach((p) => { counts[p.provider] = (counts[p.provider] || 0) + 1; });
    $('pushProviderSeg').innerHTML =
      `<button type="button" class="${pushProviderFilter === '' ? 'active' : ''}" data-value="" aria-pressed="${pushProviderFilter === ''}">All</button>` +
      providers.map((p) => `<button type="button" class="${pushProviderFilter === p ? 'active' : ''}" data-value="${esc(p)}" aria-pressed="${pushProviderFilter === p}">${esc(p)}<span style="margin-left:8px;color:var(--text-3);font-size:10.5px">${counts[p]}</span></button>`).join('');
  }

  async function loadPush() {
    if (!token) return;
    const r = await fetch('/api/v1/push/events', { headers: auth() });
    if (!r.ok) return;
    pushes = (await r.json()).data || [];
    if (selectedPushIndex >= pushes.length) selectedPushIndex = -1;
    refreshPushChips();
    renderPush();
    setNavCount('navCountPush', pushes.length);
  }

  async function simulatePush() {
    if (!hasSession()) return;
    const key = $('pushDataKey').value;
    const values = { provider: $('pushProvider').value, messageId: $('pushMessageId').value };
    if (key) values['data.' + key] = $('pushDataValue').value;
    const r = await fetch('/api/v1/push/simulate', {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(values),
    });
    if (r.ok) {
      toast('Push event simulated.');
      loadPush();
    } else {
      toast('Simulate failed: ' + r.status, 'error');
    }
  }

  // ================================================================
  // Crashes — uncaught exceptions and ANRs from the `crash` plugin, read cross-session via
  // GET /api/v1/retained-events?pluginId=crash (no sessionId). A crash's process dies right after
  // CrashCapture writes it to Room, so by the time a developer reopens the app and connects the
  // dashboard, the session that recorded it is no longer current — the live in-memory timeline
  // (GET /api/v1/events, always scoped to the current session) can never show it; this was the bug
  // that made the whole feature useless in its primary scenario. Retained-events has no such
  // scoping: pluginId set with no sessionId reads across every retained run instead of just the
  // live one (see RetainedCaptureQuery.events()'s pluginIds branch).
  //
  // Each row already carries payloadJson (breadcrumbs + all-thread dump) and sessionId inline
  // (retainedJson()), so this view no longer shares Timeline's ensureEventPayloadsLoaded()/
  // eventPayloadsById best-effort cache the way it used to — that cache is capped at 500 rows
  // *across every plugin*, so a real crash could be crowded out of it by unrelated log/network
  // activity. The crash-scoped fetch below can't be crowded out the same way: pluginId=crash is
  // applied in SQL before the 500-row cap trims anything (RoomEventStore.recentEventsForPlugins),
  // so a rare crash among heavy unrelated traffic still comes back with its payload attached.
  //
  // sessionId is joined against GET /api/v1/runs (loadCrashes()) so every row can say which run it
  // came from and the current run reads distinctly from prior ones — see crashRunInfo(). This is
  // also what lets the Overview "previous run crashed" banner (renderOverviewCrashBanner) jump to
  // the exact crash instead of just the Crashes view in general — see focusCrashesOnRun().
  //
  // Every crash and ANR is still auto-flagged into the evidence tray server-side at insert time
  // (CrashCapture.autoFlagCrash); this view is just a read surface over that same retained store.
  //
  // List uses the shared virtualList/createVirtualList helper (a13f094) like every other list
  // view — Network/Timeline/Sockets/Push — not a bespoke mechanism. Unlike /api/v1/events,
  // /api/v1/retained-events has no cursor — loadCrashes() already asks for
  // RetainedCaptureQuery.MAX_LIMIT (500) rows, the hard per-request ceiling, in one shot, so there
  // is nothing left to page into on scroll (no onNearEnd below).
  // ================================================================
  let crashEvents = [];
  let selectedCrashId = '';
  let crashOrder = [];
  let crashKindFilter = '';
  let crashRunsById = new Map(); // sessionId -> row from GET /api/v1/runs, refreshed by loadCrashes()
  const CRASHES_EMPTY_HTML = listEmptyHtml('bug', 'No crashes or ANRs match', 'Clear the kind filter or search to see the other captured events.');
  /** Splits raw multi-line text (the all-thread dump — not JSON) into codeBlockHtml()'s line-object
   * shape so the crash dump gets the exact same collapsible/fullscreen/copy treatment as every
   * JSON code block, without pretending the text is JSON. Truncation markers
   * ("N more frames (truncated)", "N more threads (truncated)", "(truncated, dump exceeded N
   * chars)") are literal substrings already inside the text — rendered as plain lines, never
   * stripped or specially parsed. */
  function plainCodeLines(text) {
    return String(text || '').split('\n').map((line) => ({ pad: '', k: '', v: line, cls: 'code-brace' }));
  }
  function crashKindOf(e) { return e.tags?.kind || (e.type === 'anr' ? 'ANR' : 'UNCAUGHT'); }
  function crashThreadOf(e) { return e.tags?.thread || ''; }
  /** The generic /api/v1/events list this view used to read embeds `tags` as a parsed object
   * directly; retainedJson() instead emits it as an escaped JSON *string* sibling (`tagsJson`),
   * like every other payload field on that row. Parsed once per row here so crashKindOf()/
   * crashThreadOf() (and everything else in this view) can keep reading a plain `tags` object. */
  function decorateCrashRow(e) {
    let tags;
    try { tags = e.tagsJson ? JSON.parse(e.tagsJson) : undefined; } catch { tags = undefined; }
    return { ...e, tags };
  }
  /** Joins a crash row's sessionId against the GET /api/v1/runs rows loaded alongside it in
   * loadCrashes(). Returns null — never a guessed label — when the run isn't in that map at all
   * (the runs fetch failed, or this event's session has since aged out of that list); callers must
   * treat null as "omit", not as "previous run". A run that IS present with ACTIVE status is the
   * run this dashboard is connected to right now; any other status is, by definition, a previous
   * run — StoredSessionStatus is authoritative here, not "was it the newest result". appVersionName
   * /deviceModel are both optional on StoredSession — joined in only when present, never fabricated
   * when absent (the same rule this file follows everywhere else absent metadata shows up). */
  function crashRunInfo(e) {
    const run = crashRunsById.get(e.sessionId);
    if (!run) return null;
    if (run.status === 'ACTIVE') return { text: 'Current run', tone: 'signal', current: true };
    const bits = [run.appVersionName ? 'v' + run.appVersionName : null, run.deviceModel].filter(Boolean);
    return { text: bits.length ? bits.join(' · ') : 'Previous run', tone: 'muted', current: false };
  }
  /** payloadJson missing on a row that DID come back from the crash-scoped retained-events fetch
   * (loadCrashes()) means exactly one thing now: this particular event genuinely has no payload
   * recorded (e.g. a pre-payload legacy capture). Unlike Timeline's shared cache, this fetch can't
   * be "crowded out" — see the module doc above — so that framing no longer applies here. */
  function crashPayloadMissingMessage(noun) {
    return `${noun} unavailable — no payload was recorded for this crash.`;
  }
  function crashPayloadOf(e) {
    if (!e?.payloadJson) return null;
    try { return JSON.parse(e.payloadJson); } catch { return null; }
  }
  /** Cross-session by design (see the module doc above): pluginId=crash with no sessionId reads
   * every retained run, not just the live one — the entire point, since a crash's process is gone
   * by the time anyone reopens the app to look for it. GET /api/v1/runs is fetched alongside it so
   * every row can be joined to the run that produced it (crashRunInfo()); a failed runs fetch just
   * leaves crashRunsById empty, so every row's run info falls back to "omit" rather than guessing. */
  async function loadCrashes() {
    if (!token) return;
    const params = new URLSearchParams({ limit: '500' });
    params.append('pluginId', 'crash');
    const [crashesRes, runsRes] = await Promise.all([
      fetch('/api/v1/retained-events?' + params, { headers: auth() }),
      fetch('/api/v1/runs', { headers: auth() }),
    ]);
    if (!crashesRes.ok) { toast('Crashes fetch failed: ' + crashesRes.status, 'error'); return; }
    const body = await crashesRes.json();
    const runs = runsRes.ok ? (await runsRes.json()).data || [] : [];
    crashRunsById = new Map(runs.map((run) => [run.id, run]));
    // RetainedCaptureQuery.events() always sorts ascending (wallTimeMs, sequence) regardless of
    // which branch resolved the read — reverse for the newest-first order every other list in this
    // dashboard uses.
    crashEvents = (body.data || []).slice().reverse().map(decorateCrashRow);
    if (!crashEvents.some((e) => e.id === selectedCrashId)) selectedCrashId = crashEvents[0]?.id || '';
    setNavCount('navCountCrashes', crashEvents.length);
    renderCrashes();
  }
  function renderCrashesMetrics() {
    const uncaught = crashEvents.filter((e) => e.type === 'uncaught').length;
    const anr = crashEvents.filter((e) => e.type === 'anr').length;
    $('crashesMetrics').innerHTML = [
      metricHtml('Total', String(crashEvents.length), '', 'ink'),
      metricHtml('Crashes', String(uncaught), '', 'error'),
      metricHtml('ANRs', String(anr), '', 'warn'),
      metricHtml('Flagged', String([...evidenceFlags.values()].filter((v) => v.kind === 'crash').length), 'as evidence', 'signal'),
    ].join('');
  }
  function renderCrashes() {
    const q = $('crashesSearch').value.toLowerCase();
    const rows = crashEvents.filter(
      (e) =>
        (!crashKindFilter || crashKindOf(e) === crashKindFilter) &&
        (!q || (e.summary + ' ' + crashThreadOf(e)).toLowerCase().includes(q)),
    );
    crashOrder = rows.map((e) => e.id);
    if (!crashOrder.includes(selectedCrashId)) selectedCrashId = rows[0]?.id || '';
    // No onNearEnd — see the module doc above; retained-events has no cursor and loadCrashes()
    // already asks for the hard per-request maximum in one shot.
    virtualList('crashesList').update(
      rows.length,
      (n, total) => {
        const e = rows[n];
        const kind = crashKindOf(e);
        const run = crashRunInfo(e);
        return rowHtml({
          id: e.id, selected: selectedCrashId === e.id,
          badgeText: kind === 'ANR' ? 'ANR' : 'CRASH', badgeTone: 'error',
          mainText: e.summary, tagText: run ? run.text : false, tagTone: run ? run.tone : 'muted',
          duration: crashThreadOf(e),
          statusText: time(e.wallTimeMs), sTone: 'muted',
          flagKind: 'crash', flagLabel: e.summary,
          posinset: n + 1, setsize: total,
        });
      },
      CRASHES_EMPTY_HTML,
    );
    $('crashesFootLeft').textContent = rows.length + ' of ' + crashEvents.length;
    $('crashesFootRight').textContent = 'auto-flagged as evidence';
    $('crashesBadge').textContent = crashEvents.length + ' recorded';
    renderCrashesMetrics();
    renderCrashDetail();
  }
  viewControllers.crashes = {
    containerId: 'crashesList',
    order: () => crashOrder,
    getSelected: () => selectedCrashId,
    select: (id) => { selectedCrashId = id; renderCrashes(); },
    flagCurrent: () => { const e = crashEvents.find((x) => x.id === selectedCrashId); if (e) toggleEvidenceFlag('crash', e.id, e.summary); },
  };
  /** Target of the Overview "previous run crashed" banner (renderOverviewCrashBanner) — landing on
   * the general Crashes view was the best that banner could do before crashes were addressable per
   * run; now that every row carries sessionId, it can select the exact
   * crash instead. Clears any leftover kind/search filter that would otherwise hide the target row
   * — the banner's whole job is to land the developer on this crash, not on "no rows match your
   * leftover filter" — then reuses the same select()+focusSelectedRow() path the j/k keyboard
   * stepper already uses (see the devconsole:step listener above) so scroll/focus land correctly
   * even though the list is virtualized. */
  async function focusCrashesOnRun(sessionId) {
    await show('crashes');
    const target = crashEvents.find((e) => e.sessionId === sessionId);
    if (!target) { toast('No retained crash found for that run.', 'error'); return; }
    if (crashKindFilter) {
      crashKindFilter = '';
      $('crashesKindSeg').querySelectorAll('button').forEach((b) => {
        const isAll = !b.dataset.value;
        b.classList.toggle('active', isAll);
        b.setAttribute('aria-pressed', String(isAll));
      });
    }
    if ($('crashesSearch').value) $('crashesSearch').value = '';
    await viewControllers.crashes.select(target.id);
    focusSelectedRow('crashesList', crashOrder.indexOf(target.id));
  }
  const crashGroupOpen = {};
  function renderCrashDetail() {
    const pane = $('crashDetail');
    const e = crashEvents.find((x) => x.id === selectedCrashId);
    if (!e) { renderEmpty(pane, 'bug', 'No crash selected', 'Select a crash or ANR from the list to inspect its breadcrumbs and all-thread dump.'); return; }
    const kind = crashKindOf(e);
    const flagged = isFlagged('crash', e.id);
    const payload = crashPayloadOf(e);
    const run = crashRunInfo(e);
    const head = detailHeadHtml({
      badgeText: kind === 'ANR' ? 'ANR' : 'CRASH', badgeTone: 'error',
      title: e.summary, statusText: time(e.wallTimeMs), sTone: 'muted',
      extraBadge: run?.current ? { text: 'CURRENT RUN', tone: 'signal' } : null,
      facts: [{ k: 'thread', v: crashThreadOf(e) || '—' }, { k: 'type', v: e.type }]
        .concat(run ? [{ k: 'run', v: run.text }] : [])
        .concat(payload?.breadcrumbs ? [{ k: 'breadcrumbs', v: String(payload.breadcrumbs.length) }] : []),
      actions: [
        { id: 'flag', label: flagged ? 'Flagged as evidence' : 'Flag as evidence', icon: 'flag', on: flagged, title: 'Attach this crash to the evidence tray (Enter) — crashes auto-flag at capture time, so this is usually already on' },
        { id: 'copy-dump', label: 'Copy all-thread dump', icon: 'copy', disabled: !payload?.stackTrace },
      ],
    });
    const breadcrumbsHtml = (() => {
      const crumbs = payload?.breadcrumbs || [];
      if (!crumbs.length) return `<div class="crumb-empty">${esc(payload ? 'No breadcrumbs recorded for this crash.' : crashPayloadMissingMessage('Breadcrumbs'))}</div>`;
      return `<div class="crumb-strip">${crumbs
        .map((c) => {
          const lvl = SEVERITY_SHORT[c.severity] || 'INF';
          return `<div class="crumb crumb-sev-${levelTone(lvl)}"><div class="crumb-head"><span>${esc(time(c.ts))}</span><span>${esc(c.plugin)}</span></div><div class="crumb-summary">${esc(c.summary)}</div></div>`;
        })
        .join('')}</div>`;
    })();
    const dumpGroup = detailGroupHtml(
      'crash/dump',
      { label: 'All-thread dump', code: payload?.stackTrace ? plainCodeLines(payload.stackTrace) : null, empty: payload?.stackTrace ? undefined : (payload ? 'No stack trace recorded.' : crashPayloadMissingMessage('Stack trace')), meta: payload?.stackTrace ? payload.stackTrace.split('\n').length + ' lines' : '' },
      crashGroupOpen,
    );
    const focusSnap = captureFocus(pane);
    pane.innerHTML = head + `<div class="detail-body">
      <div class="block-title">Breadcrumbs</div>
      ${breadcrumbsHtml}
      ${dumpGroup}
      <div class="detail-footnote">${icon('lock', 'ic-sm')}<span>Breadcrumbs are already-redacted summaries; the all-thread dump is redacted the same way any other stack trace/text payload is before it ever leaves the device.</span></div>
    </div>`;
    restoreFocus(focusSnap, pane);
  }
  function wireCrashDetailPane() {
    $('crashDetail').addEventListener('click', (e) => {
      if (e.target.closest('[data-action="toggle-zoom"]')) { toggleDetailZoom(); return; }
      const groupToggle = e.target.closest('[data-group-toggle]');
      if (groupToggle) { crashGroupOpen[groupToggle.dataset.groupToggle] = groupToggle.getAttribute('aria-expanded') !== 'true'; renderCrashDetail(); return; }
      const actionBtn = e.target.closest('[data-detail-action]');
      if (!actionBtn) return;
      const ev = crashEvents.find((x) => x.id === selectedCrashId);
      if (!ev) return;
      const id = actionBtn.dataset.detailAction;
      if (id === 'flag') { toggleEvidenceFlag('crash', ev.id, ev.summary); renderCrashDetail(); }
      else if (id === 'copy-dump') {
        const payload = crashPayloadOf(ev);
        if (payload?.stackTrace) copyToClipboard(payload.stackTrace, 'All-thread dump');
      }
    });
  }

  // ================================================================
  // State & flags
  // ================================================================
  // ================================================================
  // State & Flags: feature flags have no session-wide write switch —
  // each flag's own `mutable` bit gates its toggle instead of one banner. State provider
  // mutators are different: GET /api/v1/state/{id} reports the real `mutators` array for the
  // selected provider, and a POST to /mutations/{command} is refused session-wide with
  // STATE_MUTATION_DISABLED when the host has that capability off, which is what drives the
  // gate banner below.
  // ================================================================
  let stateProviderIds = [];
  let selectedStateProviderId = '';
  let selectedStateValue; // undefined = not loaded yet
  let selectedStateMutators = [];
  let cachedFlags = [];
  async function loadState() {
    if (!token) return;
    const [statesRes, flagsRes] = await Promise.all([fetch('/api/v1/state', { headers: auth() }), fetch('/api/v1/flags', { headers: auth() })]);
    stateProviderIds = statesRes.ok ? ((await statesRes.json()).data || []).map((s) => s.id) : [];
    if (!stateProviderIds.includes(selectedStateProviderId)) {
      selectedStateProviderId = stateProviderIds[0] || '';
      selectedStateValue = undefined;
    }
    cachedFlags = flagsRes.ok ? (await flagsRes.json()).data || [] : [];
    if (selectedStateProviderId) await showState(selectedStateProviderId);
    else renderStateCards();
  }
  async function showState(id) {
    if (!token || !id) return;
    selectedStateProviderId = id;
    selectedStateValue = undefined;
    selectedStateMutators = [];
    renderStateCards();
    const r = await fetch('/api/v1/state/' + encodeURIComponent(id), { headers: auth() });
    const body = r.ok ? await r.json() : null;
    selectedStateValue = body ? body.values : null;
    selectedStateMutators = body?.mutators || [];
    renderStateCards();
  }
  async function setFlagValue(key, value) {
    if (!hasSession()) return;
    const r = await fetch('/api/v1/flags/' + encodeURIComponent(key), { method: 'POST', headers: { ...controlHeaders(), 'Content-Type': 'text/plain' }, body: value });
    toast(r.ok ? key + ' set to ' + value : 'Flag update failed: ' + r.status, r.ok ? undefined : 'error');
    if (r.ok) loadState();
  }
  function copyStateJson() {
    if (selectedStateValue === undefined || selectedStateValue === null) return;
    navigator.clipboard?.writeText(JSON.stringify(selectedStateValue, null, 2)).then(() => toast('State copied.'));
  }
  /** The command body is raw text, not JSON — inputSchema only tells us how to *shape* the
   * control (a boolean picker, an enum select, a number field, or a free-text/JSON textarea for
   * anything else), never something we validate against client-side. */
  /** `data-mutator-provider`/`data-mutator-id` (read back via `.dataset` — auto-decoded, exact)
   * are what runStateMutation() actually looks the control up by; a mutator/provider id
   * containing `"` or `&` is still findable through them. `id`/`data-preserve` stay only so the
   * cardsGridHtml() re-render-preserve pass (which indexes by the real, already-decoded `.id`
   * property, not this interpolated string) keeps working — that mechanism was never the broken
   * part. */
  function stateMutatorInputHtml(providerId, m) {
    let schema = {};
    try { schema = JSON.parse(m.inputSchema || '{}') || {}; } catch { schema = {}; }
    const inputId = 'stateMutatorInput-' + providerId + '-' + m.id;
    const dataAttrs = `data-mutator-provider="${esc(providerId)}" data-mutator-id="${esc(m.id)}"`;
    if (schema.type === 'boolean') {
      return `<select id="${esc(inputId)}" ${dataAttrs} data-preserve><option value="true">true</option><option value="false">false</option></select>`;
    }
    if (Array.isArray(schema.enum) && schema.enum.length) {
      return `<select id="${esc(inputId)}" ${dataAttrs} data-preserve>${schema.enum.map((v) => `<option value="${esc(String(v))}">${esc(String(v))}</option>`).join('')}</select>`;
    }
    if (schema.type === 'number' || schema.type === 'integer') {
      return `<input id="${esc(inputId)}" ${dataAttrs} data-preserve type="number" placeholder="value">`;
    }
    if (schema.type === 'string') {
      return `<input id="${esc(inputId)}" ${dataAttrs} data-preserve type="text" placeholder="value">`;
    }
    return `<textarea id="${esc(inputId)}" ${dataAttrs} data-preserve rows="2" placeholder="${esc(JSON.stringify(schema))}"></textarea>`;
  }
  function stateMutatorsFieldsHtml(providerId, mutators) {
    if (!mutators.length) return '';
    return `<div class="card-fields">${mutators
      .map((m) => `<label class="field"><span>${esc(m.id)}</span>${stateMutatorInputHtml(providerId, m)}</label>`)
      .join('')}</div>`;
  }
  async function runStateMutation(providerId, mutatorId) {
    if (!hasSession()) return;
    // Not $(id) with the same interpolated string stateMutatorInputHtml() wrote — that only
    // round-trips correctly if id-escaping and this lookup agree byte-for-byte. A dataset
    // comparison sidesteps that entirely: `.dataset` is always the exact, already-decoded value,
    // so this matches regardless of what characters providerId/mutatorId contain.
    const input = [...$('stateCards').querySelectorAll('[data-mutator-id]')].find(
      (el) => el.dataset.mutatorProvider === providerId && el.dataset.mutatorId === mutatorId,
    );
    const value = input ? input.value : '';
    const r = await fetch('/api/v1/state/' + encodeURIComponent(providerId) + '/mutations/' + encodeURIComponent(mutatorId), {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'text/plain' },
      body: value,
    });
    if (r.ok) {
      const body = await r.json();
      selectedStateValue = body.values;
      $('stateGate').hidden = true;
      $('stateGate').innerHTML = '';
      toast(mutatorId + ' applied.');
      renderStateCards();
      return;
    }
    let code;
    try { code = (await r.json()).code; } catch { code = undefined; }
    if (code === 'STATE_MUTATION_DISABLED') {
      $('stateGate').hidden = false;
      $('stateGate').innerHTML = gateBannerHtml({
        title: 'State mutations are disabled',
        body: 'The stateMutationsEnabled capability is off for this build, so mutator commands are refused session-wide.',
        code: 'STATE_MUTATION_DISABLED',
      });
    } else {
      toast('Mutation failed: ' + r.status, 'error');
    }
  }
  function renderStateCards() {
    const boolFlags = cachedFlags.filter((f) => f.type === 'BOOLEAN');
    const otherFlags = cachedFlags.filter((f) => f.type !== 'BOOLEAN');
    cardsGridHtml('stateCards', [
      {
        icon: 'flag', iconTone: 'signal', title: 'Feature flags', badge: cachedFlags.length + ' flag' + (cachedFlags.length === 1 ? '' : 's'), badgeTone: 'muted',
        lede: cachedFlags.length ? false : 'No feature flags are registered for this session.',
        rows: otherFlags.map((f) => ({ k: f.key, v: f.value, tone: 'ink', tag: f.mutable ? false : 'FIXED', tagTone: 'muted' })),
        toggles: boolFlags.map((f) => ({
          id: f.key, checked: f.value === 'true', disabled: !hasSession() || !f.mutable,
          k: f.key, sub: f.source + (f.mutable ? '' : ' · fixed') + (f.value !== f.defaultValue ? ' · overridden' : ''),
          title: f.mutable ? 'Toggle ' + f.key : f.key + ' is not mutable',
        })),
      },
      {
        icon: 'database', iconTone: 'signal', title: 'State providers', badge: stateProviderIds.length + ' registered', badgeTone: 'muted',
        lede: stateProviderIds.length ? false : 'This application has not registered any state providers.',
        tree: stateProviderIds.map((id) => ({ id, label: id, icon: 'flag', selected: id === selectedStateProviderId, tone: id === selectedStateProviderId ? 'ink' : 'muted' })),
      },
      {
        icon: 'grid', iconTone: 'signal', title: selectedStateProviderId ? 'App state · ' + selectedStateProviderId : 'App state', span: 2,
        lede: selectedStateProviderId ? (selectedStateValue === undefined ? 'Loading…' : false) : 'Choose a provider to inspect its typed snapshot.',
        code: selectedStateValue !== undefined && selectedStateValue !== null ? formatJsonLines(selectedStateValue, '') : false,
        bodyHtml: stateMutatorsFieldsHtml(selectedStateProviderId, selectedStateMutators),
        buttons: (selectedStateValue != null ? [{ id: 'copy-state', label: 'Copy JSON', icon: 'copy' }] : []).concat(
          selectedStateMutators.map((m) => ({
            id: 'mutator:' + m.id, label: 'Run ' + m.id, icon: 'terminal', disabled: !hasSession(),
            title: hasSession() ? 'Send the value above as the raw body of ' + m.id : 'Connect this browser first',
          })),
        ),
      },
    ]);
  }

  // ================================================================
  // Remote Config
  // ================================================================
  let remoteConfigProviders = [];
  let remoteConfigQuery = '';
  let remoteConfigSourceFilter = 'all';
  const REMOTE_CONFIG_SOURCE_TONE = { remote: 'signal', override: 'warn', default: 'muted', static: 'muted', unknown: 'muted' };
  async function loadRemoteConfig() {
    if (!token) return;
    const r = await fetch('/api/v1/remote-config', { headers: auth() });
    if (!r.ok) {
      // Counter and badge are reset here too: leaving the last successful load's numbers next to a
      // card that says the fetch failed is the one reading that is worse than either alone.
      remoteConfigProviders = [];
      setNavCount('navCountRemoteConfig', 0);
      $('remoteConfigBadge').textContent = 'unavailable';
      cardsGridHtml('remoteConfigCards', [{ icon: 'sliders', iconTone: 'signal', title: 'Remote Config', lede: 'Remote Config unavailable: ' + r.status }]);
      return;
    }
    remoteConfigProviders = (await r.json()).data || [];
    renderRemoteConfigCards();
  }
  /** Never-fetched is spelled out; rendering epoch 0 (or a sentinel) as a date would read as a real fetch. */
  function remoteConfigFetchLine(provider) {
    const fetchInfo = provider.fetch || {};
    const when = fetchInfo.lastFetchEpochMs == null ? 'never' : new Date(fetchInfo.lastFetchEpochMs).toLocaleString();
    const status = (fetchInfo.status || 'unknown').replace(/_/g, ' ');
    const interval = fetchInfo.minimumFetchIntervalSeconds == null ? '' : ' · min interval ' + fetchInfo.minimumFetchIntervalSeconds + 's';
    return 'last fetch: ' + when + ' · ' + status + interval;
  }
  function renderRemoteConfigCards() {
    const total = remoteConfigProviders.reduce((sum, p) => sum + (p.entries || []).length, 0);
    setNavCount('navCountRemoteConfig', total);
    $('remoteConfigBadge').textContent = remoteConfigProviders.length
      ? total + ' key' + (total === 1 ? '' : 's') + ' · ' + remoteConfigProviders.length + ' provider' + (remoteConfigProviders.length === 1 ? '' : 's')
      : 'no providers';
    if (!remoteConfigProviders.length) {
      cardsGridHtml('remoteConfigCards', [{
        icon: 'sliders', iconTone: 'signal', title: 'Remote Config', span: 2,
        lede: 'No Remote Config provider is registered for this session. Register one with DevConsoleConfig.withRemoteConfigProviders(...) — see docs/REMOTE_CONFIG.md.',
      }]);
      return;
    }
    const needle = remoteConfigQuery.trim().toLowerCase();
    cardsGridHtml('remoteConfigCards', remoteConfigProviders.map((provider) => {
      const all = provider.entries || [];
      const shown = all.filter((e) => (!needle || e.key.toLowerCase().includes(needle))
        && (remoteConfigSourceFilter === 'all' || e.source === remoteConfigSourceFilter));
      // Four distinct states, none of which may render as an unexplained blank table.
      let lede = false;
      if (provider.unavailableReason) lede = 'Remote Config unavailable: ' + provider.unavailableReason;
      else if (!all.length) {
        lede = (provider.fetch || {}).lastFetchEpochMs == null
          ? 'No values — this provider has not completed a fetch yet.'
          : 'No values returned by the last fetch.';
      } else if (!shown.length) lede = 'No key matches the current filter.';
      return {
        icon: 'sliders', iconTone: provider.unavailableReason ? 'warn' : 'signal', span: 2,
        title: 'Remote Config · ' + provider.id,
        badge: remoteConfigFetchLine(provider), badgeTone: provider.unavailableReason ? 'warn' : 'muted',
        lede,
        rows: shown.map((e) => ({
          k: e.key,
          v: e.redacted ? '<redacted>' : e.value,
          // A [providerId, key] pair, not a position in `shown`: the search box and the source
          // filter re-render this list constantly, and an index would point at a different key
          // after either one changes. Same composite-dataset-key shape the mock diff rows use.
          click: JSON.stringify([provider.id, e.key]),
          clickTitle: 'Show the full value of ' + e.key,
          tone: e.source === 'remote' ? 'ink' : 'muted',
          tag: (e.source || 'unknown').toUpperCase() + (e.truncated ? ' · TRUNCATED' : ''),
          tagTone: REMOTE_CONFIG_SOURCE_TONE[e.source] || 'muted',
        })),
      };
    }));
  }

  // ================================================================
  // Remote Config value modal — a Remote Config value is a *string* on the wire (see
  // RemoteConfigEntry's own doc), so the card row can only ever show a one-line preview of it.
  // This is where you read the whole thing: pretty-printed when it really is JSON, verbatim when
  // it is not. Deliberately never renders a non-JSON value as a quoted JSON string — the quotes
  // would be this viewer's invention, not something the server sent, which is the same rule the
  // source badge follows.
  // ================================================================
  let remoteConfigValueTarget = null; // { providerId, key } — null while the modal is closed
  let remoteConfigValueMode = 'json'; // 'json' | 'raw'
  let remoteConfigValueOpenerEl = null;
  function remoteConfigEntryAt(providerId, key) {
    const provider = remoteConfigProviders.find((p) => p.id === providerId);
    return provider ? (provider.entries || []).find((e) => e.key === key) : undefined;
  }
  /** Pretty JSON is offered only when the value actually parses. A redacted value has no real
   * content to parse, and a truncated one is a cut string that will almost never parse — both are
   * told apart from "just not JSON" so nobody debugs a parse failure that is really a length cut. */
  function remoteConfigValueView(entry) {
    if (!entry) return { mode: 'raw', jsonOk: false, text: '', notice: '' };
    if (entry.redacted) {
      return { mode: 'raw', jsonOk: false, text: '<redacted>', notice: 'Value withheld by the redaction policy.' };
    }
    const text = entry.value == null ? '' : String(entry.value);
    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch {
      return {
        mode: 'raw', jsonOk: false, text,
        notice: entry.truncated
          ? 'Value was truncated on capture, so it no longer parses as JSON — showing raw text.'
          : 'Not valid JSON — showing raw text.',
      };
    }
    return { mode: 'json', jsonOk: true, parsed, text, notice: entry.truncated ? 'Value was truncated on capture.' : '' };
  }
  function renderRemoteConfigValueModal() {
    if (!remoteConfigValueTarget) return;
    const entry = remoteConfigEntryAt(remoteConfigValueTarget.providerId, remoteConfigValueTarget.key);
    const view = remoteConfigValueView(entry);
    const source = (entry && entry.source) || 'unknown';
    $('remoteConfigValueTitle').textContent = remoteConfigValueTarget.key;
    const badge = $('remoteConfigValueSource');
    badge.textContent = source.toUpperCase() + (entry && entry.truncated ? ' · TRUNCATED' : '');
    badge.className = 'card-row-tag tone-text-' + (REMOTE_CONFIG_SOURCE_TONE[source] || 'muted');
    $('remoteConfigValueProvider').textContent = remoteConfigValueTarget.providerId;
    const jsonBtn = $('remoteConfigValueSeg').querySelector('button[data-value="json"]');
    jsonBtn.disabled = !view.jsonOk;
    jsonBtn.title = view.jsonOk ? 'Pretty-print this value as JSON' : 'This value is not valid JSON';
    $('remoteConfigValueSeg').querySelectorAll('button').forEach((b) => {
      const on = b.dataset.value === remoteConfigValueMode;
      b.classList.toggle('active', on);
      b.setAttribute('aria-pressed', String(on));
    });
    const notice = $('remoteConfigValueNotice');
    notice.textContent = view.notice;
    notice.hidden = !view.notice;
    $('remoteConfigValueBody').innerHTML = remoteConfigValueMode === 'json' && view.jsonOk
      ? codeLinesHtml(formatJsonLines(view.parsed, ''), true)
      : `<div class="code-block code-block-lg"><div class="code-line rc-raw-line">${esc(view.text)}</div></div>`;
  }
  function openRemoteConfigValue(rowId) {
    let providerId, key;
    try {
      [providerId, key] = JSON.parse(rowId);
    } catch {
      return;
    }
    const entry = remoteConfigEntryAt(providerId, key);
    if (!entry) return;
    remoteConfigValueTarget = { providerId, key };
    // Pretty JSON is the default, but only where it means anything: a plain string value opens on
    // Raw with the JSON segment disabled rather than on an error you have to click past.
    remoteConfigValueMode = remoteConfigValueView(entry).mode;
    remoteConfigValueOpenerEl = document.activeElement;
    renderRemoteConfigValueModal();
    $('remoteConfigValueModal').hidden = false;
    document.addEventListener('keydown', remoteConfigValueKeydown);
    $('remoteConfigValueClose').focus();
  }
  function closeRemoteConfigValue() {
    const overlay = $('remoteConfigValueModal');
    if (!overlay || overlay.hidden) return;
    overlay.hidden = true;
    $('remoteConfigValueBody').innerHTML = '';
    remoteConfigValueTarget = null;
    document.removeEventListener('keydown', remoteConfigValueKeydown);
    remoteConfigValueOpenerEl?.focus?.();
    remoteConfigValueOpenerEl = null;
  }
  function remoteConfigValueKeydown(e) {
    const overlay = $('remoteConfigValueModal');
    if (e.key === 'Escape') { closeRemoteConfigValue(); return; }
    if (e.key !== 'Tab') return;
    const focusable = [...overlay.querySelectorAll('button')].filter((el) => !el.disabled && el.offsetParent !== null);
    if (!focusable.length) return;
    const first = focusable[0], last = focusable[focusable.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }
  /** Copies what is on screen, not always the raw value: someone who switched to Pretty JSON to
   * read it wants the indented text they are looking at. */
  function copyRemoteConfigValue() {
    if (!remoteConfigValueTarget) return;
    const view = remoteConfigValueView(remoteConfigEntryAt(remoteConfigValueTarget.providerId, remoteConfigValueTarget.key));
    const text = remoteConfigValueMode === 'json' && view.jsonOk ? JSON.stringify(view.parsed, null, 2) : view.text;
    copyToClipboard(text, remoteConfigValueTarget.key);
  }

  // ================================================================
  // Preferences
  // ================================================================
  const SENSITIVE_KEY_PATTERN = /token|secret|password|passwd|auth|credential|cookie/i;
  const canEditPreferences = () => hasSession() && preferencesEditable;
  async function loadPreferences() {
    if (!token) return;
    const r = await fetch('/api/v1/preferences', { headers: auth() });
    if (!r.ok) {
      cardsGridHtml('prefCards', [{ icon: 'sliders', iconTone: 'signal', title: 'Preferences', lede: 'Preferences unavailable: ' + r.status }]);
      return;
    }
    const body = await r.json();
    preferencesEditable = body.editable === true;
    updateControlUi();
    preferenceFiles = body.data || [];
    const selected = $('prefFile').value;
    $('prefFile').innerHTML = '<option value="">Choose a file</option>' + preferenceFiles.map((f) => `<option value="${esc(f.name)}">${esc(f.name)}</option>`).join('');
    const name = preferenceFiles.some((f) => f.name === selected) ? selected : preferenceFiles[0]?.name || '';
    $('prefFile').value = name;
    renderPreferenceFile(name);
  }
  function renderPreferenceFile(name) {
    $('prefBadge').textContent = name || 'no file selected';
    const file = preferenceFiles.find((f) => f.name === name);
    const entries = file?.entries || [];
    const masked = entries.filter((e) => e.redacted);
    const blindSpots = entries.filter((e) => !e.redacted && SENSITIVE_KEY_PATTERN.test(e.key));
    const cards = [
      {
        icon: 'alert', iconTone: blindSpots.length ? 'warn' : 'signal', title: 'Known redaction blind spot', span: 2,
        lede: 'The allowlist matches key names literally. A key that merely *looks* sensitive but isn’t on the deny list is transmitted verbatim — shown honestly here rather than hidden, since the fix is a pattern rule, not a UI change.',
        rows: masked.slice(0, 4).map((e) => ({ k: e.key, v: e.value, tone: 'warn', tag: 'MASKED', tagTone: 'warn' })).concat(
          blindSpots.map((e) => ({ k: e.key, v: e.value, tone: 'error', tag: 'VERBATIM', tagTone: 'error' })),
        ),
      },
      file
        ? {
            icon: 'sliders', iconTone: 'signal', title: name, span: 2, badge: entries.length + ' entries', badgeTone: 'muted',
            lede: entries.length ? false : 'This file has no entries.',
            table: entries.length
              ? {
                  cols: ['Key', 'Value', 'Type', 'Redaction'],
                  rows: entries.map((e) => [
                    { v: e.key, tone: 'ink' },
                    { v: e.value, tone: e.redacted ? 'warn' : SENSITIVE_KEY_PATTERN.test(e.key) ? 'error' : 'ink' },
                    { v: e.type, tone: 'muted' },
                    { v: e.redacted ? 'masked' : SENSITIVE_KEY_PATTERN.test(e.key) ? 'NOT MATCHED' : 'none', tone: e.redacted ? 'warn' : SENSITIVE_KEY_PATTERN.test(e.key) ? 'error' : 'muted' },
                  ]),
                }
              : false,
            fieldsHtml: `<div class="card-fields" style="margin-top:12px">
              <label class="field"><span>Key</span><input id="prefKey" placeholder="last_user_id"></label>
              <label class="field"><span>Value</span><input id="prefValue" placeholder="42"></label>
              <label class="field"><span>Type</span><select id="prefType"><option>STRING</option><option>BOOLEAN</option><option>INT</option><option>LONG</option><option>FLOAT</option></select></label>
            </div>`,
            buttons: [
              { id: 'save', label: 'Save entry', icon: 'check', kind: canEditPreferences() ? 'primary' : 'default', disabled: !canEditPreferences(), title: canEditPreferences() ? 'Add or overwrite a key' : 'Sign in and enable the preferences capability' },
              { id: 'remove', label: 'Remove entry', icon: 'trash', kind: 'danger', disabled: !canEditPreferences(), title: canEditPreferences() ? 'Remove the key above' : 'Sign in and enable the preferences capability' },
            ],
          }
        : { icon: 'sliders', iconTone: 'signal', title: 'Entries', span: 2, lede: 'Choose a file to inspect its entries.' },
    ];
    cardsGridHtml('prefCards', cards);
    wireCardGrid('prefCards', { onButton: (id) => { if (id === 'save') savePreference(); else if (id === 'remove') removePreference(); } });
  }
  async function savePreference() {
    if (!canEditPreferences()) return;
    const file = $('prefFile').value;
    const key = $('prefKey').value;
    if (!file || !key) return;
    const body = new URLSearchParams({ key, value: $('prefValue').value, type: $('prefType').value });
    const r = await fetch('/api/v1/preferences/' + encodeURIComponent(file), {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    });
    toast(r.ok ? 'Preference saved.' : 'Save failed: ' + r.status, r.ok ? undefined : 'error');
    if (r.ok) loadPreferences();
  }
  async function removePreference() {
    if (!canEditPreferences()) return;
    const file = $('prefFile').value;
    const key = $('prefKey').value;
    if (!file || !key) return;
    const r = await fetch('/api/v1/preferences/' + encodeURIComponent(file) + '?key=' + encodeURIComponent(key), {
      method: 'DELETE',
      headers: controlHeaders(),
    });
    toast(r.ok ? 'Preference removed.' : 'Remove failed: ' + r.status, r.ok ? undefined : 'error');
    if (r.ok) loadPreferences();
  }

  // ================================================================
  // Database
  // ================================================================
  let dbSelectedTable = '';
  let dbTableQuery = '';
  let dbSort = 'name';
  let dbLastRows = null; // {columns, rows, truncated, rowIds} of the currently shown table, for Copy as CSV
  let dbSizeBytes = null;
  const canEditDatabase = () => hasSession() && databaseEditable;
  async function loadDatabases() {
    if (!token) return;
    const r = await fetch('/api/v1/database', { headers: auth() });
    if (!r.ok) {
      cardsGridHtml('dbCards', [{ icon: 'database', iconTone: 'signal', title: 'Database', lede: 'Databases unavailable: ' + r.status }]);
      return;
    }
    const body = await r.json();
    databaseEditable = body.editable === true;
    updateControlUi();
    databases = body.data || [];
    const selected = $('dbName').value;
    $('dbName').innerHTML = '<option value="">Choose a database</option>' + databases.map((d) => `<option value="${esc(d)}">${esc(d)}</option>`).join('');
    const name = databases.includes(selected) ? selected : databases[0] || '';
    $('dbName').value = name;
    $('dbBadge').textContent = name ? name + ' · ' + databases.length + ' database' + (databases.length === 1 ? '' : 's') : 'no database';
    if (name) loadTables(name);
    else renderDbCards();
  }
  async function loadTables(name) {
    if (!token || !name) return;
    const r = await fetch('/api/v1/database/' + encodeURIComponent(name), { headers: auth() });
    if (!r.ok) {
      cardsGridHtml('dbCards', [{ icon: 'database', iconTone: 'signal', title: 'Tables', lede: 'Tables unavailable: ' + r.status }]);
      return;
    }
    const body = await r.json();
    dbTablesCache = body.tables || [];
    dbSizeBytes = typeof body.sizeBytes === 'number' ? body.sizeBytes : null;
    if (!dbTablesCache.some((t) => t.name === dbSelectedTable)) { dbSelectedTable = ''; dbLastRows = null; }
    renderDbCards();
    if (dbSelectedTable) loadTableRows(dbSelectedTable);
  }
  async function loadTableRows(table) {
    if (!token) return;
    const name = $('dbName').value;
    if (!name || !table) return;
    dbSelectedTable = table;
    const r = await fetch('/api/v1/database/' + encodeURIComponent(name) + '/tables/' + encodeURIComponent(table), { headers: auth() });
    dbLastRows = r.ok ? await r.json() : null;
    renderDbCards();
  }
  function renderDbCards() {
    const q = dbTableQuery.trim().toLowerCase();
    let shown = dbTablesCache.filter((t) => !q || t.name.toLowerCase().includes(q));
    if (dbSort === 'rows') shown = shown.slice().sort((a, b) => b.rowCount - a.rowCount);
    const table = dbTablesCache.find((t) => t.name === dbSelectedTable);
    const cols = dbLastRows && Array.isArray(dbLastRows.columns) ? dbLastRows.columns : null;
    const rows = dbLastRows && Array.isArray(dbLastRows.rows) ? dbLastRows.rows : null;
    const sqlCard = dbSqlResult
      ? [{
          icon: 'terminal', iconTone: 'signal', title: 'Statement result', span: 2,
          badge: dbSqlResult.rows ? dbSqlResult.rows.length + ' row' + (dbSqlResult.rows.length === 1 ? '' : 's') : 'executed',
          badgeTone: 'signal',
          lede: dbSqlResult.rows && dbSqlResult.rows.length ? false : 'Statement executed — no rows returned.',
          table: dbSqlResult.columns && dbSqlResult.rows && dbSqlResult.rows.length
            ? { cols: dbSqlResult.columns, rows: dbSqlResult.rows.map((row) => dbSqlResult.columns.map((c, i) => ({ v: Array.isArray(row) ? (row[i] ?? '') : (row[c] ?? ''), tone: i === 0 ? 'muted' : 'ink' }))) }
            : false,
          buttons: [{ id: 'clear-sql-result', label: 'Dismiss', icon: 'close', title: 'Clear this result' }],
        }]
      : [];
    cardsGridHtml('dbCards', sqlCard.concat([
      {
        icon: 'database', iconTone: 'signal', title: 'Tables', badge: shown.length + ' of ' + dbTablesCache.length, badgeTone: 'muted',
        lede: (shown.length ? false : q ? 'No table name contains “' + dbTableQuery + '”.' : 'This database has no tables.'),
        rows: dbSizeBytes != null ? [{ k: 'Database size', v: formatBytes(dbSizeBytes), tone: 'muted' }] : [],
        tree: shown.map((t) => ({ id: t.name, label: t.name, icon: 'database', meta: t.rowCount.toLocaleString(), selected: t.name === dbSelectedTable, tone: t.name === dbSelectedTable ? 'ink' : 'muted' })),
      },
      {
        icon: 'grid', iconTone: 'signal', title: table ? table.name : 'Table rows', span: 2,
        badge: table ? (rows ? rows.length : 0) + ' of ' + table.rowCount.toLocaleString() + ' rows' : false, badgeTone: 'muted',
        lede: !table
          ? 'Choose a table to inspect its rows.'
          : rows && !rows.length
            ? 'This table is empty — nothing has been written to it in this session.'
            : dbLastRows && dbLastRows.truncated
              ? 'Showing the first ' + (rows ? rows.length : 0) + ' rows — the result was truncated.'
              : false,
        fieldsHtml: table
          ? `<div class="card-fields"><label class="field"><span>SQL statement</span><textarea id="dbSql" data-preserve rows="2" placeholder="SELECT * FROM ${esc(table.name)} LIMIT 20"></textarea><span class="card-field-help">Runs against this database. Mutating statements need the database capability.</span></label></div>`
          : '',
        bodyHtml: cols && rows && rows.length ? dbRowsTableHtml(cols, rows, dbLastRows.rowIds) : '',
        buttons: table
          ? [
              { id: 'run', label: 'Run', icon: 'terminal', kind: canEditDatabase() ? 'primary' : 'default', disabled: !canEditDatabase(), title: canEditDatabase() ? 'Run statement' : 'Sign in and enable the database capability' },
              { id: 'copy-csv', label: 'Copy as CSV', icon: 'copy', disabled: !rows || !rows.length, title: 'Copy the shown rows as CSV' },
              { id: 'insert-row', label: 'Insert row', icon: 'plus', disabled: !canEditDatabase() || !cols, title: canEditDatabase() ? 'Fill in an INSERT statement for ' + table.name : 'Sign in and enable the database capability' },
            ]
          : [],
      },
    ]));
    wireCardGrid('dbCards', {
      onTree: (name) => loadTableRows(name),
      onButton: (id) => {
        if (id === 'run') runSql();
        else if (id === 'copy-csv') copyTableCsv();
        else if (id === 'clear-sql-result') { dbSqlResult = null; renderDbCards(); }
        else if (id === 'insert-row') insertRowScaffold(table.name, cols);
        else if (id.startsWith('del-row:')) deleteDbRow(table.name, id.slice('del-row:'.length));
      },
    });
  }
  /** Rows table with a per-row delete action, built directly (rather than through the shared
   * cardTableHtml) because that component has no notion of row actions. The delete target is
   * always the engine's own `rowid` from `rowIds` — never a displayed cell value, since sensitive
   * columns come back masked as •••• and would target the wrong row. A row with a null rowId
   * (no rowid-equivalent — e.g. WITHOUT ROWID tables) gets no delete action at all. */
  function dbRowsTableHtml(cols, rows, rowIds) {
    const editable = canEditDatabase();
    const head = cols.map((c) => `<th scope="col">${esc(c)}</th>`).join('') + (editable ? '<th scope="col"></th>' : '');
    const body = rows
      .map((row, i) => {
        const rowId = rowIds ? rowIds[i] : null;
        const cells = cols
          .map((c, ci) => {
            const v = Array.isArray(row) ? row[ci] : row[c];
            return `<td class="tone-text-${ci === 0 ? 'muted' : 'ink'}" title="${esc(v ?? '')}">${esc(v ?? '')}</td>`;
          })
          .join('');
        const action = editable
          ? `<td>${
              rowId != null
                ? `<button type="button" class="row-flag" data-card-btn="del-row:${esc(String(rowId))}" title="Delete this row" aria-label="Delete row ${esc(String(rowId))}">${icon('trash', 'ic-sm')}</button>`
                : ''
            }</td>`
          : '';
        return `<tr>${cells}${action}</tr>`;
      })
      .join('');
    return `<div class="card-table-wrap"><table class="card-table"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
  }
  async function deleteDbRow(tableName, rowId) {
    if (!canEditDatabase() || rowId === '' || rowId == null) return;
    if (!(await openConfirm('Delete row', 'Delete rowid ' + rowId + ' from ' + tableName + '? This cannot be undone.', 'Delete'))) return;
    const name = $('dbName').value;
    // tableName (from the table list) and rowId (from the rowIds column) are both server-derived,
    // but this still splices them into a raw SQL string, so neither is trusted as-is: the table
    // name is identifier-quoted (doubling any embedded `"`, standard SQL escaping) and the row id
    // is required to be a plain integer literal -- sqlite rowids always are -- before it's spliced
    // in unquoted.
    const quotedTable = '"' + String(tableName).replace(/"/g, '""') + '"';
    const rowIdNum = Number(rowId);
    if (!Number.isInteger(rowIdNum)) {
      toast('Delete failed: invalid row id.', 'error');
      return;
    }
    const sql = 'DELETE FROM ' + quotedTable + ' WHERE rowid = ' + rowIdNum + ';';
    const r = await fetch('/api/v1/database/' + encodeURIComponent(name) + '/sql', {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ sql }),
    });
    if (r.ok) {
      toast('Row deleted.');
      loadTableRows(tableName);
    } else {
      const body = await r.json().catch(() => ({}));
      toast('Delete failed: ' + r.status + (body.message ? ' — ' + body.message : ''), 'error');
    }
  }
  /** Never fabricates values to insert — just scaffolds the statement's shape (table + column
   * list) into the SQL field so the operator fills in real values before running it. */
  function insertRowScaffold(tableName, cols) {
    if (!canEditDatabase() || !cols) return;
    const sqlField = $('dbSql');
    if (!sqlField) return;
    sqlField.value = `INSERT INTO ${tableName} (${cols.join(', ')})\nVALUES (${cols.map((c) => '/* ' + c + ' */').join(', ')});`;
    sqlField.focus();
  }
  let dbSqlResult = null; // last executed statement's own result — never clobbered by table loads
  async function runSql() {
    if (!token) return;
    const name = $('dbName').value;
    const sql = ($('dbSql')?.value || '').trim();
    if (!name || !sql) return;
    const r = await fetch('/api/v1/database/' + encodeURIComponent(name) + '/sql', {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ sql }),
    });
    if (!r.ok) {
      const body = await r.json().catch(() => ({}));
      toast(
        body.code === 'DATABASE_WRITE_BLOCKED'
          ? 'Blocked — the database capability is required to run mutating statements.'
          : 'Statement failed: ' + r.status + (body.message ? ' — ' + body.message : ''),
        'error',
      );
      return;
    }
    const result = await r.json();
    dbSqlResult = { sql, columns: Array.isArray(result.columns) ? result.columns : null, rows: Array.isArray(result.rows) ? result.rows : null };
    toast('Statement executed.');
    // Refresh the table list/rows (a mutating statement may have changed them); renderDbCards
    // re-runs from loadTables and renders dbSqlResult in its own card, so it survives the refresh.
    loadTables(name);
  }
  function copyTableCsv() {
    if (!dbLastRows || !Array.isArray(dbLastRows.columns) || !Array.isArray(dbLastRows.rows)) return;
    const esc2 = (v) => '"' + String(v ?? '').replace(/"/g, '""') + '"';
    const lines = [dbLastRows.columns.map(esc2).join(',')].concat(
      dbLastRows.rows.map((row) => dbLastRows.columns.map((c, i) => esc2(Array.isArray(row) ? row[i] : row[c])).join(',')),
    );
    navigator.clipboard?.writeText(lines.join('\n')).then(() => toast((dbSelectedTable || 'Table') + ' copied as CSV'));
  }

  // ================================================================
  // Files
  // ================================================================
  const canEditFiles = () => hasSession() && filesEditable;
  let fileEntries = [];
  let filePreviewStatus = 'Choose a file to preview its contents.';
  let filePreviewContent = null; // raw text, only when preview.kind === 'TEXT'
  async function loadFileRoots() {
    if (!token) return;
    const r = await fetch('/api/v1/files', { headers: auth() });
    if (!r.ok) {
      cardsGridHtml('filesCards', [{ icon: 'folder', iconTone: 'signal', title: 'Files', lede: 'Files unavailable: ' + r.status }]);
      return;
    }
    const body = await r.json();
    filesEditable = body.editable === true;
    updateControlUi();
    fileRoots = body.data || [];
    const selected = $('fileRoot').value;
    $('fileRoot').innerHTML = '<option value="">Choose a root</option>' + fileRoots.map((root) => `<option value="${esc(root)}">${esc(root)}</option>`).join('');
    // Default to the 'files' root when nothing is selected: it holds the app's own content, whereas
    // 'cache' (alphabetically first) is usually near-empty, so it made a poor first impression.
    const root = fileRoots.includes(selected) ? selected : (fileRoots.includes('files') ? 'files' : fileRoots[0] || '');
    $('fileRoot').value = root;
    $('filePath').value = '';
    selectedFilePath = '';
    filePreviewContent = null;
    filePreviewStatus = 'Choose a file to preview its contents.';
    if (root) loadFileListing(root, '');
    else renderFilesCards();
  }
  async function loadFileListing(root, path) {
    if (!token || !root) return;
    const r = await fetch('/api/v1/files/' + encodeURIComponent(root) + '?path=' + encodeURIComponent(path || ''), { headers: auth() });
    if (!r.ok) {
      cardsGridHtml('filesCards', [{ icon: 'folder', iconTone: 'signal', title: 'Tree', lede: 'Listing unavailable: ' + r.status }]);
      return;
    }
    const listing = await r.json();
    $('filePath').value = listing.relativePath || '';
    fileEntries = listing.entries || [];
    $('filesBadge').textContent = '/' + (listing.relativePath || '');
    renderFilesCards();
  }
  async function openFileEntry(relativePath, isDirectory) {
    const root = $('fileRoot').value;
    if (!root) return;
    if (isDirectory) {
      loadFileListing(root, relativePath);
      return;
    }
    selectedFilePath = relativePath;
    filePreviewContent = null;
    filePreviewStatus = 'Loading…';
    renderFilesCards();
    const r = await fetch('/api/v1/files/' + encodeURIComponent(root) + '/preview?path=' + encodeURIComponent(relativePath), { headers: auth() });
    if (!r.ok) {
      filePreviewStatus = 'Preview unavailable: ' + r.status;
      renderFilesCards();
      return;
    }
    const preview = await r.json();
    if (preview.kind === 'TEXT') {
      filePreviewContent = preview.content + (preview.truncated ? '\n…(truncated)' : '');
      filePreviewStatus = false;
    } else {
      filePreviewContent = null;
      filePreviewStatus = preview.kind === 'BINARY' ? `[binary, ${preview.sizeBytes} bytes]` : 'Unavailable: ' + preview.reason;
    }
    renderFilesCards();
  }
  async function deleteSelectedFile() {
    if (!canEditFiles() || !selectedFilePath) return;
    const root = $('fileRoot').value;
    if (!root) return;
    if (!(await openConfirm('Delete file', 'Delete ' + selectedFilePath + '?', 'Delete'))) return;
    const path = selectedFilePath;
    const r = await fetch('/api/v1/files/' + encodeURIComponent(root) + '?path=' + encodeURIComponent(path), { method: 'DELETE', headers: controlHeaders() });
    if (r.ok) {
      selectedFilePath = '';
      filePreviewContent = null;
      filePreviewStatus = 'File deleted.';
      toast('File deleted.');
      loadFileListing(root, $('filePath').value);
    } else {
      filePreviewStatus = 'Delete failed: ' + r.status;
      renderFilesCards();
    }
  }
  async function createFile() {
    if (!canEditFiles()) return;
    const root = $('fileRoot').value;
    const path = $('fileTargetPath')?.value.trim();
    if (!root || !path) return;
    const body = new URLSearchParams({ path, content: $('fileEditor')?.value || '' });
    const r = await fetch('/api/v1/files/' + encodeURIComponent(root), {
      method: 'PUT',
      headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    });
    if (r.ok) {
      toast('File created.');
      loadFileListing(root, $('filePath').value);
    } else {
      const b = await r.json().catch(() => ({}));
      toast('Create failed: ' + r.status + (b.code ? ' — ' + b.code : ''), 'error');
    }
  }
  async function saveFileContent() {
    if (!canEditFiles() || !selectedFilePath) return;
    const root = $('fileRoot').value;
    if (!root) return;
    const body = new URLSearchParams({ path: selectedFilePath, content: $('fileEditor')?.value || '' });
    const r = await fetch('/api/v1/files/' + encodeURIComponent(root), {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    });
    toast(r.ok ? 'File saved.' : 'Save failed: ' + r.status, r.ok ? undefined : 'error');
  }
  async function renameSelectedFile() {
    if (!canEditFiles() || !selectedFilePath) return;
    const root = $('fileRoot').value;
    const newPath = $('fileTargetPath')?.value.trim();
    if (!root || !newPath) return;
    const body = new URLSearchParams({ path: selectedFilePath, newPath });
    const r = await fetch('/api/v1/files/' + encodeURIComponent(root) + '/rename', {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    });
    if (r.ok) {
      selectedFilePath = newPath;
      toast('File renamed.');
      loadFileListing(root, $('filePath').value);
    } else {
      const b = await r.json().catch(() => ({}));
      toast('Rename failed: ' + r.status + (b.code ? ' — ' + b.code : ''), 'error');
    }
  }
  async function downloadSelectedFile() {
    if (!canEditFiles() || !selectedFilePath) return;
    const root = $('fileRoot').value;
    if (!root) return;
    const r = await fetch('/api/v1/files/' + encodeURIComponent(root) + '/download?path=' + encodeURIComponent(selectedFilePath), { headers: auth() });
    if (!r.ok) {
      toast('Download failed: ' + r.status, 'error');
      return;
    }
    const blob = await r.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = selectedFilePath.split('/').pop() || 'download';
    a.click();
    URL.revokeObjectURL(url);
  }
  function renderFilesCards() {
    const base = $('filePath').value || '';
    const indent = 8 + (base ? base.split('/').filter(Boolean).length : 0) * 16;
    cardsGridHtml('filesCards', [
      {
        icon: 'folder', iconTone: 'signal', title: 'Tree', badge: fileEntries.length + ' entries', badgeTone: 'muted',
        lede: fileEntries.length ? false : 'This directory is empty.',
        tree: fileEntries.map((e) => ({
          id: e.relativePath, label: e.name + (e.isDirectory ? '/' : ''), icon: e.isDirectory ? 'folder' : 'file',
          meta: e.isDirectory ? '' : (e.sizeBytes < 1024 ? e.sizeBytes + ' B' : (e.sizeBytes / 1024).toFixed(1) + ' kB'),
          indent, selected: e.relativePath === selectedFilePath, tone: e.relativePath === selectedFilePath ? 'ink' : 'muted',
        })),
      },
      {
        icon: 'file', iconTone: 'signal', title: selectedFilePath || 'Preview', span: 2,
        badge: selectedFilePath ? (canEditFiles() ? 'editable' : 'read-only') : false, badgeTone: canEditFiles() ? 'signal' : 'warn',
        lede: filePreviewStatus || false,
        code: filePreviewContent != null ? filePreviewContent.split('\n').map((line) => ({ pad: '', k: '', v: line, cls: 'tone-text-ink' })) : false,
        fieldsHtml: `<div class="card-fields" style="margin-top:12px">
          <label class="field"><span>Content (create / save changes)</span><textarea id="fileEditor" rows="6" placeholder="Text content for create / save changes">${esc(filePreviewContent || '')}</textarea></label>
          <label class="field"><span>New / rename target</span><input id="fileTargetPath" placeholder="relative/path.txt"></label>
        </div>`,
        buttons: [
          { id: 'rename', label: 'Rename', icon: 'pencil', disabled: !canEditFiles() || !selectedFilePath, title: canEditFiles() ? 'Rename to the target path above' : 'Sign in and enable the files capability' },
          { id: 'save', label: 'Save changes', icon: 'check', kind: canEditFiles() ? 'primary' : 'default', disabled: !canEditFiles() || !selectedFilePath, title: canEditFiles() ? 'Save changes' : 'Sign in and enable the files capability' },
          { id: 'download', label: 'Download', icon: 'download', disabled: !canEditFiles() || !selectedFilePath, title: canEditFiles() ? 'Download' : 'Sign in and enable the files capability' },
          { id: 'delete', label: 'Delete', icon: 'trash', kind: 'danger', disabled: !canEditFiles() || !selectedFilePath, title: canEditFiles() ? 'Delete' : 'Sign in and enable the files capability' },
        ],
      },
    ]);
    wireCardGrid('filesCards', {
      onTree: (path) => {
        const entry = fileEntries.find((e) => e.relativePath === path);
        if (entry) openFileEntry(path, entry.isDirectory);
      },
      onButton: (id) => {
        if (id === 'save') saveFileContent();
        else if (id === 'rename') renameSelectedFile();
        else if (id === 'download') downloadSelectedFile();
        else if (id === 'delete') deleteSelectedFile();
      },
    });
  }

  // ================================================================
  // SDK health
  // ================================================================
  async function loadSdkHealth() {
    if (!token) return;
    const [healthRes, metaRes, overviewRes] = await Promise.all([
      fetch('/api/v1/sdk-health', { headers: auth() }),
      fetch('/api/v1/meta', { headers: auth() }),
      fetch('/api/v1/overview', { headers: auth() }),
    ]);
    if (!healthRes.ok) {
      renderEmpty($('sdkHealthDetail'), 'health', 'Health unavailable: ' + healthRes.status);
      return;
    }
    const health = await healthRes.json();
    const meta = metaRes.ok ? await metaRes.json() : {};
    const csp = metaRes.ok ? metaRes.headers.get('Content-Security-Policy') : null;
    const integrity = overviewRes.ok ? (await overviewRes.json()).sessionIntegrity : null;
    $('sdkHealthMetrics').innerHTML = metricsStripHtml([
      { label: 'State', val: health.state || 'UNKNOWN', tone: health.state === 'RUNNING' ? 'signal' : 'muted' },
      { label: 'Published', val: String(health.publishedEventCount ?? 0), sub: 'events', tone: 'ink' },
      { label: 'Dropped', val: String(health.droppedEventCount ?? 0), tone: health.droppedEventCount > 0 ? 'warn' : 'ink' },
      { label: 'Principals', val: String(health.activePrincipalCount ?? 0), tone: 'ink' },
    ]);
    cardsGridHtml('sdkHealthDetail', [
      { icon: 'health', iconTone: 'signal', title: 'Runtime', metrics: [
        { label: 'Init count', val: String(health.initializationCount ?? 0), tone: 'ink' },
        { label: 'Active principals', val: String(health.activePrincipalCount ?? 0), tone: 'ink' },
      ] },
      { icon: 'grid', iconTone: 'signal', title: 'Protocol', rows: [
        { k: 'Protocol', v: meta.protocolVersion ? 'devconsole/' + meta.protocolVersion : '—', tone: 'ink' },
        { k: 'Transport', v: 'ws + http (same origin)', tone: 'muted' },
        { k: 'CSP', v: csp || 'not reported', tone: 'muted', tag: csp ? 'ENFORCED' : false, tagTone: 'signal' },
        { k: 'Capabilities', v: (meta.capabilities || []).join(', ') || '—', tone: 'muted' },
      ] },
      integrity
        ? { icon: 'shield', iconTone: 'warn', title: 'Session integrity', lede: 'Guardrail counters the SDK tracks for this session — not fabricated response-time budgets, since none are reported by the on-device SDK.', metrics: [
            { label: 'Active mocks', val: String((integrity.activeMockRuleIds || []).length), tone: 'put' },
            { label: 'Flag overrides', val: String(Object.keys(integrity.featureFlagOverrides || {}).length), tone: 'ink' },
            { label: 'State mutations', val: String(integrity.stateMutationCount ?? 0), tone: integrity.stateMutationCount ? 'warn' : 'ink' },
          ] }
        : false,
    ].filter(Boolean));
  }

  // ================================================================
  // Session & security. The 7-row Capabilities block needs EditingCapabilities, which is never
  // serialized as one JSON object; this view assembles the same seven rows from real per-feature
  // probes instead (each endpoint's own `editable` bit, or a reactive 404 for composer).
  // ================================================================
  let sessionPrincipals = [];
  // Populated only after an explicit "Rotate code now" — the server never surfaces the live
  // session code to an already-authenticated browser any other way, so there is nothing to show
  // until the user asks for a fresh one.
  let rotatedSessionCode = null;
  let lastSessionMeta = {};
  let lastSessionCapabilities = {};
  async function rotateSessionCode() {
    if (!hasSession()) return;
    if (!(await openConfirm('Rotate session code?', 'This immediately invalidates the current session code. Any device that has not yet connected with it will need the new one.', 'Rotate code')))
      return;
    const r = await fetch('/api/v1/auth/session-code/rotate', { method: 'POST', headers: controlHeaders() });
    if (!r.ok) { toast('Could not rotate session code.', 'error'); return; }
    rotatedSessionCode = await r.json();
    toast('Session code rotated.');
    renderSessionCards(lastSessionMeta, lastSessionCapabilities);
  }
  let retainedRuns = [];
  async function loadSession() {
    if (!token) return;
    const [metaRes, principalsRes, prefsRes, dbRes, filesRes, mocksRes, captureRes, flagsRes, composerRes, runsRes] = await Promise.all([
      fetch('/api/v1/meta', { headers: auth() }),
      fetch('/api/v1/auth/principals', { headers: auth() }),
      fetch('/api/v1/preferences', { headers: auth() }),
      fetch('/api/v1/database', { headers: auth() }),
      fetch('/api/v1/files', { headers: auth() }),
      fetch('/api/v1/mocks/rules', { headers: auth() }),
      fetch('/api/v1/capture-rules', { headers: auth() }),
      fetch('/api/v1/flags', { headers: auth() }),
      fetch('/api/v1/composer/collections', { headers: auth() }),
      fetch('/api/v1/runs', { headers: auth() }),
    ]);
    const meta = metaRes.ok ? await metaRes.json() : {};
    sessionPrincipals = principalsRes.ok ? (await principalsRes.json()).data || [] : [];
    const flags = flagsRes.ok ? (await flagsRes.json()).data || [] : [];
    const capabilities = {
      preferences: prefsRes.ok && (await prefsRes.json()).editable === true,
      database: dbRes.ok && (await dbRes.json()).editable === true,
      files: filesRes.ok && (await filesRes.json()).editable === true,
      mocks: mocksRes.ok && (await mocksRes.json()).editable === true,
      captureRules: captureRes.ok && (await captureRes.json()).editable === true,
      featureFlags: flags.some((f) => f.mutable),
      requestExecution: composerRes.status !== 404,
    };
    retainedRuns = runsRes.ok ? (await runsRes.json()).data || [] : [];
    lastSessionMeta = meta;
    lastSessionCapabilities = capabilities;
    renderSessionCards(meta, capabilities);
  }
  async function revokePrincipal(id) {
    if (!hasSession()) return;
    const r = await fetch('/api/v1/auth/principals/' + encodeURIComponent(id), { method: 'DELETE', headers: controlHeaders() });
    if (r.ok) {
      toast('Principal revoked.');
      loadSession();
    }
  }
  async function stopServer() {
    if (!hasSession()) return;
    if (!(await openConfirm('Stop the DevConsole server?', 'All connected browsers will be disconnected.', 'Stop server'))) return;
    await fetch('/api/v1/session/stop', { method: 'POST', headers: controlHeaders() });
    toast('Server stop requested.');
  }
  function principalsTableHtml(principals) {
    const rows = principals
      .map(
        (p) => `<tr>
        <td class="tone-text-ink">${esc(p.browserLabel)}</td>
        <td class="tone-text-muted">${esc(p.sourceIp)}</td>
        <td class="tone-text-muted">${esc(time(p.expiresAtEpochMs))}</td>
        <td>${hasSession() ? `<button type="button" class="row-flag" data-revoke-principal="${esc(p.id)}" title="Revoke" aria-label="Revoke ${esc(p.browserLabel)}">${icon('close', 'ic-sm')}</button>` : ''}</td>
      </tr>`,
      )
      .join('');
    return `<div class="card-table-wrap"><table class="card-table"><thead><tr><th scope="col">Client</th><th scope="col">Address</th><th scope="col">Expires</th><th scope="col"></th></tr></thead><tbody>${rows}</tbody></table></div>`;
  }
  /** GET /api/v1/runs (7ef109d) — genuinely fills a gap this view's own hint already promised
   * ("what is retained") but never showed: which runs are on the device, whether the previous one
   * crashed, and roughly how much each is holding. Read-only, newest-first (server-sorted). */
  const RUN_STATUS_TONE = { CRASHED: 'error', ACTIVE: 'signal', DELETING: 'warn', COMPLETED: 'muted' };
  function retainedRunsTableHtml(runs) {
    return cardTableHtml({
      cols: ['Status', 'Started', 'App', 'Device', 'Records'],
      rows: runs.map((r) => [
        { v: r.status, tone: RUN_STATUS_TONE[r.status] || 'muted' },
        { v: time(r.startedAtEpochMs), tone: 'muted' },
        { v: [r.applicationId, r.appVersionName].filter(Boolean).join(' ') || '—', tone: 'ink' },
        { v: [r.deviceModel, r.deviceOsVersion].filter(Boolean).join(' · ') || '—', tone: 'muted' },
        { v: r.recordCount != null ? String(r.recordCount) : '—', tone: 'ink' },
      ]),
    });
  }
  function renderSessionCards(meta, capabilities) {
    const endpoint = meta.endpoint;
    const lan = endpoint?.bindingMode === 'LAN';
    const capRows = [
      ['preferences', capabilities.preferences], ['database', capabilities.database], ['files', capabilities.files],
      ['mocks', capabilities.mocks], ['captureRules', capabilities.captureRules], ['featureFlags', capabilities.featureFlags],
      ['requestExecution', capabilities.requestExecution],
    ];
    const capOnCount = capRows.filter(([, on]) => on).length;
    $('sessionMetrics').innerHTML = metricsStripHtml([
      { label: 'Browsers', val: String(sessionPrincipals.length + 1), sub: 'incl. this one', tone: 'ink' },
      { label: 'Capabilities', val: String(capOnCount), sub: 'of ' + capRows.length + ' on', tone: capOnCount ? 'signal' : 'warn' },
      { label: 'Build', val: meta.build?.variant || '—', tone: 'ink' },
      { label: 'Protocol', val: meta.protocolVersion ? 'v' + meta.protocolVersion : '—', tone: 'muted' },
    ]);
    cardsGridHtml('sessionCards', [
      {
        icon: 'alert', iconTone: lan ? 'warn' : 'signal', title: lan ? 'This console is reachable on your LAN' : 'This console is bound to the loopback interface', span: 2,
        lede: lan
          ? 'Anyone on this network who has the session code can read every capture in this session, including redacted-but-present metadata. Debug builds only — the SDK refuses to start in release.'
          : 'Only this device can reach the console — the SDK bound to loopback rather than a LAN-visible address.',
        rows: [
          { k: 'Bound address', v: endpoint ? endpoint.host + ':' + endpoint.port : '—', tone: 'ink', tag: endpoint?.bindingMode || false, tagTone: lan ? 'warn' : 'signal' },
          { k: 'Transport', v: 'http + ws, no TLS', tone: 'warn', tag: 'PLAINTEXT', tagTone: 'warn' },
          { k: 'Auth', v: 'rotating session code', tone: 'signal' },
          { k: 'Build type', v: meta.build?.variant || 'unknown', tone: meta.build?.variant === 'debug' ? 'signal' : 'muted', tag: meta.build?.variant === 'debug' ? 'GUARDED' : false, tagTone: 'signal' },
        ].concat(
          rotatedSessionCode
            ? [
                { k: 'Rotated code', v: rotatedSessionCode.code, tone: 'signal', tag: 'NEW', tagTone: 'signal' },
                { k: 'Link for other device', v: rotatedSessionCode.browserUrl, tone: 'ink' },
                { k: 'Code expires', v: time(rotatedSessionCode.expiresAtEpochMs), tone: 'muted' },
              ]
            : [],
        ),
        buttons: [
          { id: 'end-session', label: 'End session', icon: 'trash', kind: 'danger', disabled: !hasSession(), title: hasSession() ? 'End session' : 'Connect this browser first' },
          { id: 'rotate-code', label: 'Rotate code now', icon: 'refresh', disabled: !hasSession(), title: hasSession() ? 'Invalidate the current session code and mint a new one' : 'Connect this browser first' },
        ],
      },
      {
        icon: 'eye', iconTone: 'signal', title: 'Connected browsers', badge: sessionPrincipals.length + ' other', badgeTone: 'muted',
        lede: sessionPrincipals.length ? false : 'No other browsers are connected to this session.',
        bodyHtml: sessionPrincipals.length ? principalsTableHtml(sessionPrincipals) : '',
      },
      {
        icon: 'database', iconTone: retainedRuns.some((r) => r.status === 'CRASHED') ? 'error' : 'signal', title: 'Retained runs', span: 2,
        badge: retainedRuns.length + ' run' + (retainedRuns.length === 1 ? '' : 's'), badgeTone: 'muted',
        lede: retainedRuns.length ? false : 'No retained runs on this device yet.',
        bodyHtml: retainedRuns.length ? retainedRunsTableHtml(retainedRuns) : '',
      },
      {
        icon: 'lock', iconTone: 'signal', title: 'Capabilities', badge: capOnCount + ' of ' + capRows.length + ' on', badgeTone: capOnCount ? 'signal' : 'warn',
        rows: capRows.map(([k, on]) => ({ k, v: on ? 'on' : 'off', tone: on ? 'signal' : 'warn', tag: on ? 'WRITE' : 'BLOCKED', tagTone: on ? 'signal' : 'warn' })),
      },
      {
        icon: 'download', iconTone: 'signal', title: 'Export & retention',
        lede: 'Redaction is applied before anything leaves the device.',
        fieldsHtml: `<div class="card-fields">
          <label class="field"><span>Scope</span><select id="exportScope"><option value="WHOLE_SESSION">Whole session</option><option value="TIME_RANGE">Inclusive time range</option><option value="EVENT_IDS">Selected event IDs</option></select></label>
          <label class="field" id="exportFromField" hidden><span>From epoch ms</span><input id="exportFrom" type="number" min="0"></label>
          <label class="field" id="exportToField" hidden><span>To epoch ms</span><input id="exportTo" type="number" min="0"></label>
          <label class="field" id="exportIdsField" hidden><span>Event IDs (comma separated)</span><textarea id="exportIds" rows="2"></textarea></label>
          <label class="field" style="flex-direction:row;align-items:center;gap:7px"><input id="exportMetadataOnly" type="checkbox" style="width:auto"><span style="text-transform:none;font-size:12px">Metadata only</span></label>
        </div>
        <pre id="exportStatus" aria-live="polite" style="margin-top:8px">${esc(hasSession() ? 'Choose a scope, then create a redacted ZIP.' : 'Connect this browser to create a diagnostic export.')}</pre>`,
        buttons: [
          { id: 'export-estimate', label: 'Estimate size', icon: 'grid', disabled: !hasSession(), title: 'Check the ZIP size for the current scope before creating it' },
          { id: 'export-create', label: 'Session ZIP', icon: 'download', kind: hasSession() ? 'primary' : 'default', disabled: !hasSession() },
          { id: 'export-har', label: 'HAR', icon: 'download', disabled: !hasSession() },
          { id: 'export-postman', label: 'Postman', icon: 'download', disabled: !hasSession() },
        ],
      },
    ]);
    $('exportScope').onchange = updateExportScope;
    updateExportScope();
    wireCardGrid('sessionCards', {
      onButton: (id) => {
        if (id === 'end-session') stopServer();
        else if (id === 'rotate-code') rotateSessionCode();
        else if (id === 'export-estimate') estimateExportSize();
        else if (id === 'export-create') createExport();
        else if (id === 'export-har') downloadHar();
        else if (id === 'export-postman') downloadPostman();
      },
    });
    $('sessionCards').querySelectorAll('[data-revoke-principal]').forEach((btn) => { btn.onclick = () => revokePrincipal(btn.dataset.revokePrincipal); });
  }
  function updateExportScope() {
    const scope = $('exportScope').value;
    $('exportFromField').hidden = scope !== 'TIME_RANGE';
    $('exportToField').hidden = scope !== 'TIME_RANGE';
    $('exportIdsField').hidden = scope !== 'EVENT_IDS';
  }
  /** Shared by createExport, estimateExportSize, and (implicitly) the Evidence tray's "Export
   * session ZIP" button, which has no form of its own — the exportScope/exportMetadataOnly
   * fields only exist once the Session view has rendered, so this falls back to a plain
   * whole-session scope when called without them. */
  function exportParams() {
    const scope = $('exportScope')?.value || 'WHOLE_SESSION';
    const metadataOnly = $('exportMetadataOnly')?.checked || false;
    const values = new URLSearchParams({ scope, metadataOnly: String(metadataOnly) });
    if (scope === 'TIME_RANGE') {
      values.set('fromEpochMs', $('exportFrom').value);
      values.set('toEpochMs', $('exportTo').value);
    }
    if (scope === 'EVENT_IDS')
      $('exportIds')
        .value.split(',')
        .map((value) => value.trim())
        .filter(Boolean)
        .forEach((id) => values.append('eventId', id));
    return values;
  }
  /** Shows the ZIP's estimated size before the operator commits to building it — the same
   * scope/metadataOnly/fromEpochMs/toEpochMs/eventId params createExport() sends, against the
   * read-only estimate endpoint. */
  async function estimateExportSize() {
    if (!token) return;
    const status = $('exportStatus');
    if (status) { status.dataset.busy = '1'; status.textContent = 'Estimating…'; }
    const r = await fetch('/api/v1/exports/estimate?' + exportParams(), { headers: auth() });
    if (status) delete status.dataset.busy;
    if (!r.ok) {
      if (status) status.textContent = 'Estimate failed: ' + r.status;
      return;
    }
    const body = await r.json();
    if (status) status.textContent = 'Estimated export size: ' + formatBytes(body.estimatedBytes) + '. Choose a scope, then create a redacted ZIP.';
  }
  async function createExport() {
    if (!token) return;
    const metadataOnly = $('exportMetadataOnly')?.checked || false;
    if (!metadataOnly && !(await openConfirm('Create diagnostic export', 'This creates a full redacted diagnostic ZIP. Review it before sharing; binary attachments may still be sensitive.', 'Create ZIP')))
      return;
    const values = exportParams();
    const btn = document.querySelector('[data-card-btn="export-create"]');
    const status = $('exportStatus'); // absent when called from the Evidence tray — status text is optional
    if (btn) btn.disabled = true;
    if (status) { status.dataset.busy = '1'; status.textContent = 'Building, hashing, and compressing the export…'; }
    const r = await fetch('/api/v1/exports', {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body: values,
    });
    if (!r.ok) {
      let error;
      try {
        error = await r.json();
      } catch {
        error = { code: 'EXPORT_FAILED' };
      }
      // EXPORT_TOO_LARGE carries both estimatedBytes and maxBytes — say exactly how far over the
      // limit the real export is, not just that it failed.
      const overBy = error.code === 'EXPORT_TOO_LARGE' && error.estimatedBytes != null && error.maxBytes != null
        ? ' — estimated ' + formatBytes(error.estimatedBytes) + ', ' + formatBytes(error.estimatedBytes - error.maxBytes) + ' over the ' + formatBytes(error.maxBytes) + ' limit'
        : '';
      if (status) { delete status.dataset.busy; status.textContent = [error.code, error.guidance].filter(Boolean).join(' · ') + overBy; }
      toast('Export failed: ' + (error.code || r.status) + overBy, 'error');
      if (btn) btn.disabled = false;
      return;
    }
    const blob = await r.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'devconsole-export.zip';
    a.click();
    URL.revokeObjectURL(url);
    if (status) { delete status.dataset.busy; status.textContent = 'Export ready. Verify manifest.json before sharing.'; }
    toast('Export downloaded.');
    if (btn) btn.disabled = false;
  }

  // ================================================================
  // Evidence tray -- durable, server-persisted per session (`GET/POST/DELETE /api/v1/evidence`,
  // `PUT /api/v1/evidence/report`). Every item renders from its own `snapshot`, materialized by
  // the server once, at flag time, from the same already-redacted sources the detail endpoints
  // use (DevConsoleKtorModule's materializeEvidenceSubject) -- never re-derived from whatever this
  // browser's live views currently hold. That replaces the old best-effort re-derivation (the
  // removed evidenceAttachmentId() above, formerly around dashboard.js:3897) where a flagged
  // network transaction degraded to a bare label once the Network list moved on.
  //
  // The report draft (severity/summary/expected/actual) autosaves debounced ~750ms after the last
  // keystroke and immediately on blur -- see wireEvidenceReportAutosave -- so a browser refresh
  // loses neither the flagged items nor the typed report text.
  // ================================================================
  let evidenceReport = { severity: 'MAJOR', summary: '', expected: '', actual: '', updatedAtMs: 0 };
  let evidenceReportSaveTimer = null;
  const EVIDENCE_SEVERITIES = ['BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR', 'TRIVIAL'];
  const EVIDENCE_SEVERITY_SUB = {
    BLOCKER: 'Blocks the release train', CRITICAL: 'Severe, no workaround',
    MAJOR: 'Core flow degraded, workaround exists', MINOR: 'Cosmetic or edge case', TRIVIAL: 'Nitpick',
  };
  const evidenceSeverityLabel = (s) => (s ? s.charAt(0) + s.slice(1).toLowerCase() : 'Major');
  // Static description of each snapshot kind's shape -- the server materializes every one of
  // these fields at flag time now, so this is a schema description, not a data value; nothing
  // here is fabricated per-item (that honesty rule lives in evidenceItemStatus below).
  const evidenceIncludedByKind = {
    network: 'method, url, status, headers, body, timing', timeline: 'summary, level, source, payload (when the plugin emits one)',
    socket: 'connection, direction, frame type, payload', push: 'provider, lifecycle, data, notification',
    screenshot: 'width, height, image attachment', crash: 'kind, thread, all-thread dump, breadcrumbs',
  };
  /** Reads whatever the item's own persisted snapshot actually has -- never a live lookup into
   * networkPage/events/etc. Absent fields render as "—", never a guess. */
  function evidenceItemStatus(it) {
    const s = it.snapshot;
    if (!s) return { text: '—', tone: 'muted' };
    if (it.kind === 'network') return { text: s.status ? String(s.status) : s.error || 'no response', tone: statusTone(s.status) };
    if (it.kind === 'timeline') { const lvl = SEVERITY_SHORT[s.severity] || ''; return { text: lvl || s.type || '—', tone: levelTone(lvl) }; }
    if (it.kind === 'socket') return { text: (s.direction || '') + ' ' + (s.frameType || ''), tone: 'muted' };
    if (it.kind === 'push') return { text: s.lifecycle || '—', tone: 'muted' };
    if (it.kind === 'screenshot') return { text: s.widthPx && s.heightPx ? s.widthPx + '×' + s.heightPx : '—', tone: 'put' };
    if (it.kind === 'crash') return { text: s.kind || '—', tone: 'error' };
    return { text: '—', tone: 'muted' };
  }
  /** Thumbnail eligibility only -- "is this attachment an image worth rendering as `<img>`", not
   * "is it unredacted". A SCREENSHOT-kind flag, or a TIMELINE-kind flag whose snapshot is itself a
   * "screenshot" plugin event (flagged from the Timeline row instead of the dedicated capture
   * flow), are the only shapes with an image attachment. The UNREDACTED badge itself is a separate,
   * authoritative question answered by `it.redactionApplicability` below -- never inferred from
   * kind (7ef109d: GET /api/v1/evidence items now carry that field, looked up live). */
  function evidenceItemIsScreenshot(it) {
    return it.kind === 'screenshot' || (it.kind === 'timeline' && it.snapshot?.pluginId === 'screenshot');
  }
  /** `it.redactionApplicability` is `'APPLIED'`, `'NOT_APPLICABLE'`, or `null`/absent when unknown
   * (the reader was unwired, or the attachment row is gone) -- badge only the confirmed case,
   * exactly per the field's contract: absent means "don't know", not "assume safe". */
  const isUnredacted = (it) => it.redactionApplicability === 'NOT_APPLICABLE';
  function evidenceAttachmentsTableHtml(items) {
    const head = ['Kind', 'Subject', 'Result', 'Flagged', 'Included', ''].map((c) => `<th scope="col">${esc(c)}</th>`).join('');
    const body = items
      .map((it) => {
        const status = evidenceItemStatus(it);
        const unredacted = isUnredacted(it);
        const kindCell = `<td class="tone-text-muted" title="${esc(it.kind)}">${esc(it.kind)}${
          unredacted ? ' <span class="badge-pill badge-error" title="Reported unredacted by the server (redactionApplicability=NOT_APPLICABLE)">UNREDACTED</span>' : ''
        }</td>`;
        const cells = [
          kindCell,
          `<td class="tone-text-ink" title="${esc(it.label)}">${esc(it.label)}</td>`,
          `<td class="tone-text-${status.tone}" title="${esc(status.text)}">${esc(status.text)}</td>`,
          `<td class="tone-text-muted">${esc(time(it.flaggedAtMs))}</td>`,
          `<td class="tone-text-muted" title="${esc(evidenceIncludedByKind[it.kind] || '—')}">${esc(evidenceIncludedByKind[it.kind] || '—')}</td>`,
        ].join('');
        const download = it.attachmentId
          ? `<button type="button" class="row-flag" data-card-btn="download-attachment:${esc(it.attachmentId)}" title="Download attachment" aria-label="Download attachment for ${esc(it.label)}">${icon('download', 'ic-sm')}</button>`
          : '';
        const unflag = `<button type="button" class="row-flag" data-card-btn="unflag:${esc(it.kind)}:${esc(it.id)}" title="Remove from evidence" aria-label="Remove ${esc(it.label)} from evidence">${icon('close', 'ic-sm')}</button>`;
        return `<tr>${cells}<td>${download}${unflag}</td></tr>`;
      })
      .join('');
    return `<div class="card-table-wrap"><table class="card-table"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
  }
  /** Populates the `evidenceFlags` cache and `evidenceReport` draft from the server's persisted
   * state -- the one place either is ever assigned. Called on connect (so every row's flag button
   * and the rail count are correct before the Evidence tray is ever opened) and by loadEvidence()
   * (the view loader) so a browser refresh recovers exactly what the server has, nothing
   * re-derived and nothing lost. */
  async function loadEvidenceFlags() {
    if (!token) { evidenceFlags.clear(); updateEvidenceUi(); return { items: [], report: evidenceReport }; }
    const r = await fetch('/api/v1/evidence', { headers: auth() });
    if (!r.ok) return { items: [...evidenceFlags.values()], report: evidenceReport };
    const body = await r.json();
    const items = body.data || [];
    evidenceFlags.clear();
    items.forEach((it) => {
      const kind = String(it.kind).toLowerCase();
      evidenceFlags.set(evidenceKey(kind, it.subjectId), {
        kind, id: it.subjectId, label: it.label, flaggedAtMs: it.flaggedAtMs,
        snapshot: it.snapshot, attachmentId: it.attachmentId, itemId: it.id,
        redactionApplicability: it.redactionApplicability ?? null,
      });
    });
    if (body.report) evidenceReport = body.report;
    updateEvidenceUi();
    return { items: [...evidenceFlags.values()], report: evidenceReport };
  }
  async function loadEvidence() {
    const { items } = await loadEvidenceFlags();
    let environment = null;
    if (hasSession()) {
      const r = await fetch('/api/v1/overview', { headers: auth() });
      if (r.ok) environment = (await r.json()).app;
    }
    renderEvidence(items, environment);
  }
  /** Steps-to-reproduce / Actual prefill picks the single most useful item to summarize: a crash
   * outranks everything (it is always the reason a session gets flagged), then the first failing
   * network capture, else whatever was flagged first. Prefill only ever *seeds* the Summary/Actual
   * inputs (via their `value=` attribute) -- it never overwrites `evidenceReport` itself, so it
   * can't clobber text the reporter already saved. */
  function pickWorstEvidenceItem(items) {
    return (
      items.find((it) => it.kind === 'crash') ||
      items.find((it) => it.kind === 'network' && it.snapshot && (!it.snapshot.status || it.snapshot.status >= 400)) ||
      items[0]
    );
  }
  /** Reads the live inputs when the fields are on screen (the most current text the reporter is
   * looking at, including anything not yet autosaved), falling back to the last-persisted
   * `evidenceReport` otherwise -- shared by every clipboard formatter below so "copy now" always
   * reflects exactly what's on screen. */
  function currentReportFields() {
    return {
      severity: evidenceReport.severity || 'MAJOR',
      summary: ($('evidenceSummary')?.value ?? evidenceReport.summary ?? '').trim(),
      expected: ($('evidenceExpected')?.value ?? evidenceReport.expected ?? '').trim(),
      actual: ($('evidenceActual')?.value ?? evidenceReport.actual ?? '').trim(),
    };
  }
  function evidenceItemLine(it) {
    const status = evidenceItemStatus(it);
    return '`' + it.kind + '` ' + it.label + (status.text && status.text !== '—' ? ' → ' + status.text : '');
  }
  // ---- Clipboard formats (C): Markdown (existing, repointed at persisted state), Jira wiki
  // markup, and a GitHub issue format with collapsible <details> blocks for the stack trace and
  // raw payloads. All three are client-side string builders -- no egress, nothing sent anywhere.
  function evidenceMarkdown(items, environment) {
    const f = currentReportFields();
    const lines = ['# Bug report', ''];
    lines.push('**Severity:** ' + evidenceSeverityLabel(f.severity), '');
    lines.push('**Summary:** ' + (f.summary || 'Untitled'), '');
    if (f.expected) lines.push('**Expected:** ' + f.expected, '');
    if (f.actual) lines.push('**Actual:** ' + f.actual, '');
    if (environment) lines.push('**Environment:** ' + environment.packageName + ' ' + environment.versionName, '');
    lines.push('## Attachments (' + items.length + ')');
    items.forEach((it) => lines.push('- ' + evidenceItemLine(it)));
    return lines.join('\n');
  }
  function evidenceJira(items, environment) {
    const f = currentReportFields();
    const lines = ['h2. Bug report', ''];
    lines.push('*Severity:* ' + evidenceSeverityLabel(f.severity));
    lines.push('*Summary:* ' + (f.summary || 'Untitled'));
    if (f.expected) lines.push('*Expected:* ' + f.expected);
    if (f.actual) lines.push('*Actual:* ' + f.actual);
    if (environment) lines.push('*Environment:* ' + environment.packageName + ' ' + environment.versionName);
    lines.push('', 'h2. Attachments (' + items.length + ')', '', '||Kind||Subject||Result||');
    items.forEach((it) => { const status = evidenceItemStatus(it); lines.push('|' + it.kind + '|' + it.label + '|' + (status.text || '—') + '|'); });
    const crash = items.find((it) => it.kind === 'crash' && it.snapshot?.payload?.stackTrace);
    if (crash) lines.push('', 'h2. All-thread dump (' + crash.label + ')', '', '{code}' + crash.snapshot.payload.stackTrace + '{code}');
    return lines.join('\n');
  }
  function evidenceGithub(items, environment) {
    const f = currentReportFields();
    const lines = ['## Bug report', ''];
    lines.push('**Severity:** ' + evidenceSeverityLabel(f.severity), '');
    lines.push('**Summary:** ' + (f.summary || 'Untitled'));
    if (f.expected) lines.push('', '**Expected:** ' + f.expected);
    if (f.actual) lines.push('', '**Actual:** ' + f.actual);
    if (environment) lines.push('', '**Environment:** ' + environment.packageName + ' ' + environment.versionName);
    lines.push('', '## Attachments (' + items.length + ')', '');
    items.forEach((it) => lines.push('- ' + evidenceItemLine(it)));
    const crash = items.find((it) => it.kind === 'crash' && it.snapshot?.payload);
    if (crash) {
      lines.push(
        '', `<details><summary>All-thread dump (${esc(crash.label)})</summary>`, '',
        '```', crash.snapshot.payload.stackTrace || '', '```', '', '</details>',
      );
    }
    const payloads = items.filter((it) => it.kind !== 'crash' && it.snapshot);
    if (payloads.length) {
      lines.push(
        '', '<details><summary>Raw payloads</summary>', '', '```json',
        JSON.stringify(payloads.map((it) => ({ kind: it.kind, label: it.label, snapshot: it.snapshot })), null, 2),
        '```', '', '</details>',
      );
    }
    return lines.join('\n');
  }
  async function copyEvidenceMarkdown(items, environment) { await copyToClipboard(evidenceMarkdown(items, environment), 'Bug report (Markdown)'); }
  async function copyEvidenceJira(items, environment) { await copyToClipboard(evidenceJira(items, environment), 'Bug report (Jira)'); }
  async function copyEvidenceGithub(items, environment) { await copyToClipboard(evidenceGithub(items, environment), 'Bug report (GitHub)'); }
  async function copyEvidenceCurl(items) {
    const ids = items.filter((it) => it.kind === 'network').map((it) => it.id);
    if (!ids.length) { toast('No flagged network transactions to build cURL from', 'error'); return; }
    const results = await Promise.all(
      ids.map((id) => fetch('/api/v1/network/transactions/' + encodeURIComponent(id) + '/curl', { headers: auth() }).then((r) => (r.ok ? r.text() : null))),
    );
    const text = results.filter(Boolean).join('\n\n');
    if (!text) { toast('cURL unavailable for the flagged transactions', 'error'); return; }
    await copyToClipboard(text, results.filter(Boolean).length + ' capture(s) as cURL');
  }
  async function downloadBlobResponse(r, filename) {
    if (!r.ok) return false;
    const blob = await r.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
    return true;
  }
  function flaggedNetworkIds(items) {
    return items.filter((it) => it.kind === 'network').map((it) => it.id);
  }
  async function downloadEvidenceHar(items) {
    const ids = flaggedNetworkIds(items);
    if (!ids.length) { toast('No flagged network transactions to export', 'error'); return; }
    const params = new URLSearchParams();
    ids.forEach((id) => params.append('id', id));
    const r = await fetch('/api/v1/network/har?' + params, { headers: auth() });
    if (!(await downloadBlobResponse(r, 'devconsole-evidence.har'))) toast('HAR export failed: ' + r.status, 'error');
  }
  async function downloadEvidencePostman(items) {
    const ids = flaggedNetworkIds(items);
    if (!ids.length) { toast('No flagged network transactions to export', 'error'); return; }
    const params = new URLSearchParams();
    ids.forEach((id) => params.append('id', id));
    const r = await fetch('/api/v1/network/postman?' + params, { headers: auth() });
    if (!(await downloadBlobResponse(r, 'devconsole-evidence.postman_collection.json'))) toast('Postman export failed: ' + r.status, 'error');
  }
  async function downloadEvidenceAttachment(attachmentId) {
    if (!attachmentId) return;
    const r = await fetch('/api/v1/attachments/' + encodeURIComponent(attachmentId), { headers: auth() });
    if (!(await downloadBlobResponse(r, attachmentId))) toast('Attachment download failed: ' + r.status, 'error');
  }
  /** The evidence bundle -- `POST /api/v1/exports` with `scope=EVIDENCE`, the existing export
   * route (CSRF, size limits, truncation reporting, stale-export pruning all come for free). No
   * `X-DevConsole-Export-Truncated` header is set on this route (unlike the HAR/Postman bulk
   * routes), so truncation can only be read from the ZIP's own manifest.json -- same "verify
   * manifest.json" guidance the whole-session export already gives. */
  async function downloadEvidenceBundle() {
    if (!token) return;
    const r = await fetch('/api/v1/exports', {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ scope: 'EVIDENCE' }),
    });
    if (!r.ok) {
      let error; try { error = await r.json(); } catch { error = { code: 'EXPORT_FAILED' }; }
      toast('Evidence bundle export failed: ' + (error.code || r.status), 'error');
      return;
    }
    if (await downloadBlobResponse(r, 'devconsole-evidence-bundle.zip')) toast('Evidence bundle downloaded. Verify manifest.json before sharing.');
  }
  /** Attachment bytes only ever arrive via an authenticated fetch (`GET /api/v1/attachments/{id}`
   * needs the bearer header, so a bare `<img src>` can't reach it) -- cached by attachment id so a
   * re-render (severity toggle, autosave) doesn't re-fetch/re-decode the same PNG. Never evicted:
   * an evidence tray tops out at 200 items, so the worst case is 200 resident object URLs for the
   * lifetime of the tab, which is bounded and small next to what virtualization already allows
   * elsewhere.
   *
   * `redactionApplicability` here comes from the response's `X-DevConsole-Redaction-Applicability`
   * header (7ef109d) -- omitted (`null`), never defaulted, when the server doesn't know. This is
   * the single most authoritative source available (read from the exact request that reads the
   * stored row), used to reconcile the badge a caller may have already painted from a JSON field.
   *
   * Evicted by clearAttachmentInfoCache() (see clearEvidenceTray) rather than left to grow for the
   * tab's lifetime -- keyed by attachmentId, and every clear-and-recapture cycle mints fresh
   * attachment ids, so without an eviction point tied to the one place old ids are guaranteed to
   * be dead, both the Map and its blob object URLs would grow unbounded. */
  const attachmentInfoCache = new Map();
  async function attachmentInfo(attachmentId) {
    if (!attachmentId) return null;
    if (attachmentInfoCache.has(attachmentId)) return attachmentInfoCache.get(attachmentId);
    const r = await fetch('/api/v1/attachments/' + encodeURIComponent(attachmentId), { headers: auth() });
    if (!r.ok) return null;
    const url = URL.createObjectURL(await r.blob());
    const redactionApplicability = r.headers.get('X-DevConsole-Redaction-Applicability') || null;
    const info = { url, redactionApplicability };
    attachmentInfoCache.set(attachmentId, info);
    return info;
  }
  /** Revokes every cached blob object URL and drops the cache -- called only where old attachment
   * ids are guaranteed dead (the tray was just cleared server-side), never on a transient fetch
   * failure elsewhere, so an in-progress view never loses a thumbnail it's still showing. */
  function clearAttachmentInfoCache() {
    attachmentInfoCache.forEach((info) => { if (info?.url) URL.revokeObjectURL(info.url); });
    attachmentInfoCache.clear();
  }
  /** Populates every `<img data-thumb-for>` left by evidenceThumbsHtml()/renderEventDetail() after
   * it's in the DOM -- cardsGridHtml's innerHTML rebuild can't itself await an authenticated fetch,
   * so thumbnails mount asynchronously, one fetch per distinct attachment id actually on screen.
   * Also reconciles each thumbnail's UNREDACTED badge against the live response header -- the
   * pre-fetch markup already reflects the JSON-field value (itself authoritative, per 7ef109d), so
   * this only ever *corrects* a badge, it never has to invent one from nothing. */
  function mountEvidenceThumbnails(root) {
    root.querySelectorAll('img[data-thumb-for]').forEach((img) => {
      const id = img.dataset.thumbFor;
      attachmentInfo(id).then((info) => {
        if (!info) return;
        img.src = info.url;
        const badge = img.closest('.evidence-thumb-card')?.querySelector('.evidence-thumb-badge');
        // Only correct the badge when the response actually answered the question. The server
        // appends the header only when its metadata lookup succeeds (an unwired reader, a pruned
        // row, or a transient store exception all come back as 200 with no header) — absent means
        // "don't know", same rule as the JSON field (see isUnredacted above), so it must never
        // clear a badge the pre-fetch markup already painted from the authoritative JSON value.
        if (badge && info.redactionApplicability != null) badge.hidden = info.redactionApplicability !== 'NOT_APPLICABLE';
      });
    });
  }
  function evidenceThumbsHtml(items) {
    const shots = items.filter((it) => evidenceItemIsScreenshot(it) && it.attachmentId);
    if (!shots.length) return '<p class="card-lede">No screenshots flagged yet — capture one below, or flag a "screenshot" event from the Timeline.</p>';
    return `<div class="evidence-thumbs">${shots
      .map((it) => {
        const status = evidenceItemStatus(it);
        const unredacted = isUnredacted(it);
        return `<div class="evidence-thumb-card">
          <span class="evidence-thumb-badge"${unredacted ? '' : ' hidden'}>UNREDACTED</span>
          <button type="button" class="evidence-thumb-btn" data-card-btn="open-attachment:${esc(it.attachmentId)}" title="Open ${esc(it.label)} full size" aria-label="Open ${esc(it.label)} full size">
            <img data-thumb-for="${esc(it.attachmentId)}" alt="${esc(it.label)}">
          </button>
          <div class="evidence-thumb-meta"><span>${esc(status.text)}</span></div>
        </div>`;
      })
      .join('')}</div>`;
  }
  async function openAttachmentFullSize(attachmentId) {
    const info = await attachmentInfo(attachmentId);
    if (info?.url) window.open(info.url, '_blank', 'noopener');
    else toast('Attachment could not be opened.', 'error');
  }
  // ---- Screenshot capture (B): POST /api/v1/screenshots, then flag the resulting event as
  // SCREENSHOT-kind evidence so a capture is immediately visible in the tray with a thumbnail --
  // the capture flow *is* the flag flow here, matching "capture button ... in the evidence tray".
  function screenshotErrorMessage(code) {
    if (code === 'SCREENSHOT_DISABLED') return 'Screenshot capture is off by default — the host must set screenshotPolicy.enabled = true (DevConsoleConfig.withScreenshotPolicy(...)) to turn it on.';
    if (code === 'NO_FOREGROUND_ACTIVITY') return 'Nothing to capture — no foreground Activity on the device right now.';
    if (code === 'SECURE_WINDOW') return 'This screen is FLAG_SECURE — the platform blocks capturing it, so no screenshot was taken.';
    if (code === 'SCREENSHOT_FAILED') return 'Screenshot capture failed on the device.';
    if (code === 'AUTH_REQUIRED') return 'Connect this browser first.';
    return 'Screenshot capture failed' + (code ? ': ' + code : '');
  }
  async function captureScreenshot() {
    if (!hasSession()) { toast('Connect this browser first.', 'error'); return; }
    const btn = $('captureScreenshot');
    if (btn) btn.disabled = true;
    try {
      const r = await fetch('/api/v1/screenshots', { method: 'POST', headers: controlHeaders() });
      if (!r.ok) {
        let code; try { code = (await r.json()).code; } catch { code = undefined; }
        toast(screenshotErrorMessage(code), 'error');
        return;
      }
      const body = await r.json();
      toast('Screenshot captured (' + body.widthPx + '×' + body.heightPx + ').');
      await toggleEvidenceFlag('screenshot', body.eventId, 'Screenshot ' + body.widthPx + '×' + body.heightPx);
      if (currentView === 'evidence') loadEvidence();
      else if (currentView === 'overview') loadOverview();
    } finally {
      if (btn) btn.disabled = !hasSession();
    }
  }
  // ---- Report draft autosave: debounced ~750ms after the last keystroke, immediately on blur
  // (focusout, which bubbles — plain blur doesn't). A refresh must never lose typed text, so this
  // is the only path evidenceReport is ever written to from user input.
  function scheduleEvidenceReportSave() {
    clearTimeout(evidenceReportSaveTimer);
    evidenceReportSaveTimer = setTimeout(saveEvidenceReport, 750);
  }
  async function saveEvidenceReport() {
    clearTimeout(evidenceReportSaveTimer);
    if (!hasSession()) return;
    const summary = $('evidenceSummary')?.value ?? '';
    const expected = $('evidenceExpected')?.value ?? '';
    const actual = $('evidenceActual')?.value ?? '';
    const body = new URLSearchParams({ severity: evidenceReport.severity, summary, expected, actual });
    const r = await fetch('/api/v1/evidence/report', { method: 'PUT', headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' }, body });
    const status = $('evidenceSaveStatus');
    if (r.ok) {
      evidenceReport = await r.json();
      if (status) status.textContent = 'Saved ' + time(evidenceReport.updatedAtMs);
    } else {
      let code; try { code = (await r.json()).code; } catch { code = undefined; }
      if (status) status.textContent = 'Save failed' + (code ? ': ' + code : '');
      toast('Report save failed' + (code ? ': ' + code : ''), 'error');
    }
  }
  let evidenceReportAutosaveWired = false;
  function wireEvidenceReportAutosave() {
    if (evidenceReportAutosaveWired) return;
    evidenceReportAutosaveWired = true;
    const container = $('evidenceList');
    container.addEventListener('input', (e) => {
      if (['evidenceSummary', 'evidenceExpected', 'evidenceActual'].includes(e.target.id)) scheduleEvidenceReportSave();
    });
    // focusout bubbles (blur does not) — this is what lets one delegated listener on the stable
    // #evidenceList container catch every field's blur without rewiring per render.
    container.addEventListener('focusout', (e) => {
      if (['evidenceSummary', 'evidenceExpected', 'evidenceActual'].includes(e.target.id)) saveEvidenceReport();
    });
  }
  function renderEvidence(items, environment) {
    const hasDraftText = Boolean((evidenceReport.summary || '').trim() || (evidenceReport.expected || '').trim() || (evidenceReport.actual || '').trim());
    $('evidenceBadge').textContent = items.length ? evidenceSeverityLabel(evidenceReport.severity).toLowerCase() + ' · ' + items.length + ' attached' : 'empty';
    if (!items.length && !hasDraftText) {
      $('evidenceMetrics').innerHTML = '';
      cardsGridHtml('evidenceList', [
        {
          icon: 'flag', iconTone: 'muted', title: 'Nothing flagged yet', span: 3,
          lede: 'Flag a transaction, frame, push event, timeline event or crash with the flag button at the end of its row — or from its detail header — and it lands here with the full redacted detail the server captured at flag time, not just a label. Draft the report while the session is still live; once it ends the proof is gone. A browser refresh keeps everything: flagged items and any typed report text are saved server-side.',
          buttons: [
            { id: 'go-network', label: 'Go to Network', icon: 'network', kind: 'primary' },
            { id: 'capture-screenshot', label: 'Capture screenshot', icon: 'camera', disabled: !hasSession(), title: hasSession() ? 'Capture the device\'s foreground screen — off by default; set screenshotPolicy.enabled to allow it' : 'Connect this browser first' },
          ],
        },
      ]);
      wireCardGrid('evidenceList', { onButton: (id) => { if (id === 'go-network') show('network'); else if (id === 'capture-screenshot') captureScreenshot(); } });
      return;
    }
    $('evidenceMetrics').innerHTML = metricsStripHtml([
      { label: 'Attachments', val: String(items.length), tone: 'ink' },
      { label: 'Severity', val: evidenceSeverityLabel(evidenceReport.severity), tone: evidenceReport.severity === 'BLOCKER' || evidenceReport.severity === 'CRITICAL' ? 'error' : evidenceReport.severity === 'MAJOR' ? 'warn' : 'muted' },
    ]);
    const worst = pickWorstEvidenceItem(items);
    const rows = [];
    if (environment) rows.push({ k: 'Environment', v: environment.packageName + ' ' + environment.versionName, tone: 'muted' });
    // Expected is a real editable input in fieldsHtml below, never pre-filled — nobody but the
    // reporter knows what was *expected*. Actual is seeded (not saved) from the worst item's
    // snapshot only when nothing has been saved for it yet, same rule Summary follows.
    const observedActual = (() => {
      if (evidenceReport.actual) return evidenceReport.actual;
      if (!worst?.snapshot) return '';
      if (worst.kind === 'network') return (worst.snapshot.status ? String(worst.snapshot.status) : 'no response') + (worst.snapshot.error ? ' — ' + worst.snapshot.error : '');
      if (worst.kind === 'crash') return worst.snapshot.summary || '';
      return '';
    })();
    const stepLines = [{ pad: '', k: '', v: 'Steps to reproduce', cls: 'code-brace' }];
    if (worst) {
      stepLines.push({ pad: '1. ', k: '', v: 'Put the app back in the state it was in when this was flagged', cls: 'tone-text-ink' });
      if (worst.kind === 'network' && worst.snapshot) stepLines.push({ pad: '2. ', k: '', v: 'Trigger ' + worst.snapshot.method + ' ' + worst.snapshot.path, cls: 'tone-text-ink' });
      stepLines.push({ pad: worst.kind === 'network' && worst.snapshot ? '3. ' : '2. ', k: '', v: 'Observe: ' + worst.label, cls: 'tone-text-ink' });
    }
    cardsGridHtml('evidenceList', [
      {
        icon: 'pencil', iconTone: 'signal', title: 'Bug draft', span: 2, badge: 'autosaves', badgeTone: 'signal',
        fieldsHtml: `<div class="card-fields">
          <label class="field"><span>Summary</span><input id="evidenceSummary" data-preserve value="${esc(evidenceReport.summary || (worst ? worst.label : ''))}"></label>
          <label class="field"><span>Expected</span><input id="evidenceExpected" data-preserve value="${esc(evidenceReport.expected || '')}" placeholder="What should have happened"></label>
          <label class="field"><span>Actual</span><input id="evidenceActual" data-preserve value="${esc(observedActual)}" placeholder="What was actually observed"></label>
        </div>
        <p class="card-field-help" id="evidenceSaveStatus">${evidenceReport.updatedAtMs ? 'Saved ' + time(evidenceReport.updatedAtMs) : 'Not saved yet — autosaves ~750ms after you stop typing, and on blur.'}</p>`,
        rows,
        code: stepLines,
        buttons: [
          { id: 'copy-md', label: 'Copy as Markdown', icon: 'copy', kind: 'primary' },
          { id: 'copy-jira', label: 'Copy as Jira', icon: 'copy' },
          { id: 'copy-github', label: 'Copy as GitHub issue', icon: 'copy' },
          { id: 'copy-curl', label: 'Copy repro cURL', icon: 'copy' },
        ],
      },
      {
        icon: 'shield', iconTone: 'warn', title: 'Severity & routing',
        // Exactly one severity is ever selected — a radiogroup, not five independent switches.
        radioLabel: 'Severity',
        toggles: EVIDENCE_SEVERITIES.map((s) => ({ id: s, k: evidenceSeverityLabel(s), checked: evidenceReport.severity === s, sub: EVIDENCE_SEVERITY_SUB[s] })),
        rows: [{ k: 'Redaction', v: 'applied before export (screenshots excepted — see below)', tone: 'signal', tag: 'SAFE', tagTone: 'signal' }],
      },
      {
        icon: 'camera', iconTone: 'signal', title: 'Screenshots',
        lede: 'Screenshots cannot be redacted — pixels carry whatever was on screen, tokens included. Anything the server reports NOT_APPLICABLE is badged UNREDACTED below, in Timeline, and in the export manifest.',
        bodyHtml: evidenceThumbsHtml(items),
        buttons: [{ id: 'capture-screenshot', label: 'Capture screenshot', icon: 'camera', disabled: !hasSession(), title: hasSession() ? 'Capture the device\'s foreground screen — off by default; set screenshotPolicy.enabled to allow it' : 'Connect this browser first' }],
      },
      {
        icon: 'flag', iconTone: 'signal', title: 'Attachments', span: 3, badge: items.length + ' items', badgeTone: 'muted',
        // Bespoke table, not cardTableHtml: the last column needs real download/unflag buttons per
        // row, which a plain escaped-text cell can't hold.
        bodyHtml: evidenceAttachmentsTableHtml(items),
        buttons: [
          { id: 'export-bundle', label: 'Evidence bundle (ZIP)', icon: 'download', kind: 'primary', title: 'report.md/json, HAR, Postman, session.json and attachments for exactly the flagged items — POST /api/v1/exports?scope=EVIDENCE' },
          { id: 'export-zip', label: 'Export whole session (redacted)', icon: 'download' },
          { id: 'export-har', label: 'HAR of flagged transactions', icon: 'download' },
          { id: 'export-postman', label: 'Postman of flagged transactions', icon: 'download' },
          { id: 'clear', label: 'Clear tray', icon: 'trash' },
        ],
      },
    ]);
    mountEvidenceThumbnails($('evidenceList'));
    wireEvidenceReportAutosave();
    wireCardGrid('evidenceList', {
      onToggle: (sev) => { evidenceReport.severity = sev; scheduleEvidenceReportSave(); renderEvidence(items, environment); },
      onButton: (id) => {
        if (id === 'copy-md') copyEvidenceMarkdown(items, environment);
        else if (id === 'copy-jira') copyEvidenceJira(items, environment);
        else if (id === 'copy-github') copyEvidenceGithub(items, environment);
        else if (id === 'copy-curl') copyEvidenceCurl(items);
        else if (id === 'capture-screenshot') captureScreenshot();
        else if (id === 'export-bundle') downloadEvidenceBundle();
        else if (id === 'export-zip') createExport();
        else if (id === 'export-har') downloadEvidenceHar(items);
        else if (id === 'export-postman') downloadEvidencePostman(items);
        else if (id === 'clear') clearEvidenceTray();
        else if (id.startsWith('download-attachment:')) downloadEvidenceAttachment(id.slice('download-attachment:'.length));
        else if (id.startsWith('open-attachment:')) openAttachmentFullSize(id.slice('open-attachment:'.length));
        else if (id.startsWith('unflag:')) {
          const rest = id.slice('unflag:'.length);
          const sep = rest.indexOf(':');
          if (sep > 0) toggleEvidenceFlag(rest.slice(0, sep), rest.slice(sep + 1), '');
        }
      },
    });
  }
  /** DELETE /api/v1/evidence — clears the whole tray server-side, then reloads so the empty state
   * (and the still-persisted report draft, if any) render from the real post-clear server state
   * rather than an assumed-empty local one. */
  async function clearEvidenceTray() {
    if (!hasSession()) { evidenceFlags.clear(); updateEvidenceUi(); loadEvidence(); return; }
    const r = await fetch('/api/v1/evidence', { method: 'DELETE', headers: controlHeaders() });
    if (r.ok) { toast('Evidence tray cleared.'); clearAttachmentInfoCache(); }
    else { let code; try { code = (await r.json()).code; } catch { code = undefined; } toast(evidenceErrorMessage(code), 'error'); }
    loadEvidence();
  }

  // Shared control helpers
  // ================================================================
  const controlHeaders = () => ({ ...auth(), 'X-DevConsole-CSRF': csrf });
  const updateControlUi = () => {
    const enabled = hasSession();
    $('composerRun').disabled = !enabled;
    $('composerImport').disabled = !enabled;
    $('composerCollectionSave').disabled = !enabled;
    $('pushSimulate').disabled = !enabled;
    syncMockEngineButton();
    syncMockRuleDialogGate();
    $('captureRuleSave').disabled = !enabled || !captureRulesEditable;
    $('fileCreate').disabled = !enabled || !filesEditable;
    // Note: the timeline note textarea/save button are rendered inside the event detail pane
    // (renderEventDetail) with their disabled state set directly from `token` at render time,
    // since they only exist in the DOM once an event is selected.
    $('quickExport').disabled = !token;
    // Capture is off by default on the host (ScreenshotPolicy.enabled = false) and no route
    // reports that ahead of time — this button is only
    // ever gated on "is a session connected", exactly like Export; SCREENSHOT_DISABLED is
    // surfaced as a toast naming the config property once a capture is actually attempted.
    $('captureScreenshot').disabled = !token;
    syncConnectPrompts(enabled);
  };

  /**
   * Keeps the "connect first" hints in step with the controls they describe. Without this the
   * static copy keeps telling a connected operator to sign in while the button beside it is live.
   */
  const syncConnectPrompts = enabled => {
    const exportStatus = $('exportStatus');
    if (exportStatus && !exportStatus.dataset.busy) {
      exportStatus.textContent = enabled
        ? 'Choose a scope, then create a redacted ZIP.'
        : "Open DevConsole on the device → More → scan the QR or paste the code in Overview to create a diagnostic export.";
    }
    const composerSub = $('composerResult')?.querySelector('.empty-sub');
    if (composerSub) {
      composerSub.textContent = enabled
        ? 'Configure the request above, then execute it with the SDK-owned client.'
        : "Open DevConsole on the device → More → scan the QR or paste the code in Overview to execute or import requests.";
    }
  };

  // ================================================================
  // Composer
  // ================================================================
  /** `/api/v1/composer/*` 404s wholesale when the host disabled the composer feature (no
   * boolean capability field is ever returned — see requestExecution research); detected
   * reactively here, the one place every Composer visit already round-trips the server. */
  async function loadComposerCollections() {
    if (!token) return;
    const r = await fetch('/api/v1/composer/collections', { headers: auth() });
    const gate = $('composerGate');
    if (r.status === 404) {
      gate.hidden = false;
      gate.innerHTML = gateBannerHtml({ title: 'Request execution is disabled', body: 'The host app turned off the requestExecution capability for this build, so Composer is unavailable. Nothing here can be sent.', code: 'COMPOSER_DISABLED' });
      return;
    }
    gate.hidden = true;
    gate.innerHTML = '';
    if (!r.ok) return;
    const collections = (await r.json()).data || [];
    $('composerCollectionList').innerHTML = collections.length
      ? cardRowsHtml(
          collections.map((c) => ({
            k: c.name, v: (c.request?.method || 'GET') + ' ' + (c.request?.url || ''), tone: 'ink',
            tag: hasSession() ? 'DELETE' : false, tagTone: 'error',
          })),
        )
      : 'Collections contain no persisted secret variable values.';
    if (hasSession()) {
      $('composerCollectionList').querySelectorAll('.card-row').forEach((row, i) => {
        row.style.cursor = 'pointer';
        row.title = 'Delete this collection';
        row.onclick = () => deleteComposerCollection(collections[i].id);
      });
    }
  }
  /** GET /api/v1/meta reports the composer capability (`{enabled, allowedHosts}`, sorted hosts).
   * Also primes `composerCapabilityEnabled`, the same capability Network's "Resend" action gates
   * on (Resend re-executes through this same composer transport, so it shares Composer's on/off
   * switch). An empty allowlist DENIES every host (permitsHostOf matches against the configured
   * set; nothing configured means nothing matches) — say that honestly. */
  async function loadComposerHostAllowlist() {
    if (!token) return;
    const r = await fetch('/api/v1/meta', { headers: auth() });
    if (!r.ok) return;
    const composer = (await r.json()).composer || {};
    composerCapabilityEnabled = composer.enabled === true;
    const hosts = composer.allowedHosts || [];
    $('composerAllowedHosts').innerHTML = hosts.length
      ? cardRowsHtml(hosts.map((h) => ({ k: h, v: 'allowed', tone: 'signal' })))
      : '<p class="card-lede">No allowlist configured — every composer/resend host is refused until the host app allows some.</p>';
  }
  const appendComposerPairs = (body, name, value) =>
    value
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean)
      .forEach((line) => body.append(name, line));
  const fileAsBase64 = (file) =>
    new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(reader.error);
      reader.onload = () => resolve(String(reader.result).split(',', 2)[1] || '');
      reader.readAsDataURL(file);
    });
  async function runComposer() {
    if (!hasSession()) return;
    const bodyType = $('composerBodyType').value;
    const body = new URLSearchParams({
      method: $('composerMethod').value,
      url: $('composerUrl').value,
      bodyType,
      timeoutMs: $('composerTimeout').value,
      followRedirects: $('composerRedirects').value,
    });
    if (bodyType === 'TEXT' || bodyType === 'JSON') body.set('body', $('composerBody').value);
    appendComposerPairs(body, 'query', $('composerQuery').value);
    appendComposerPairs(body, 'header', $('composerHeaders').value);
    appendComposerPairs(body, 'form', $('composerForm').value);
    appendComposerPairs(body, 'multipart', $('composerMultipart').value);
    appendComposerPairs(body, 'variable', $('composerVariables').value);
    appendComposerPairs(body, 'secretVariable', $('composerSecrets').value);
    if (bodyType === 'BINARY_FILE') {
      const file = $('composerBinary').files[0];
      if (!file || file.size > 3 * 1024 * 1024) {
        renderEmpty($('composerResult'), 'alert', 'Choose a binary file no larger than 3 MiB.');
        return;
      }
      body.set('binaryBodyBase64', await fileAsBase64(file));
      body.set('binaryFileName', file.name);
      body.set('binaryContentType', file.type || 'application/octet-stream');
    }
    $('composerRun').disabled = true;
    renderEmpty($('composerResult'), 'send', 'Executing…', 'Running with the SDK-owned client.');
    const r = await fetch('/api/v1/composer/execute', {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    });
    let result;
    try {
      result = await r.json();
    } catch {
      result = { code: 'INVALID_RESPONSE' };
    }
    renderJson($('composerResult'), result);
    $('composerRun').disabled = false;
    if (r.ok) {
      loadNetwork();
      load();
    }
  }
  async function importCurl() {
    if (!hasSession()) return;
    const r = await fetch('/api/v1/composer/import', { method: 'POST', headers: controlHeaders(), body: $('composerCurl').value });
    if (r.ok) {
      const value = await r.json();
      renderJson($('composerResult'), value);
      $('composerMethod').value = value.method || 'GET';
      $('composerUrl').value = value.url || '';
      $('composerBodyType').value = value.bodyType || 'NONE';
      $('composerTimeout').value = value.timeoutMs || 15000;
      $('composerRedirects').value = String(value.followRedirects ?? true);
    } else {
      renderEmpty($('composerResult'), 'alert', 'Import failed: ' + r.status);
    }
  }
  async function saveComposerCollection() {
    if (!hasSession()) return;
    const body = new URLSearchParams({ name: $('composerCollectionName').value, curl: $('composerCurl').value });
    const r = await fetch('/api/v1/composer/collections', {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    });
    toast(r.ok ? 'Collection saved without secret values.' : 'Save failed: ' + r.status, r.ok ? undefined : 'error');
    if (r.ok) loadComposerCollections();
  }
  async function deleteComposerCollection(id) {
    if (!hasSession()) return;
    const r = await fetch('/api/v1/composer/collections/' + encodeURIComponent(id), { method: 'DELETE', headers: controlHeaders() });
    toast(r.ok ? 'Collection deleted.' : 'Delete failed: ' + r.status, r.ok ? undefined : 'error');
    if (r.ok) loadComposerCollections();
  }

  // ================================================================
  // Mocks
  // ================================================================
  const canEditMocks = () => hasSession() && mocksEditable;
  const canResend = () => hasSession() && composerCapabilityEnabled;
  let mockConflictCount = 0;
  let mockPendingHighlightId = '';
  async function loadMockRules() {
    if (!token) return;
    const r = await fetch('/api/v1/mocks/rules', { headers: auth() });
    if (r.ok) {
      const body = await r.json();
      mocksEditable = body.editable === true;
      updateControlUi();
      const rules = body.data || [];
      mockRulesCache = rules;
      $('mocksRulesBadge').textContent = rules.filter((rule) => rule.enabled).length + ' of ' + rules.length + ' active';
      $('mockRuleList').innerHTML = rules.length
        ? cardTogglesHtml(
            rules.map((rule) => ({
              // Only StaticResponse (± Delay) rules survive a dialog round-trip; editing a
              // fault-injection/template rule here would silently rewrite it as a static 200,
              // and a truncated body would save the truncation as content.
              id: rule.id, checked: rule.enabled, disabled: !canEditMocks(), deletable: canEditMocks(),
              editable: isMockRuleEditable(rule),
              k: rule.id,
              sub: (rule.method || '*') + ' ' + rule.path + ' → ' + rule.action + (rule.statusCode != null ? ' ' + rule.statusCode : '') + ' · ' + rule.scope
                + (rule.hitCount ? ' · ' + rule.hitCount + ' hit' + (rule.hitCount === 1 ? '' : 's') + (rule.lastHitEpochMs ? ' · ' + relativeTime(rule.lastHitEpochMs) : '') : ''),
            })),
          )
        : '<p class="card-lede">No mock rules are active for this session.</p>';
      $('mocksMetrics').innerHTML = metricsStripHtml([
        { label: 'Rules', val: String(rules.length), sub: rules.filter((rule) => rule.enabled).length + ' on', tone: 'ink' },
        { label: 'Conflicts', val: String(mockConflictCount), tone: mockConflictCount ? 'warn' : 'ink' },
        { label: 'Mocking', val: rules.length && rules.some((rule) => rule.enabled) ? 'active' : 'idle', tone: rules.some((rule) => rule.enabled) ? 'put' : 'muted' },
        { label: 'Global switch', val: mocksGloballyEnabled ? 'on' : 'off', tone: mocksGloballyEnabled ? 'put' : 'muted' },
      ]);
      if (mockPendingHighlightId) {
        const pendingId = mockPendingHighlightId;
        mockPendingHighlightId = '';
        requestAnimationFrame(() => {
          const row = document.querySelector(`#mockRuleList [data-card-toggle="${CSS.escape(pendingId)}"]`)?.closest('.card-toggle-row');
          if (!row) return;
          row.scrollIntoView({ block: 'center' });
          row.classList.add('row-flash');
          setTimeout(() => row.classList.remove('row-flash'), 900);
        });
      }
      const gate = $('mocksGate');
      if (!mocksEditable) {
        gate.hidden = false;
        gate.innerHTML = gateBannerHtml({ title: 'Mock editing is disabled', body: 'The mocks capability is off for this build. Rules are shown read-only and toggles are refused.', code: 'MOCKS_DISABLED' });
      } else {
        gate.hidden = true;
        gate.innerHTML = '';
      }
      // Refreshes mockRulesCache, which the open network detail pane's "N fields differ from
      // original" notice and diff highlighting read directly (see renderNetworkDetail) — without
      // this, a rule save/delete that happens to change the serving rule's sourceBodySnapshot
      // wouldn't show up on an already-open transaction until something else re-rendered it.
      if (currentView === 'network' && selectedTransactionDetail?.tags?.mocked === 'true') renderNetworkDetail();
    }
    loadMockConflicts();
  }
  async function loadMockConflicts() {
    if (!token) return;
    const r = await fetch('/api/v1/mocks/conflicts', { headers: auth() });
    if (!r.ok) return;
    const conflicts = (await r.json()).data || [];
    mockConflictCount = conflicts.length;
    $('mockConflicts').innerHTML = conflicts.length
      ? cardRowsHtml(conflicts.map((c) => ({ k: c.first, v: 'may also match ' + c.second, tone: 'warn' })))
      : '<p class="card-lede">No overlapping rules detected.</p>';
  }
  async function loadMocks() {
    if (!token) return;
    const r = await fetch('/api/v1/mocks', { headers: auth() });
    mocksGloballyEnabled = r.ok ? (await r.json()).enabled === true : true;
    syncMockEngineButton();
    loadMockRules();
  }
  /** Turning mocking off stays on the ungated `disable-all` route, so a host that publishes mocks
   *  as read-only can still fall back to real traffic; turning it back on goes through the
   *  capability-gated `/enabled` route, because that one changes how the app behaves. */
  async function toggleMockEngine() {
    if (!hasSession()) return;
    const turningOn = !mocksGloballyEnabled;
    if (turningOn && !canEditMocks()) return;
    const r = turningOn
      ? await fetch('/api/v1/mocks/enabled', {
          method: 'POST',
          headers: { ...controlHeaders(), 'Content-Type': 'text/plain' },
          body: 'true',
        })
      : await fetch('/api/v1/mocks/disable-all', { method: 'POST', headers: controlHeaders() });
    const verb = turningOn ? 'enabled' : 'disabled';
    toast(r.ok ? 'Mocking ' + verb + '.' : (turningOn ? 'Enable' : 'Disable') + ' failed: ' + r.status, r.ok ? undefined : 'error');
    if (r.ok) loadMocks();
  }
  /** Keeps the single mock-engine button describing what it will do next. */
  function syncMockEngineButton() {
    const btn = $('mockDisable');
    if (!btn) return;
    const on = mocksGloballyEnabled;
    btn.classList.toggle('danger', on);
    btn.querySelector('use').setAttribute('href', on ? '#dc-close' : '#dc-check');
    btn.lastChild.textContent = on ? 'Disable all mocks' : 'Enable mocking';
    btn.disabled = !hasSession() || (!on && !canEditMocks());
    btn.title = btn.disabled
      ? (hasSession() ? 'The host app publishes mock rules as read-only' : 'Sign-in required')
      : on
        ? 'Stop serving every mock rule — requests go to the real network'
        : 'Serve matching requests from the enabled mock rules again';
  }
  async function deleteMockRule(id) {
    if (!canEditMocks()) return;
    const r = await fetch('/api/v1/mocks/rules/' + encodeURIComponent(id), { method: 'DELETE', headers: controlHeaders() });
    toast(r.ok ? 'Mock rule deleted.' : 'Delete failed: ' + r.status, r.ok ? undefined : 'error');
    if (r.ok) loadMockRules();
  }
  async function setMockRuleEnabled(id, enabled) {
    if (!canEditMocks()) return;
    const r = await fetch('/api/v1/mocks/rules/' + encodeURIComponent(id) + '/enabled', {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'text/plain' },
      body: String(enabled),
    });
    toast(r.ok ? 'Mock rule ' + (enabled ? 'enabled.' : 'disabled.') : 'Update failed: ' + r.status, r.ok ? undefined : 'error');
    if (r.ok) loadMockRules();
  }

  // ================================================================
  // Capture rules
  // ================================================================
  const canEditCaptureRules = () => hasSession() && captureRulesEditable;
  async function loadCaptureRules() {
    if (!token) return;
    const [rulesRes, metaRes] = await Promise.all([fetch('/api/v1/capture-rules', { headers: auth() }), fetch('/api/v1/meta', { headers: auth() })]);
    if (!rulesRes.ok) {
      $('captureRuleStatus').textContent = 'Capture exclusions unavailable: ' + rulesRes.status;
      return;
    }
    const body = await rulesRes.json();
    captureRulesEditable = body.editable === true;
    updateControlUi();
    $('captureRuleStatus').textContent = canEditCaptureRules() ? 'Capture exclusions are editable.' : 'Read-only — sign in and enable the captureRules capability to edit.';
    const rules = body.data || [];
    $('captureRulesBadge').textContent = rules.length + ' rule' + (rules.length === 1 ? '' : 's');
    $('captureRuleList').innerHTML = rules.length ? captureRuleTableHtml(rules) : '<p class="card-lede">No capture exclusions are configured — everything matching a capture rule is retained.</p>';
    const gate = $('captureRulesGate');
    if (!captureRulesEditable) {
      gate.hidden = false;
      gate.innerHTML = gateBannerHtml({ title: 'Capture rules are read-only', body: 'The captureRules capability is off, so rules can be inspected but not changed.', code: 'CAPTURE_RULES_DISABLED' });
    } else {
      gate.hidden = true;
      gate.innerHTML = '';
    }
    if (metaRes.ok) {
      const names = (await metaRes.json()).redaction?.sensitiveFieldNames || [];
      $('captureRedaction').innerHTML = names.length
        ? cardRowsHtml(names.map((n) => ({ k: n, v: 'masked before capture', tone: 'warn', tag: 'MASK', tagTone: 'warn' })))
        : '<p class="card-lede">No redacted field names were reported.</p>';
    }
    setNavCount('navCountCaptureRules', rules.length);
  }
  /** Bespoke table (not the generic `cardTableHtml`, which only escapes plain text) so the
   * State/delete cells can carry real per-row action buttons. */
  function captureRuleTableHtml(rules) {
    const editable = canEditCaptureRules();
    const cols = ['Order', 'Host', 'Method', 'Path prefix', 'Action', 'State', ''];
    const rows = rules
      .map(
        (rule, i) => `<tr>
        <td class="tone-text-muted">${i + 1}</td>
        <td class="tone-text-ink">${esc(rule.host)}</td>
        <td class="tone-text-muted">${esc(rule.method || '*')}</td>
        <td class="tone-text-ink">${esc(rule.pathPrefix || '—')}</td>
        <td class="tone-text-error">EXCLUDE</td>
        <td>${
          editable
            ? `<button type="button" class="card-row-tag tone-text-${rule.enabled ? 'signal' : 'muted'} tone-border-${rule.enabled ? 'signal' : 'muted'}" data-capture-toggle="${esc(rule.id)}" data-capture-next="${rule.enabled ? 'false' : 'true'}" aria-pressed="${rule.enabled}" style="cursor:pointer">${rule.enabled ? 'ACTIVE' : 'PAUSED'}</button>`
            : `<span class="tone-text-${rule.enabled ? 'signal' : 'muted'}">${rule.enabled ? 'ACTIVE' : 'PAUSED'}</span>`
        }</td>
        <td>${editable ? `<button type="button" class="row-flag" data-capture-delete="${esc(rule.id)}" title="Delete" aria-label="Delete ${esc(rule.id)}">${icon('trash', 'ic-sm')}</button>` : ''}</td>
      </tr>`,
      )
      .join('');
    return `<div class="card-table-wrap"><table class="card-table"><thead><tr>${cols.map((h) => `<th scope="col">${esc(h)}</th>`).join('')}</tr></thead><tbody>${rows}</tbody></table></div>`;
  }
  function wireCaptureRuleActions() {
    const list = $('captureRuleList');
    if (list.dataset.wired) return;
    list.dataset.wired = '1';
    list.addEventListener('click', (e) => {
      const toggle = e.target.closest('[data-capture-toggle]');
      if (toggle) { setCaptureRuleEnabled(toggle.dataset.captureToggle, toggle.dataset.captureNext === 'true'); return; }
      const del = e.target.closest('[data-capture-delete]');
      if (del) deleteCaptureRule(del.dataset.captureDelete);
    });
  }
  async function saveCaptureRule() {
    if (!canEditCaptureRules()) return;
    const body = new URLSearchParams({
      id: $('captureRuleId').value,
      host: $('captureRuleHost').value,
      method: $('captureRuleMethod').value,
      pathPrefix: $('captureRulePathPrefix').value,
      enabled: 'true',
    });
    const r = await fetch('/api/v1/capture-rules', {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    });
    toast(r.ok ? 'Capture exclusion saved.' : 'Save failed: ' + r.status, r.ok ? undefined : 'error');
    if (r.ok) loadCaptureRules();
  }
  async function setCaptureRuleEnabled(id, enabled) {
    if (!canEditCaptureRules()) return;
    const r = await fetch('/api/v1/capture-rules/' + encodeURIComponent(id) + '/enabled', {
      method: 'POST',
      headers: { ...controlHeaders(), 'Content-Type': 'text/plain' },
      body: String(enabled),
    });
    toast(r.ok ? 'Capture exclusion ' + (enabled ? 'resumed.' : 'paused.') : 'Update failed: ' + r.status, r.ok ? undefined : 'error');
    if (r.ok) loadCaptureRules();
  }
  async function deleteCaptureRule(id) {
    if (!canEditCaptureRules()) return;
    const r = await fetch('/api/v1/capture-rules/' + encodeURIComponent(id), { method: 'DELETE', headers: controlHeaders() });
    toast(r.ok ? 'Capture exclusion deleted.' : 'Delete failed: ' + r.status, r.ok ? undefined : 'error');
    if (r.ok) loadCaptureRules();
  }

  // ================================================================
  // Network transaction detail / export
  // ================================================================
  /** Detail pane header/tabs/body builder shared by Network/WebSockets/Push (Timeline's is
   * simpler and inlined in renderEventDetail since it has no tabs).
   *
   * The closing `</div>` on the last line is load-bearing. Every caller builds its pane as
   * `head + findBar + <div class="detail-body">…</div>`, counting on all three being *siblings*
   * inside `.detail-pane-v2`'s flex column: the head sizes to content and `.detail-body` takes the
   * rest with `flex: 1; min-height: 0; overflow: auto`, which is the only thing that scrolls a long
   * response body. While this div was left unclosed the parser nested the find bar and the body
   * inside `.detail-head` instead, where `flex`/`min-height`/`overflow` mean nothing — so a body
   * taller than the pane grew the head past it and `.detail-pane-v2`'s `overflow: hidden` simply
   * cut it off, with nothing anywhere able to scroll to the rest. */
  function detailHeadHtml({ badgeText, badgeTone, title, statusText, sTone, mocked, extraBadge, facts, actions, tabs, layoutToggle }) {
    const zoom = document.body.classList.contains('detail-zoom');
    return `<div class="detail-head">
      <div class="detail-head-row">
        <span class="detail-badge badge-${badgeTone}">${esc(badgeText)}</span>
        ${mocked ? `<span class="detail-badge badge-put">MOCK</span>` : ''}
        ${extraBadge ? `<span class="detail-badge badge-${extraBadge.tone}">${esc(extraBadge.text)}</span>` : ''}
        <span class="detail-head-title">${esc(title)}</span>
        <span class="detail-head-status tone-text-${sTone || 'muted'}">${esc(statusText || '')}</span>
        <button type="button" class="detail-expand${zoom ? ' active' : ''}" data-action="toggle-zoom" aria-pressed="${zoom}" title="${zoom ? 'Restore the split view (f)' : 'Expand this pane to full width (f)'}">${icon(zoom ? 'collapse' : 'expand', 'ic-sm')}</button>
      </div>
      ${
        facts && facts.length
          ? `<div class="detail-facts">${facts
              .map((f) =>
                f.link
                  ? `<button type="button" class="detail-fact detail-fact-link" data-open-mock-rule="${esc(f.v)}" title="Open ${esc(f.v)} in Mocks"><span class="detail-fact-k">${esc(f.k)}</span><span class="detail-fact-v">${esc(f.v)}</span></button>`
                  : `<span class="detail-fact"><span class="detail-fact-k">${esc(f.k)}</span><span class="detail-fact-v">${esc(f.v)}</span></span>`,
              )
              .join('')}</div>`
          : ''
      }
      ${actions && actions.length ? `<div class="detail-actions-row">${actions.map((a) => `<button type="button" class="detail-action-btn${a.on ? ' on' : ''}${a.hideInSimple ? ' detail-action-hide-simple' : ''}" data-detail-action="${esc(a.id)}" ${a.disabled ? 'disabled' : ''} title="${esc(a.title || a.label)}">${a.icon ? icon(a.icon, 'ic-sm') : ''}${esc(a.label)}</button>`).join('')}</div>` : ''}
      ${
        tabs && tabs.length
          ? `<div class="detail-tabs">${tabs.map((t) => `<button type="button" class="detail-tab${t.active ? ' active' : ''}" data-detail-tab="${esc(t.id)}">${esc(t.label)}${t.count ? `<span class="detail-tab-count">${esc(t.count)}</span>` : ''}</button>`).join('')}${
              layoutToggle
                ? `<button type="button" class="detail-layout-toggle${layoutToggle.on ? ' on' : ''}" data-detail-action="toggle-layout" title="${esc(layoutToggle.title)}">${icon(layoutToggle.icon, 'ic-sm')}${esc(layoutToggle.label)}</button>`
                : ''
            }</div>`
          : ''
      }</div>`;
  }
  /** Shared by every detail pane's find-in-detail bar (Network, WebSockets, Timeline, Push) --
   * `inputId` keeps each pane's input element uniquely addressable since all four panes' markup
   * lives in the DOM at once (only one view is visible, but none are torn down when hidden). */
  function findBarHtml(query, matchCount, inputId = 'detailFindInput') {
    return `<div class="detail-find"><span class="search-wrap"><svg class="ic ic-sm search-lead" aria-hidden="true"><use href="#dc-search"/></svg><input type="text" id="${inputId}" placeholder="Find in headers, payload and response…" value="${esc(query)}"></span><span class="detail-find-count tone-text-${query ? (matchCount ? 'signal' : 'warn') : 'muted'}">${
      query ? matchCount + ' match' + (matchCount === 1 ? '' : 'es') : 'headers + payload + response'
    }</span></div>`;
  }
  function sbsPaneHtml(pane) {
    return `<div class="sbs-pane">
      <div class="sbs-pane-head">
        <span class="sbs-pane-dot tone-bg-${pane.dotTone}"></span>
        <span class="sbs-pane-side">${esc(pane.side)}</span>
        <span class="sbs-pane-meta tone-text-${pane.metaTone || 'muted'}">${esc(pane.meta)}</span>
        ${(pane.actions || []).map((a) => `<button type="button" class="sbs-pane-action" data-detail-action="${esc(a.id)}" title="${esc(a.title || a.label)}">${a.icon ? icon(a.icon) : ''}${esc(a.label)}</button>`).join('')}
      </div>
      <div class="sbs-pane-body">${pane.groups.map((g) => detailGroupHtml(pane.side + '/' + g.label, g, networkGroupOpen)).join('')}</div>
    </div>`;
  }
  const networkGroupOpen = {};

  async function fetchTransactionDetail(id) {
    if (networkDetailCache.has(id)) {
      const cached = networkDetailCache.get(id);
      cacheNetworkDetail(id, cached); // touch: refresh recency on hit
      return cached;
    }
    const r = await fetch('/api/v1/network/transactions/' + encodeURIComponent(id), { headers: auth() });
    if (!r.ok) return null;
    const detail = await r.json();
    cacheNetworkDetail(id, detail);
    return detail;
  }
  async function showTransaction(id) {
    if (!token || !id) return;
    selectedTransactionId = id;
    selectedTransactionDetail = await fetchTransactionDetail(id);
    if (networkTab === 'diff' && !networkPinnedId) networkTab = 'compare';
    renderNetwork();
    renderNetworkDetail();
  }
  function pinNetworkBaseline(id) {
    if (!id) return;
    networkPinnedId = networkPinnedId === id ? '' : id;
    toast(networkPinnedId ? 'Pinned as diff baseline.' : 'Baseline unpinned.');
    if (networkPinnedId) fetchTransactionDetail(networkPinnedId).then(renderNetworkDetail);
    renderNetwork();
    renderNetworkDetail();
  }

  // ---- Detail tab content builders (real fields only — a Timing tab is omitted since the live
  // API doesn't expose timing data to fill one) ----
  function headerRows(headers) {
    return (headers || []).map((h) => ({ k: h.name, v: h.value }));
  }
  /** A BodyPreview ({type:'text'|'binary'|'absent', ...}) to mock-style code lines. JSON text
   * bodies get full syntax coloring; non-JSON text falls back to a single string line. */
  function bodyPreviewLines(body, query) {
    if (!body || body.type === 'absent') return null;
    if (body.type === 'binary') return [{ pad: '', k: '', v: 'Binary body — ' + body.length + ' bytes' + (body.truncated ? ' (truncated)' : ''), cls: 'json-null', hit: false }];
    try {
      return formatJsonLines(JSON.parse(body.value), query);
    } catch {
      return formatJsonLines(body.value, query);
    }
  }

  /** Raw text for the body viewer's Pretty/Raw toggle — `undefined` (not just falsy) for
   * anything other than a captured text body, so `bodyViewerHtml` can tell "no raw text to
   * format" apart from "empty string body" and fall back to the plain code-block/empty state. */
  function bodyRawText(body) {
    return body?.type === 'text' ? body.value : undefined;
  }

  // ================================================================
  // Mocked-response diff: when a transaction was served by a "Mock this response" rule, its
  // `sourceBodySnapshot` (the original response body the rule was created from) is diffed
  // structurally against the mocked body actually served, so the JSON tree can flag exactly what
  // an engineer overrode. Contract mirrors the Compose/Kotlin side: a scalar value that changed,
  // or a field/array element that's new, gets highlighted; a field present in the snapshot but
  // missing from the mock is only counted as "removed", never rendered (there's no row for a key
  // that isn't there); either body failing to parse as JSON means the feature is silently absent.
  // ================================================================
  /**
   * @param mockRaw the response body actually served by the mock (raw JSON text)
   * @param snapshotRaw the rule's `sourceBodySnapshot` (raw JSON text of the original response)
   * @returns `null` when either side isn't valid JSON (or is missing); otherwise
   *   `{hits, ancestors, changed, added, removed}` -- `hits`/`ancestors` are `Set<pathKey>` for
   *   `jsonNode`/`jsonContainer` (see `pathKey`), `changed + added + removed` is the notice count.
   */
  function diffMockBody(mockRaw, snapshotRaw) {
    if (mockRaw == null || snapshotRaw == null) return null;
    let mockVal, snapshotVal;
    try {
      mockVal = JSON.parse(mockRaw);
      snapshotVal = JSON.parse(snapshotRaw);
    } catch {
      return null;
    }
    const hits = new Set();
    let changed = 0, added = 0, removed = 0;
    const isPlainObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
    // `mine`/`other` are the mock body and the snapshot, aligned at the same key-path; only
    // called when both are containers of the same kind (walk) or as a leaf fallback (compare).
    function compare(mine, other, path) {
      if (Array.isArray(mine) && Array.isArray(other)) return walk(mine, other, path, true);
      if (isPlainObject(mine) && isPlainObject(other)) return walk(mine, other, path, false);
      if (JSON.stringify(mine) !== JSON.stringify(other)) {
        changed++;
        hits.add(pathKey(path));
      }
    }
    function walk(mine, other, path, isArray) {
      const mineKeys = isArray ? mine.map((_, i) => String(i)) : Object.keys(mine);
      const otherKeys = isArray ? other.map((_, i) => String(i)) : Object.keys(other);
      const otherKeySet = new Set(otherKeys);
      mineKeys.forEach((key) => {
        const childPath = path.concat([key]);
        if (!otherKeySet.has(key)) {
          added++;
          hits.add(pathKey(childPath));
          return;
        }
        compare(mine[key], other[key], childPath);
      });
      const mineKeySet = new Set(mineKeys);
      otherKeys.forEach((key) => { if (!mineKeySet.has(key)) removed++; });
    }
    compare(mockVal, snapshotVal, []);
    if (!hits.size) return { hits, ancestors: hits, changed, added, removed };
    // Every proper ancestor of a highlighted path, so a container past JSON_AUTO_COLLAPSE_DEPTH
    // that contains a diff starts open instead of hiding the very thing this feature surfaces.
    const ancestors = new Set();
    hits.forEach((key) => {
      const parts = JSON.parse(key);
      for (let i = 1; i < parts.length; i++) ancestors.add(pathKey(parts.slice(0, i)));
    });
    return { hits, ancestors, changed, added, removed };
  }
  // renderNetworkDetail() re-runs on every find-box keystroke and every mock-rule reload (see its
  // call sites), but the mocked response body and the rule's snapshot rarely change between those
  // re-renders. A single-entry cache keyed on the exact inputs turns "recompute the whole tree
  // diff on every keystroke" into "recompute only when the transaction or the rule actually
  // changed" — a plain `===` on the previous call's own string references, which is O(1) whenever
  // neither input changed (the common case) rather than paying for the walk again.
  let mockDiffCache = null; // { mockRuleId, snapshot, raw, result }
  /** `getMockDiff(mockRule, mockRaw)` — memoised, size-guarded wrapper around `diffMockBody`.
   * Applies the same `MAX_BODY_FORMAT_BYTES` ceiling `bodyViewerHtml` uses for Pretty-mode
   * formatting, so a huge mocked body/snapshot can't force a synchronous full-tree JSON.parse +
   * walk on every render the way an unguarded call would. */
  function getMockDiff(mockRule, mockRaw) {
    const snapshot = mockRule?.sourceBodySnapshot;
    if (!snapshot) return null;
    if (mockDiffCache && mockDiffCache.mockRuleId === mockRule.id && mockDiffCache.snapshot === snapshot && mockDiffCache.raw === mockRaw) {
      return mockDiffCache.result;
    }
    const oversize = (text) => text != null && (text.length > MAX_BODY_FORMAT_BYTES || utf8ByteLength(text) > MAX_BODY_FORMAT_BYTES);
    const result = oversize(mockRaw) || oversize(snapshot) ? null : diffMockBody(mockRaw, snapshot);
    mockDiffCache = { mockRuleId: mockRule.id, snapshot, raw: mockRaw, result };
    return result;
  }

  function networkComparePanes(detail, query, diffInfo, diffSig) {
    const reqBody = bodyPreviewLines(detail.request?.body, query);
    const reqPane = {
      side: 'Request', dotTone: 'put', meta: detail.method + (detail.request?.contentType ? ' · ' + detail.request.contentType : ''), metaTone: 'muted',
      actions: [{ id: 'copy-request', label: 'Copy', icon: 'copy', title: 'Copy the whole request (line, headers, payload)' }],
      groups: [
        { label: 'General', copyLabel: 'request line', kvs: markKvHits([{ k: 'url', v: detail.request?.url || '' }, { k: 'method', v: detail.method }, { k: 'sent at', v: time(detail.startedAtEpochMs) }], query) },
        { label: 'Request headers', copyLabel: 'request headers', meta: String(headerRows(detail.request?.headers).length), kvs: markKvHits(headerRows(detail.request?.headers), query) },
        reqBody
          ? { label: 'Payload', copyLabel: 'request payload', meta: detail.request?.contentType || '', code: reqBody, body: { raw: bodyRawText(detail.request?.body), contentType: detail.request?.contentType } }
          : { label: 'Payload', empty: detail.method + ' request — no body sent.' },
      ],
    };
    const hasResponse = Boolean(detail.response);
    const resBody = hasResponse ? bodyPreviewLines(detail.response.body, query) : null;
    const resPane = {
      side: 'Response', dotTone: statusTone(detail.status), metaTone: statusTone(detail.status),
      meta: (detail.status != null ? String(detail.status) : detail.error ? 'FAILED' : '—') + (detail.durationMs != null ? ' · ' + detail.durationMs + ' ms' : ''),
      actions: hasResponse
        ? [{ id: 'copy-response', label: 'Copy', icon: 'copy', title: 'Copy the whole response (status, headers, body)' }]
        : [{ id: 'copy-error', label: 'Copy error', icon: 'copy' }],
      groups: hasResponse
        ? [
            { label: 'General', copyLabel: 'status line', kvs: markKvHits([{ k: 'status', v: String(detail.status) }, { k: 'duration', v: (detail.durationMs ?? '—') + ' ms' }, { k: 'content type', v: detail.response.contentType || '—' }], query) },
            { label: 'Response headers', copyLabel: 'response headers', meta: String(headerRows(detail.response.headers).length), kvs: markKvHits(headerRows(detail.response.headers), query) },
            resBody
              ? { label: 'Body', copyLabel: 'response body', meta: detail.response.contentType || '', code: resBody, body: { raw: bodyRawText(detail.response.body), contentType: detail.response.contentType, diffInfo, diffSig } }
              : { label: 'Body', empty: 'No body.' },
          ]
        : [
            { label: 'General', copyLabel: 'failure detail', kvs: markKvHits([{ k: 'status', v: '— no response' }, { k: 'error', v: detail.error || 'unknown' }], query) },
            { label: 'Response headers', empty: 'Connection failed before headers arrived.' },
            { label: 'Body', empty: 'No body.' },
          ],
    };
    return [reqPane, resPane];
  }

  function diffHeaderStats(mine, other) {
    const A = {}, B = {};
    headerRows(mine.response?.headers).forEach((h) => (A[h.k] = h.v));
    headerRows(other.response?.headers).forEach((h) => (B[h.k] = h.v));
    const keys = Object.keys(A).concat(Object.keys(B).filter((k) => !(k in A)));
    let changed = 0, added = 0;
    keys.forEach((k) => { if (!(k in A) || !(k in B)) added++; else if (A[k] !== B[k]) changed++; });
    if (mine.status !== other.status) changed++;
    return { changed, added };
  }
  function diffHeaderRows(mine, other) {
    const A = {}, B = {};
    headerRows(mine.response?.headers).forEach((h) => (A[h.k] = h.v));
    headerRows(other.response?.headers).forEach((h) => (B[h.k] = h.v));
    const keys = Object.keys(A).concat(Object.keys(B).filter((k) => !(k in A)));
    return keys.map((k) => {
      if (!(k in A)) return { k: '   ' + k, v: '— absent here', tone: 'muted' };
      if (!(k in B)) return { k: ' + ' + k, v: A[k], tone: 'put' };
      return A[k] === B[k] ? { k: '   ' + k, v: A[k], tone: 'muted' } : { k: ' ≠ ' + k, v: A[k], tone: 'warn' };
    });
  }
  function diffBodyLines(mine, other) {
    const mineLines = bodyPreviewLines(mine.response?.body, '') || [{ pad: '', k: '', v: 'No body.', cls: 'json-null', hit: false }];
    const otherLines = bodyPreviewLines(other.response?.body, '') || [];
    return mineLines.map((l, i) => {
      const o = otherLines[i];
      return { ...l, diffHit: !o || o.k + o.v !== l.k + l.v };
    });
  }
  function diffPaneHtml(sideLabel, mine, other, isBase) {
    const stats = diffHeaderStats(isBase ? mine : other, isBase ? other : mine);
    return {
      side: sideLabel, dotTone: isBase ? 'put' : statusTone(mine.status), metaTone: statusTone(mine.status),
      meta: (mine.status != null ? String(mine.status) : 'FAILED') + ' · ' + (mine.durationMs != null ? mine.durationMs + ' ms' : '—') + ' · ' + time(mine.startedAtEpochMs),
      actions: [{ id: 'copy-diff', label: 'Copy', icon: 'copy' }],
      groups: [
        { label: 'Request', kvs: [
          { k: 'method', v: mine.method, tone: mine.method === other.method ? 'muted' : 'warn' },
          { k: 'path', v: mine.path, tone: mine.path === other.path ? 'muted' : 'warn' },
          { k: 'status', v: mine.status != null ? String(mine.status) : 'failed', tone: mine.status === other.status ? 'muted' : statusTone(mine.status) },
        ] },
        { label: 'Response headers', copyLabel: 'response headers', meta: stats.changed + '≠ ' + stats.added + '+', kvs: diffHeaderRows(mine, other) },
        { label: 'Body', copyLabel: 'diffed body', meta: 'differences shaded', code: diffBodyLines(mine, other) },
      ],
    };
  }

  function networkSummaryKvs(detail) {
    const rows = [
      { k: 'request.url', v: detail.request?.url || '' },
      { k: 'request.method', v: detail.method },
      { k: 'response.status', v: detail.status != null ? String(detail.status) : detail.error ? '— (' + detail.error + ')' : '—', tone: statusTone(detail.status) },
    ];
    if (detail.response?.contentType) rows.push({ k: 'response.contentType', v: detail.response.contentType });
    if (detail.correlationId) rows.push({ k: 'correlationId', v: detail.correlationId });
    const tags = Object.entries(detail.tags || {});
    if (tags.length) rows.push({ k: 'tags', v: tags.map(([k, v]) => k + '=' + v).join(', ') });
    return rows;
  }
  function networkHeadersKvs(detail) {
    const rows = headerRows(detail.request?.headers)
      .map((h) => ({ k: '→ ' + h.k, v: h.v }))
      .concat(headerRows(detail.response?.headers).map((h) => ({ k: '← ' + h.k, v: h.v })));
    if (!detail.response) rows.push({ k: '← —', v: 'no response headers' + (detail.error ? ' (' + detail.error + ')' : ''), tone: 'warn' });
    return rows;
  }
  // response.timings carries six independently-nullable phases (a pooled connection skips
  // DNS/connect, a plaintext request skips TLS, a cached response skips all of them). Each
  // present phase gets its own bar; null phases are omitted outright rather than drawn as a
  // 0-length bar, which would read as a measured zero. Tones below are purely categorical
  // (waterfall-style colour coding), not status signals.
  const NETWORK_TIMING_PHASES = [
    { key: 'dnsMs', label: 'DNS lookup', tone: 'strong' },
    { key: 'connectMs', label: 'Connect', tone: 'put' },
    { key: 'tlsMs', label: 'TLS handshake', tone: 'warn' },
    { key: 'sendMs', label: 'Send', tone: 'signal' },
    { key: 'waitMs', label: 'Waiting (TTFB)', tone: 'error' },
    { key: 'receiveMs', label: 'Receive', tone: 'put' },
  ];
  function networkTimingBars(detail) {
    const t = detail.response?.timings;
    const present = t ? NETWORK_TIMING_PHASES.filter((p) => t[p.key] != null) : [];
    if (present.length) {
      const total = present.reduce((sum, p) => sum + t[p.key], 0) || 1;
      return { bars: present.map((p) => ({ label: p.label, val: t[p.key] + ' ms', pct: Math.round((t[p.key] / total) * 100), tone: p.tone })), fallback: false };
    }
    if (detail.durationMs == null) return { bars: null, fallback: false };
    return { bars: [{ label: 'Total duration', val: detail.durationMs + ' ms', pct: 100, tone: 'signal' }], fallback: true };
  }

  function renderNetworkDetail() {
    const pane = $('networkDetailPane');
    const detail = selectedTransactionDetail;
    // Everything in this pane is replaced via innerHTML each render; preserve focus (find input
    // caret, tab/group/action buttons) so keyboard users aren't dumped back to <body>. Scoped to
    // `pane` — in compare/diff mode the two side-by-side panes both emit `data-group-toggle` for
    // like-named groups, so a document-wide restore query could land on the wrong one.
    const focusSnap = captureFocus(pane);
    if (!detail) { renderEmpty(pane, 'network', 'No transaction selected', 'Select a transaction from the list to inspect its redacted metadata.'); return; }
    resetBodyViewers();
    const pinId = networkPinnedId;
    const samePin = pinId === selectedTransactionId;
    const hasPin = Boolean(pinId) && !samePin && networkDetailCache.has(pinId);
    if (networkTab === 'diff' && !pinId) networkTab = 'compare';
    const diffCount = hasPin ? diffHeaderStats(networkDetailCache.get(pinId), detail) : null;
    const relatedFresh = networkRelatedEventsForId === detail.id;
    const tabs = [{ id: 'compare', label: 'Request & response' }]
      .concat(pinId ? [{ id: 'diff', label: 'Diff vs baseline', count: hasPin ? String(diffCount.changed + diffCount.added) : 'pick one' }] : [])
      .concat([
        { id: 'summary', label: 'Summary' },
        { id: 'headers', label: 'Headers', count: String(headerRows(detail.request?.headers).length + headerRows(detail.response?.headers).length) },
        { id: 'timing', label: 'Timing' },
        { id: 'related', label: 'Related events', count: relatedFresh ? String(networkRelatedEvents.length) : undefined },
      ])
      .map((t) => ({ ...t, active: networkTab === t.id }));
    const flagged = isFlagged('network', detail.id);
    const mockRuleId = detail.tags?.mockRuleId;
    const isMocked = detail.tags?.mocked === 'true';
    // Once a transaction was itself served by a mock, "Mock this response" (prefill a new rule
    // from it) is moot — swap it for "Unmock" (disable the rule that served it). If the rule id
    // isn't in tags for some reason, there's nothing actionable to wire up, so drop the action
    // entirely rather than show a dead button.
    const mockAction = isMocked
      ? (mockRuleId ? { id: 'unmock', label: 'Unmock', icon: 'mocks', disabled: !canEditMocks(), title: canEditMocks() ? 'Disable mock rule ' + mockRuleId : 'Sign in and enable the mocks capability' } : null)
      : { id: 'mock', label: 'Mock this response', icon: 'mocks', disabled: !canEditMocks(), title: canEditMocks() ? 'Prefill a mock rule from this response' : 'Sign in and enable the mocks capability' };
    // The rule that served this transaction, looked up from the same cache the Mocks view is
    // built from (loaded on connect — see loadMockRules) rather than a fresh fetch: this render
    // path already runs on every keystroke in the find box, and mockRulesCache is refreshed
    // independently whenever the Mocks view or a save/delete touches it.
    const mockRule = isMocked && mockRuleId ? mockRulesCache.find((r) => r.id === mockRuleId) : null;
    const mockDiff = mockRule ? getMockDiff(mockRule, bodyRawText(detail.response?.body)) : null;
    const mockDiffTotal = mockDiff ? mockDiff.changed + mockDiff.added + mockDiff.removed : 0;
    // Threaded down to the response body's viewer-tree cache key (see buildOrReuseBodyViewerTree)
    // so a diff-highlighted tree is reused across re-renders exactly like a plain one, instead of
    // always being rebuilt from scratch — distinct whenever the rule or its snapshot differs, and
    // stable (so the cache actually hits) across renders where neither changed.
    const mockDiffSig = mockDiff ? (mockRule.id || '') + ':' + mockRule.sourceBodySnapshot : '';
    const actions = [
      { id: 'flag', label: flagged ? 'Flagged as evidence' : 'Flag as evidence', icon: 'flag', on: flagged, title: 'Attach this transaction to the evidence tray (Enter)' },
      // Pin stays out of Simple mode's action row (brief: Flag/Mock/cURL/Clone only) *unless* a
      // baseline is already pinned — once one exists, hiding Pin would strand that state (no way
      // to unpin, or to diff this capture against it, without a mode switch). `fetch` has no such
      // exception; it's advanced-only in every state.
      { id: 'pin', label: samePin ? 'Baseline — unpin' : pinId ? 'Diff against baseline' : 'Pin as baseline', icon: 'star', on: samePin, title: pinId && !samePin ? 'Compare this response against the pinned baseline' : 'Pin this response as the baseline to diff others against (b)', hideInSimple: !pinId },
      { id: 'resend', label: 'Resend', icon: 'refresh', disabled: !canResend(), title: canResend() ? 'Re-execute this exact request through the device (same gating as Composer)' : 'Sign in and enable the composer capability' },
      { id: 'clone', label: 'Clone to composer', icon: 'terminal' },
      ...(mockAction ? [mockAction] : []),
      { id: 'curl', label: 'cURL', icon: 'copy' },
      { id: 'fetch', label: 'fetch', icon: 'copy', hideInSimple: true },
    ];
    const showLayoutToggle = (networkTab === 'compare' || networkTab === 'diff') && (hasPin || networkTab === 'compare');
    const layoutToggle = showLayoutToggle ? { on: !networkSbsStacked, label: networkSbsStacked ? 'Side by side' : 'Stacked', title: networkSbsStacked ? 'Show the two panes side by side' : 'Stack the two panes in one column', icon: 'grid' } : null;
    const head = detailHeadHtml({
      badgeText: detail.method, badgeTone: methodTone(detail.method), title: detail.host + detail.path,
      statusText: detail.status != null ? String(detail.status) : detail.error ? 'FAILED' : '—', sTone: statusTone(detail.status),
      mocked: isMocked,
      facts: [{ k: 'time', v: time(detail.startedAtEpochMs) }, { k: 'duration', v: detail.durationMs != null ? detail.durationMs + ' ms' : '—' }]
        .concat(detail.response?.contentType ? [{ k: 'content type', v: detail.response.contentType }] : [])
        .concat(detail.correlationId ? [{ k: 'correlation', v: detail.correlationId }] : [])
        .concat(mockRuleId ? [{ k: 'mock rule', v: mockRuleId, link: true }] : [])
        .concat(mockDiffTotal > 0 ? [{ k: 'vs original', v: mockDiffTotal + (mockDiffTotal === 1 ? ' field differs' : ' fields differ') + ' from original' }] : []),
      actions, tabs, layoutToggle,
    });
    let findBar = '';
    let bodyHtml;
    if (networkTab === 'compare') {
      const panes = networkComparePanes(detail, networkDetailQuery, mockDiff, mockDiffSig);
      findBar = findBarHtml(networkDetailQuery, countPaneHits(panes));
      bodyHtml = `<div class="sbs-grid${networkSbsStacked ? ' stacked' : ''}">${panes.map(sbsPaneHtml).join('')}</div>`;
    } else if (networkTab === 'diff') {
      if (!hasPin) {
        bodyHtml = `<div class="sbs-grid stacked">${sbsPaneHtml({
          side: 'Baseline pinned · ' + time(detail.startedAtEpochMs), dotTone: 'put', metaTone: 'put',
          meta: (detail.status ?? 'FAILED') + ' · ' + detail.method + ' ' + detail.path,
          actions: [{ id: 'pin', label: 'Unpin', icon: 'star' }],
          groups: [{ label: 'Waiting for a second capture', empty: 'This response is the baseline. Move to another capture with ↑ ↓ (or click a row) and its headers and body will be diffed against this one.' }],
        })}</div>`;
      } else {
        const pinDetail = networkDetailCache.get(pinId);
        const panes = [diffPaneHtml('Baseline · ' + time(pinDetail.startedAtEpochMs), pinDetail, detail, true), diffPaneHtml('Selected · ' + time(detail.startedAtEpochMs), detail, pinDetail, false)];
        bodyHtml = `<div class="sbs-grid${networkSbsStacked ? ' stacked' : ''}">${panes.map(sbsPaneHtml).join('')}</div>`;
      }
    } else if (networkTab === 'summary') {
      bodyHtml = kvGridHtml(networkSummaryKvs(detail), true);
    } else if (networkTab === 'headers') {
      bodyHtml = kvGridHtml(networkHeadersKvs(detail), true);
    } else if (networkTab === 'timing') {
      const { bars, fallback } = networkTimingBars(detail);
      if (!bars) {
        bodyHtml = `<span class="detail-group-empty">This transaction has no completed timing yet.</span>`;
      } else {
        bodyHtml = barsHtml(bars);
        if (fallback) {
          bodyHtml += `<div class="detail-group-empty">Phase-level timing (DNS/connect/TLS/wait/receive) requires the OkHttp event listener to be installed; showing total duration only.</div>`;
        }
      }
    } else {
      // 'related' — a real tab (not a one-shot action that clobbered .detail-body with no way
      // back) so it survives re-render like every other tab. Data is per-transaction and fetched
      // lazily the first time this tab is shown for a given detail.id.
      if (!relatedFresh) {
        bodyHtml = `<span class="detail-group-empty">Loading related timeline events…</span>`;
        loadRelatedEvents(detail.id);
      } else if (networkRelatedEvents.length) {
        bodyHtml = `<div class="block-title">Related timeline events</div>` + codeBlockHtml(formatJsonLines(networkRelatedEvents), true, 'Related timeline events');
      } else {
        bodyHtml = `<span class="detail-group-empty">No related timeline events found for this transaction's correlation id.</span>`;
      }
    }
    bodyHtml += `<div class="detail-footnote">${icon('lock', 'ic-sm')}<span>Headers and bodies are redacted by the on-device allowlist before they reach this browser. Values shown as •••• never left the app.</span></div>`;
    pane.innerHTML = head + findBar + `<div class="detail-body">${bodyHtml}</div>`;
    mountBodyViewers(pane);
    restoreFocus(focusSnap, pane);
  }

  function detailActionText(id, detail) {
    if (id === 'copy-request') {
      const lines = ['Method: ' + detail.method, 'URL: ' + (detail.request?.url || ''), ...headerRows(detail.request?.headers).map((h) => h.k + ': ' + h.v)];
      if (detail.request?.body?.type === 'text') lines.push('', detail.request.body.value);
      return lines.join('\n');
    }
    if (id === 'copy-response') {
      if (!detail.response) return 'No response.';
      const lines = ['Status: ' + detail.status, ...headerRows(detail.response.headers).map((h) => h.k + ': ' + h.v)];
      if (detail.response.body?.type === 'text') lines.push('', detail.response.body.value);
      return lines.join('\n');
    }
    if (id === 'copy-error') return detail.error || 'No error recorded.';
    if (id === 'copy-diff') return JSON.stringify(detail, null, 2);
    return '';
  }

  /** Delegated click/input handling for the Network detail pane — tabs, group collapse, layout
   * toggle, zoom, find, and the header/pane action buttons — wired once (not per render). */
  function wireNetworkDetailPane() {
    const pane = $('networkDetailPane');
    pane.addEventListener('click', (e) => {
      if (e.target.closest('[data-action="toggle-zoom"]')) { toggleDetailZoom(); return; }
      const mockLink = e.target.closest('[data-open-mock-rule]');
      if (mockLink) { openMockRuleFromNetwork(mockLink.dataset.openMockRule); return; }
      const copyBtn = e.target.closest('[data-copy-group]');
      if (copyBtn) {
        const grp = copyBtn.closest('.detail-group');
        const kvCells = [...grp.querySelectorAll('.kv-grid')].flatMap((g) => {
          const cells = [...g.children];
          const out = [];
          for (let i = 0; i < cells.length; i += 2) out.push(cells[i].textContent + ': ' + (cells[i + 1]?.textContent ?? ''));
          return out;
        });
        const codeLines = [...grp.querySelectorAll('.code-line')].map((l) => l.textContent);
        copyToClipboard(kvCells.concat(codeLines).join('\n'), 'The ' + copyBtn.dataset.copyGroup);
        return;
      }
      const groupToggle = e.target.closest('[data-group-toggle]');
      if (groupToggle) {
        const key = groupToggle.dataset.groupToggle;
        networkGroupOpen[key] = groupToggle.getAttribute('aria-expanded') !== 'true';
        renderNetworkDetail();
        return;
      }
      const tabBtn = e.target.closest('[data-detail-tab]');
      if (tabBtn) {
        networkTab = tabBtn.dataset.detailTab;
        if (networkTab === 'diff' && networkPinnedId) fetchTransactionDetail(networkPinnedId).then(renderNetworkDetail);
        renderNetworkDetail();
        return;
      }
      if (e.target.closest('[data-detail-action="toggle-layout"]')) { networkSbsStacked = !networkSbsStacked; renderNetworkDetail(); return; }
      const actionBtn = e.target.closest('[data-detail-action]');
      if (!actionBtn || !selectedTransactionDetail) return;
      const id = actionBtn.dataset.detailAction;
      if (id === 'flag') { const d = selectedTransactionDetail; toggleEvidenceFlag('network', d.id, d.method + ' ' + d.host + d.path); renderNetworkDetail(); }
      else if (id === 'pin') pinNetworkBaseline(selectedTransactionId);
      else if (id === 'resend') resendCapturedRequest();
      else if (id === 'clone') cloneCapturedRequest();
      else if (id === 'mock') mockThisResponse();
      else if (id === 'unmock') unmockNetworkResponse();
      else if (id === 'curl') copyNetworkCurl();
      else if (id === 'fetch') copyNetworkFetch();
      else copyToClipboard(detailActionText(id, selectedTransactionDetail), actionBtn.textContent.trim() || 'Value');
    });
    pane.addEventListener('input', (e) => {
      if (e.target.id === 'detailFindInput') { networkDetailQuery = e.target.value; renderNetworkDetail(); }
    });
  }

  /** "Resend" (network detail actions row, before Clone to composer): re-executes the captured
   * request server-side through the same composer transport Clone-to-composer's eventual "execute"
   * step uses — never a browser fetch — so it carries the exact same gating. The new capture it
   * produces shows up on its own via live-tail (it's captured as any composer execution would be),
   * so this only needs to report the round trip's own outcome. */
  async function resendCapturedRequest() {
    if (!token || !selectedTransactionId || !canResend()) return;
    const method = selectedTransactionDetail?.method || '';
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
      const ok = await openConfirm(
        'Resend without body',
        method + ' bodies are not retained by capture, so this replays the request with an EMPTY body '
          + 'and the redacted stored headers. Use Clone to composer to author the body first.',
        'Resend anyway',
      );
      if (!ok) return;
    }
    const r = await fetch('/api/v1/network/transactions/' + encodeURIComponent(selectedTransactionId) + '/resend', {
      method: 'POST', headers: controlHeaders(),
    });
    if (!r.ok) {
      const body = await r.json().catch(() => ({}));
      toast('Resend failed: ' + (body.code || r.status), 'error');
      return;
    }
    const body = await r.json();
    toast('Resent — new capture: ' + body.response.statusCode + '.');
  }
  async function cloneCapturedRequest() {
    if (!token || !selectedTransactionId) return;
    const r = await fetch('/api/v1/network/transactions/' + encodeURIComponent(selectedTransactionId) + '/curl', { headers: auth() });
    if (!r.ok) return;
    $('composerCurl').value = await r.text();
    renderEmpty(
      $('composerResult'),
      'terminal',
      'Redacted request copied',
      hasSession() ? 'Import it below, or execute it as-is.' : 'Connect this browser to execute or save it.',
    );
    show('composer');
  }
  /** "Mock this response" (network detail actions row): prefills the mock rule dialog from the
   * selected transaction — an exact, regex-escaped path match rather than a loose pattern, since
   * the intent is "replay exactly what I just saw," not a broad rule. Stays on whatever view is
   * currently showing (the dialog is a modal overlay, not a navigation). */
  function mockThisResponse() {
    if (!selectedTransactionDetail || !canEditMocks()) return;
    const d = selectedTransactionDetail;
    const segLabel = (d.path.split('/').filter(Boolean).pop() || 'rule').toLowerCase().replace(/[^a-z0-9._-]+/g, '-');
    const base = 'mock-' + d.method.toLowerCase() + '-' + segLabel;
    const used = new Set(mockRulesCache.map((r) => r.id));
    let n = 1;
    let id = base + '-' + n;
    while (used.has(id)) { n += 1; id = base + '-' + n; }
    let body = d.response?.body?.type === 'text' ? d.response.body.value : '';
    try { body = JSON.stringify(JSON.parse(body), null, 2); } catch { /* non-JSON response body — keep verbatim */ }
    // Transport headers describe the ORIGINAL encoded payload; the mock interceptor sits above
    // OkHttp's BridgeInterceptor, so nothing recomputes them and a stale Content-Length would ship.
    const transportHeaders = new Set(['content-length', 'content-encoding', 'transfer-encoding']);
    const headers = headerRows(d.response?.headers)
      .filter((h) => !transportHeaders.has(h.k.toLowerCase()))
      .map((h) => h.k + ': ' + h.v)
      .join('\n');
    openMockRuleDialog({
      id, method: d.method, host: d.host, path: '^' + escapeRegExp(d.path) + '$',
      status: d.status ?? 200, headers, body, sourceBodySnapshot: body || null,
    });
    toast('Prefilled from the redacted capture preview — review the body before saving.');
  }
  /** "Unmock" (network detail actions row): replaces "Mock this response" once the viewed
   * transaction was itself served by a mock rule — disabling that rule is the useful action here,
   * not prefilling a new one from an already-mocked response. Reuses setMockRuleEnabled's POST to
   * .../enabled (same controlHeaders + toast + rule-list refresh) and re-renders this detail pane
   * too, so its state stays in sync with the rules list. */
  async function unmockNetworkResponse() {
    const mockRuleId = selectedTransactionDetail?.tags?.mockRuleId;
    if (!mockRuleId || !canEditMocks()) return;
    await setMockRuleEnabled(mockRuleId, false);
    renderNetworkDetail();
  }
  /** The "mock rule: <id>" fact link in the network detail head — navigates to Mocks and briefly
   * flashes the matching row so the operator can find it in a possibly long list. */
  async function openMockRuleFromNetwork(id) {
    // The flash is applied by loadMockRules AFTER its own render — applying it here would race
    // the un-awaited refresh show('mocks') kicks off and get wiped mid-animation.
    mockPendingHighlightId = id;
    await show('mocks');
  }
  async function copyToClipboard(text, label) {
    try {
      await navigator.clipboard.writeText(text);
      toast(label + ' copied to clipboard.');
    } catch {
      toast('Clipboard access was denied.', 'error');
    }
  }
  async function copyNetworkCurl() {
    if (!token || !selectedTransactionId) return;
    const r = await fetch('/api/v1/network/transactions/' + encodeURIComponent(selectedTransactionId) + '/curl', { headers: auth() });
    if (!r.ok) return;
    await copyToClipboard(await r.text(), 'cURL');
  }
  function headersToObject(headers) {
    if (!headers) return {};
    if (Array.isArray(headers)) {
      const out = {};
      headers.forEach((h) => {
        if (h && typeof h.name === 'string') out[h.name] = h.value;
      });
      return out;
    }
    return headers;
  }
  function buildFetchSnippet(detail) {
    const headers = headersToObject(detail.request?.headers);
    const body = detail.request?.body;
    const lines = ['fetch(' + JSON.stringify(detail.request?.url || '') + ', {', '  method: ' + JSON.stringify(detail.method || 'GET') + ','];
    if (Object.keys(headers).length) lines.push('  headers: ' + JSON.stringify(headers, null, 2).split('\n').join('\n  ') + ',');
    if (body && body.type === 'text') lines.push('  body: ' + JSON.stringify(body.value) + (body.truncated ? ' /* truncated */' : '') + ',');
    else if (body && body.type === 'binary') lines.push('  // body omitted: binary content (' + body.length + ' bytes)');
    lines.push('});');
    return lines.join('\n');
  }
  async function copyNetworkFetch() {
    if (!selectedTransactionDetail) return;
    await copyToClipboard(buildFetchSnippet(selectedTransactionDetail), 'fetch snippet');
  }
  /**
   * Exports either the checkbox selection (`networkSelectedIds`, if non-empty) or every captured
   * transaction (up to the server's 500-row bound) otherwise — the network list's own toolbar
   * buttons (always "everything") and the selection bar's buttons (always "the selection") both
   * funnel through here. A non-empty selection is POSTed (ids in the form body) rather than sent as
   * `?id=` query params: up to 500 ids at real-world id length can exceed a GET request line's
   * practical size, so the server exposes the same selection resolution behind `POST` too (see
   * DevConsoleKtorModule's `/api/v1/network/har`/`postman` POST routes) — CSRF-gated like every
   * other POST here, unlike the read-only GET-everything path below.
   */
  async function downloadNetworkExport(path, filename) {
    if (!token) return;
    const useSelection = networkSelectedIds.size > 0;
    const r = useSelection
      ? await fetch(path, {
          method: 'POST',
          headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams([...networkSelectedIds].map((id) => ['id', id])),
        })
      : await fetch(path + '?' + new URLSearchParams({ limit: '500' }), { headers: auth() });
    if (!r.ok) { toast('Export failed: ' + r.status, 'error'); return; }
    if (r.headers.get('X-DevConsole-Export-Truncated') === 'true') {
      const count = r.headers.get('X-DevConsole-Export-Count');
      toast('Export truncated to ' + (count ?? 'the first 500') + ' transactions — narrow the filter or selection to get the rest.', 'error');
    }
    const blob = await r.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }
  async function downloadHar() {
    await downloadNetworkExport('/api/v1/network/har', 'devconsole-network.har');
  }
  async function downloadPostman() {
    await downloadNetworkExport('/api/v1/network/postman', 'devconsole-network.postman_collection.json');
  }
  /** Backs the 'related' tab in renderNetworkDetail. Guarded against duplicate in-flight fetches
   * for the same id (a re-render can happen — e.g. focus restore — while the request is still
   * out) and only re-renders if that tab/transaction is still what's on screen when it resolves. */
  async function loadRelatedEvents(id) {
    if (!token || !id || networkRelatedEventsLoadingId === id) return;
    networkRelatedEventsLoadingId = id;
    const r = await fetch('/api/v1/network/transactions/' + encodeURIComponent(id) + '/related-events', { headers: auth() });
    networkRelatedEvents = r.ok ? (await r.json()).data || [] : [];
    networkRelatedEventsForId = id;
    if (networkRelatedEventsLoadingId === id) networkRelatedEventsLoadingId = null;
    if (networkTab === 'related' && selectedTransactionId === id) renderNetworkDetail();
  }

  // ================================================================
  // Timeline bookmarks / notes
  // ================================================================
  async function toggleBookmark(id) {
    if (!token) return;
    const e = events.find((x) => x.id === id);
    if (!e) return;
    const willBookmark = !bookmarkedIds.has(id);
    const method = willBookmark ? 'POST' : 'DELETE';
    const r = await fetch('/api/v1/events/' + encodeURIComponent(id) + '/bookmark', { method, headers: controlHeaders() });
    if (r.ok) {
      if (willBookmark) bookmarkedIds.add(id);
      else bookmarkedIds.delete(id);
      selectedEventId = id;
      render();
    }
  }
  async function saveNote() {
    if (!token || !selectedEventId) return;
    const input = $('eventNoteInput');
    if (!input) return;
    const r = await fetch('/api/v1/events/' + encodeURIComponent(selectedEventId) + '/note', { method: 'PUT', headers: controlHeaders(), body: input.value });
    toast(r.ok ? 'Note saved.' : 'Save failed: ' + r.status, r.ok ? undefined : 'error');
  }

  // ================================================================
  // View navigation
  // ================================================================
  function show(view) {
    // Flush any still-armed evidence-report autosave debounce before anything else navigation
    // does. Without this: type in the report → switch away before the 750ms debounce fires →
    // switch back to Evidence within the window → loadEvidence() below (via the `evidence` loader)
    // overwrites `evidenceReport` from the server and re-renders the input with the pre-edit
    // value → the still-armed timer then fires, reads that reverted DOM value, PUTs it, and
    // reports "Saved" — silently discarding the real edit behind a false success indicator.
    // Cancelling the timer and immediately re-running its callback reads the *current* (still
    // correct, not yet re-rendered) DOM value synchronously — saveEvidenceReport() doesn't await
    // anything until its own fetch call, so this capture happens before any of the view-swap or
    // loader code below runs, even though the save's network round trip itself isn't awaited here.
    if (evidenceReportSaveTimer) {
      clearTimeout(evidenceReportSaveTimer);
      evidenceReportSaveTimer = null;
      saveEvidenceReport();
    }
    // `f` toggles detail-zoom from any view, including the card views where it does nothing —
    // clear it on every navigation so switching views never leaves the list pane hidden with no
    // explanation.
    document.body.classList.remove('detail-zoom');
    // A stale tab (or a stale hash link) navigating into a view whose backing category the host
    // disabled must not render a dead panel that only ever shows a 403 -- redirect to Overview
    // instead, same as show() already does for a view that no longer exists at all.
    if (view !== 'overview' && isViewDisabledByCapture(view)) view = 'overview';
    const views = {
      overview: 'overviewView',
      timeline: 'timelineView',
      network: 'networkView',
      socket: 'socketView',
      state: 'stateView',
      remoteConfig: 'remoteConfigView',
      preferences: 'preferencesView',
      database: 'databaseView',
      files: 'filesView',
      push: 'pushView',
      crashes: 'crashesView',
      composer: 'composerView',
      mocks: 'mocksView',
      captureRules: 'captureRulesView',
      sdkHealth: 'sdkHealthView',
      evidence: 'evidenceView',
      session: 'sessionView',
    };
    Object.entries(views).forEach(([name, id]) => $(id).classList.toggle('active', name === view));
    // The newly visible view's splitter (if any) was measured while display:none — re-sync its
    // aria values now that it has real geometry.
    syncSplitterAria();
    const nav = {
      overview: 'viewOverview',
      timeline: 'viewTimeline',
      network: 'viewNetwork',
      socket: 'viewSockets',
      state: 'viewState',
      remoteConfig: 'viewRemoteConfig',
      preferences: 'viewPreferences',
      database: 'viewDatabase',
      files: 'viewFiles',
      push: 'viewPush',
      crashes: 'viewCrashes',
      composer: 'viewComposer',
      mocks: 'viewMocks',
      captureRules: 'viewCaptureRules',
      sdkHealth: 'viewSdkHealth',
      evidence: 'viewEvidence',
      session: 'viewSession',
    };
    document.querySelectorAll('#viewNav button').forEach((button) => {
      const active = nav[view] === button.id;
      button.classList.toggle('active', active);
      if (active) button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
    });
    currentView = view;
    // This nav already loads the view fresh below, so any live-tail refresh queued while it was
    // off-screen (or paused) would just be redundant work once resumed.
    if (liveTailDirty[view] !== undefined) liveTailDirty[view] = false;
    // Navigating to a view that lives under the collapsed rail "Advanced" group (e.g. Clone-to-
    // composer, or the evidence tray's links) must reveal it — mode never blocks navigation.
    if (uiMode === 'simple' && RAIL_ADVANCED_VIEWS.has(view) && !railAdvancedOpen) setRailAdvancedOpen(true);
    // Returns the loader's promise (view === 'network' → loadNetwork()'s) so callers that need to
    // act once the view has real data — e.g. "Open in Network" locating the matching transaction —
    // can `await show(view)` instead of guessing at a delay.
    const loaders = {
      overview: loadOverview,
      timeline: load,
      network: loadNetwork,
      socket: loadSockets,
      state: loadState,
      remoteConfig: loadRemoteConfig,
      preferences: loadPreferences,
      database: loadDatabases,
      files: loadFileRoots,
      push: loadPush,
      crashes: loadCrashes,
      composer: () => Promise.all([loadComposerCollections(), loadComposerHostAllowlist()]),
      mocks: loadMocks,
      captureRules: loadCaptureRules,
      sdkHealth: loadSdkHealth,
      session: loadSession,
      evidence: loadEvidence,
    };
    // Defense in depth alongside the redirect above: a disabled view's loader is skipped even if
    // `show()` was ever called with one directly (e.g. a future caller that bypasses the redirect),
    // so nothing ever fetches a route the server now answers 403 CATEGORY_DISABLED for.
    if (isViewDisabledByCapture(view)) return undefined;
    return loaders[view] ? loaders[view]() : undefined;
  }

  // ================================================================
  // Session-code exchange
  // ================================================================
  async function beginSessionCode() {
    const code = new URLSearchParams(location.hash.slice(1)).get('code');
    if (!code) {
      setStatus('Open DevConsole on device → More → scan the QR', "Open DevConsole on the device, tap More, then scan the QR or copy the link — it shows on screen, never in logcat");
      return;
    }
    await exchangeSessionCode(code, 'hash');
  }

  /**
   * Populates the topbar host line from real server info — app id/version (overview) and the
   * bound LAN address (meta) — instead of a hard-coded example string.
   */
  async function refreshHostLine() {
    const el = $('hostLine');
    if (!el) return;
    if (!token) {
      el.textContent = "Not connected — see the device's More screen for the connect QR";
      return;
    }
    try {
      const [overviewRes, metaRes] = await Promise.all([fetch('/api/v1/overview', { headers: auth() }), fetch('/api/v1/meta', { headers: auth() })]);
      const overview = overviewRes.ok ? await overviewRes.json() : null;
      const meta = metaRes.ok ? await metaRes.json() : null;
      const parts = [overview?.app?.packageName, overview?.app?.versionName, meta?.endpoint ? `${meta.endpoint.host}:${meta.endpoint.port}` : location.host].filter(Boolean);
      if (parts.length) el.textContent = parts.join(' · ');
    } catch {
      /* keep previous text on transient failure */
    }
  }

  // ================================================================
  // Wiring
  // ================================================================
  function wireEvents() {
    initTheme();
    initMode();
    initSplitters();
    updateRecordButton();

    $('themeToggle').onclick = toggleTheme;
    $('modeToggle').onclick = toggleMode;
    $('railAdvancedToggle').onclick = () => setRailAdvancedOpen(!railAdvancedOpen);
    wireRowList('events', { onSelect: (id) => { selectedEventId = id; render(); } });
    wireRowList('transactions', { onSelect: (id) => showTransaction(id), onCheck: (id) => toggleNetworkSelection(id) });
    wireRowList('sockets', { onSelect: (id) => { socketSelectedIndex = Number(id); renderSockets(); }, rowSelector: '.row, .trace-row' });
    wireRowList('pushEvents', { onSelect: (id) => { selectedPushIndex = Number(id); renderPush(); } });
    wireRowList('crashesList', { onSelect: (id) => { selectedCrashId = id; renderCrashes(); } });
    wireNetworkDetailPane();
    wireSocketDetailPane();
    wirePushDetailPane();
    wireCrashDetailPane();
    wireCodeFullscreen();
    document.addEventListener('click', closeDropdownsOnOutsideClick);
    // Body viewer Pretty/Raw toggle + toolbar (network + socket detail panes) — one delegated
    // listener rather than rewiring it inside every renderNetworkDetail()/renderSocketDetail()
    // call.
    document.addEventListener('click', (e) => {
      const modeBtn = e.target.closest('[data-body-mode]');
      if (modeBtn) {
        const wrap = modeBtn.closest('.body-viewer');
        if (!wrap) return;
        const mode = modeBtn.dataset.bodyMode;
        wrap.querySelectorAll('[data-body-mode]').forEach((b) => b.classList.toggle('active', b === modeBtn));
        const prettyEl = wrap.querySelector('.body-viewer-pretty');
        const rawEl = wrap.querySelector('.body-viewer-raw');
        if (prettyEl) prettyEl.hidden = mode !== 'pretty';
        if (rawEl) rawEl.hidden = mode !== 'raw';
        return;
      }
      // Pretty mode has its own copy/fullscreen affordances (S15b) — Raw mode already gets both
      // from codeBlockHtml, but Pretty (the default view) previously had neither. Both act on the
      // exact original raw text from pendingBodyViewers, never the tree's formatted rendering.
      const copyBtn = e.target.closest('[data-body-copy]');
      if (copyBtn) {
        const entry = pendingBodyViewers.get(copyBtn.dataset.bodyCopy);
        if (entry) copyToClipboard(entry.raw, 'Body');
        return;
      }
      const fsBtn = e.target.closest('[data-body-fullscreen]');
      if (fsBtn) {
        const rawBlock = fsBtn.closest('.body-viewer')?.querySelector('.body-viewer-raw .code-block');
        openCodeFullscreen(rawBlock, fsBtn.dataset.bodyTitle);
        return;
      }
    });

    // ---- Timeline --------------------------------------------------------------
    $('search').addEventListener('input', render);
    wireSeg($('timelineSeveritySeg'), (value) => { timelineSeverityFilter = value; render(); });
    $('timelineSourceSeg').addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-value]');
      if (!btn) return;
      timelineSourceFilter = btn.dataset.value;
      $('timelineSourceSeg').querySelectorAll('button').forEach((b) => {
        b.classList.toggle('active', b === btn);
        b.setAttribute('aria-pressed', String(b === btn));
      });
      render();
    });
    $('timelineBookmarkOnly').onclick = () => { timelineBookmarkedOnly = !timelineBookmarkedOnly; updateBookmarkOnlyButton(); render(); };
    $('eventDetail').addEventListener('click', (e) => { if (e.target.closest('[data-action="toggle-zoom"]')) toggleDetailZoom(); });
    $('eventDetail').addEventListener('input', (e) => { if (e.target.id === 'eventDetailFindInput') { eventDetailQuery = e.target.value; renderEventDetail(); } });

    // ---- Network -----------------------------------------------------------------
    wireSeg($('networkStatusSeg'), (value) => { networkStatusFilter = value; applyNetworkFilters(); });
    wireSeg($('networkMethodSeg'), (value) => { networkMethodFilter = value; applyNetworkFilters(); });
    $('networkRefresh').onclick = () => loadNetwork();
    $('networkNewest').onclick = () => loadNetwork();
    $('networkOlder').onclick = () => networkCursor && loadNetwork(networkCursor);
    $('networkHarDownload').onclick = downloadHar;
    $('networkPostmanDownload').onclick = downloadPostman;
    $('networkSelectAllVisible').onclick = () => toggleSelectAllVisibleNetwork();
    $('networkSelectionBar').addEventListener('click', (e) => {
      const btn = e.target.closest('[data-sel-action]');
      if (!btn) return;
      const action = btn.dataset.selAction;
      if (action === 'clear') clearNetworkSelection();
      else if (action === 'select-all-filtered') selectAllMatchingFilterNetwork();
      else if (action === 'export-har') downloadHar();
      else if (action === 'export-postman') downloadPostman();
    });

    $('timelineNewest').onclick = () => load();
    $('timelineOlder').onclick = () => timelineCursor && load(timelineCursor);

    // ---- WebSockets ----------------------------------------------------------------
    wireSeg($('socketFrameTypeSeg'), (value) => { socketFrameTypeFilter = value; loadSocketMessages(); });
    wireSeg($('socketDirectionSeg'), (value) => { socketDirectionFilter = value; loadSocketMessages(); });
    // Protocol changes which connections are in scope too, not just which messages, so it re-runs
    // loadSockets() (connections + messages) rather than loadSocketMessages() alone.
    wireSeg($('socketProtocolSeg'), (value) => { socketProtocolFilter = value || 'all'; loadSockets(); });
    wireSeg($('socketModeSeg'), (value) => { socketMode = value; renderSockets(); });
    $('socketApply').onclick = () => loadSocketMessages();
    $('socketClearFilters').onclick = () => {
      ['socketSearch', 'socketFrom', 'socketTo', 'socketError'].forEach((id) => ($(id).value = ''));
      socketFrameTypeFilter = ''; socketDirectionFilter = ''; socketSelectedConnIds.clear();
      $('socketFrameTypeSeg').querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.value === ''));
      $('socketDirectionSeg').querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.value === ''));
      // The protocol filter is pinned (see applyCaptureCategoryGating) whenever its own toolbar
      // group is hidden -- clearing filters must never silently let a host-disabled protocol's
      // data back into the list, so only reset it when the control is actually user-facing.
      if (!$('socketProtocolGroup').hidden) {
        socketProtocolFilter = 'all';
        $('socketProtocolSeg').querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.value === ''));
      }
      loadSockets();
    };

    wireCardGrid('stateCards', {
      onToggle: (key) => {
        const f = cachedFlags.find((x) => x.key === key);
        if (!f || !f.mutable) return;
        setFlagValue(key, f.value === 'true' ? 'false' : 'true');
      },
      onTree: (id) => showState(id),
      onButton: (id) => {
        if (id === 'copy-state') copyStateJson();
        else if (id.startsWith('mutator:')) runStateMutation(selectedStateProviderId, id.slice('mutator:'.length));
      },
    });

    // ---- Push ------------------------------------------------------------------
    $('pushSearch').addEventListener('input', renderPush);
    $('pushProviderSeg').addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-value]');
      if (!btn) return;
      pushProviderFilter = btn.dataset.value;
      refreshPushChips();
      renderPush();
    });
    $('pushRefresh').onclick = loadPush;
    $('pushSimulate').onclick = simulatePush;

    // ---- Crashes -----------------------------------------------------------------
    $('crashesSearch').addEventListener('input', renderCrashes);
    wireSeg($('crashesKindSeg'), (value) => { crashKindFilter = value; renderCrashes(); });
    $('crashesRefresh').onclick = loadCrashes;

    $('viewOverview').onclick = () => show('overview');
    $('viewTimeline').onclick = () => show('timeline');
    $('viewNetwork').onclick = () => show('network');
    $('viewSockets').onclick = () => show('socket');
    $('viewState').onclick = () => show('state');
    $('viewRemoteConfig').onclick = () => show('remoteConfig');
    $('viewPush').onclick = () => show('push');
    $('viewComposer').onclick = () => show('composer');
    $('viewMocks').onclick = () => show('mocks');
    $('viewCaptureRules').onclick = () => show('captureRules');
    $('viewPreferences').onclick = () => show('preferences');
    $('viewDatabase').onclick = () => show('database');
    $('viewFiles').onclick = () => show('files');
    $('viewSdkHealth').onclick = () => show('sdkHealth');
    $('viewSession').onclick = () => show('session');

    $('remoteConfigRefresh').onclick = loadRemoteConfig;
    $('remoteConfigSearch').oninput = (e) => { remoteConfigQuery = e.target.value; renderRemoteConfigCards(); };
    wireSeg($('remoteConfigSourceSeg'), (value) => { remoteConfigSourceFilter = value; renderRemoteConfigCards(); });
    wireCardGrid('remoteConfigCards', { onRow: openRemoteConfigValue });
    $('remoteConfigValueClose').onclick = closeRemoteConfigValue;
    $('remoteConfigValueCopy').onclick = copyRemoteConfigValue;
    $('remoteConfigValueModal').addEventListener('click', (e) => { if (e.target.id === 'remoteConfigValueModal') closeRemoteConfigValue(); });
    wireSeg($('remoteConfigValueSeg'), (value) => { remoteConfigValueMode = value; renderRemoteConfigValueModal(); });

    $('prefFile').onchange = (e) => renderPreferenceFile(e.target.value);

    $('dbName').onchange = (e) => {
      dbSelectedTable = '';
      dbLastRows = null;
      $('dbBadge').textContent = e.target.value ? e.target.value : 'no database';
      loadTables(e.target.value);
    };
    $('dbTableSearch').addEventListener('input', (e) => { dbTableQuery = e.target.value; renderDbCards(); });
    wireSeg($('dbSortSeg'), (value) => { dbSort = value; renderDbCards(); });

    $('fileRoot').onchange = (e) => loadFileListing(e.target.value, '');
    $('fileGo').onclick = () => loadFileListing($('fileRoot').value, $('filePath').value);
    $('fileCreate').onclick = () => createFile();


    $('composerRun').onclick = runComposer;
    $('composerImport').onclick = importCurl;
    $('composerCollectionSave').onclick = saveComposerCollection;
    $('composerCollections').onclick = loadComposerCollections;

    $('mockDisable').onclick = toggleMockEngine;
    $('mockNewRule').onclick = () => openMockRuleDialog(null);
    $('mockRuleModalClose').onclick = closeMockRuleDialog;
    $('mockRuleCancel').onclick = closeMockRuleDialog;
    $('mockRuleSave').onclick = saveMockRuleDialog;
    $('mockRuleBodyFormat').onclick = formatMockRuleBody;
    $('mockRuleBody').addEventListener('input', refreshMockBodyEditor);
    $('mockRuleBody').addEventListener('blur', formatMockRuleBody);
    wireCardGrid('mockRuleList', {
      onToggle: (id) => {
        const checked = document.querySelector(`[data-card-toggle="${CSS.escape(id)}"]`)?.getAttribute('aria-checked') === 'true';
        setMockRuleEnabled(id, !checked);
      },
      onDelete: (id) => deleteMockRule(id),
      onEdit: (id) => editMockRule(id),
    });

    $('captureRuleSave').onclick = saveCaptureRule;
    wireCaptureRuleActions();

    // Note: the timeline note "Save note" button lives inside the event detail pane and is
    // wired directly in renderEventDetail() since it only exists once an event is selected.
    $('streamLive').onclick = () => setPaused(false);
    $('streamPaused').onclick = () => setPaused(true);

    // ---- Topbar: rail toggle, evidence tray, quick export -----------------------
    $('viewEvidence').onclick = () => show('evidence');
    $('viewCrashes').onclick = () => show('crashes');
    $('captureScreenshot').onclick = () => captureScreenshot();
    updateEvidenceUi();

    function setRailHidden(next) {
      railHidden = next;
      document.body.classList.toggle('rail-hidden', railHidden);
      const btn = $('railToggle');
      btn.setAttribute('aria-pressed', String(railHidden));
      btn.title = railHidden ? 'Show the view list (\\)' : 'Hide the view list and give the content full width (\\)';
    }
    $('railToggle').onclick = () => setRailHidden(!railHidden);

    $('quickExport').onclick = async () => {
      if (!token) return;
      const btn = $('quickExport');
      btn.disabled = true;
      try {
        const r = await fetch('/api/v1/exports', {
          method: 'POST',
          headers: { ...controlHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({ scope: 'WHOLE_SESSION', metadataOnly: 'false' }),
        });
        if (!r.ok) {
          toast('Export failed: ' + r.status, 'error');
          return;
        }
        const blob = await r.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'devconsole-session.zip';
        a.click();
        URL.revokeObjectURL(url);
        toast('Session ZIP exported.');
      } finally {
        btn.disabled = !token;
      }
    };

    // ---- Keyboard shortcuts --------------------
    const viewFilterInput = {
      timeline: 'search', network: 'networkSearch', socket: 'socketSearch', push: 'pushSearch', crashes: 'crashesSearch',
    };
    let shortcutsOpenerEl = null;
    // Only the close button is focusable in this modal's body (the shortcut grid is static
    // text), so the trap keeps Tab/Shift+Tab pinned there — same shape as codeFullscreenKeydown.
    function shortcutsKeydown(e) {
      if (e.key !== 'Tab') return;
      e.preventDefault();
      $('shortcutsClose').focus();
    }
    function toggleShortcuts(force) {
      const overlay = $('shortcutsModal');
      if (!overlay) return;
      const willShow = force !== undefined ? force : overlay.hidden;
      overlay.hidden = !willShow;
      $('shortcutsBtn').classList.toggle('active', willShow);
      if (willShow) {
        shortcutsOpenerEl = document.activeElement;
        document.addEventListener('keydown', shortcutsKeydown);
        $('shortcutsClose').focus();
      } else {
        document.removeEventListener('keydown', shortcutsKeydown);
        shortcutsOpenerEl?.focus?.();
        shortcutsOpenerEl = null;
      }
    }
    $('shortcutsBtn').onclick = () => toggleShortcuts();
    $('shortcutsClose').onclick = () => toggleShortcuts(false);
    $('shortcutsModal').addEventListener('click', (e) => { if (e.target.id === 'shortcutsModal') toggleShortcuts(false); });
    document.addEventListener('keydown', (e) => {
      // Never hijack keys while the operator is typing in a field or a dialog is up.
      const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName || '');
      const dialogOpen = [...document.querySelectorAll('.modal-overlay')].some((m) => !m.hidden);
      if (e.key === 'Escape' && !$('shortcutsModal').hidden) { toggleShortcuts(false); return; }
      if (e.key === 'Escape' && !$('codeFullscreenModal').hidden) { closeCodeFullscreen(); return; }
      // Service (Network) and Connection (WebSockets) dropdowns trap nothing today — Escape
      // closes whichever is open and returns focus to the button that opened it, mirroring the
      // existing composedPath-based outside-click closer instead of replacing it.
      if (e.key === 'Escape' && networkHostDropOpen) { networkHostDropOpen = false; renderNetworkHostDrop(); $('networkHostDropBtn')?.focus(); return; }
      if (e.key === 'Escape' && socketConnDropOpen) { socketConnDropOpen = false; renderSocketConnDrop(); $('socketConnDropBtn')?.focus(); return; }
      if (typing || dialogOpen || e.metaKey || e.ctrlKey || e.altKey) return;
      if (e.key === '/') { const id = viewFilterInput[currentView]; if (id && $(id)) { e.preventDefault(); $(id).focus(); } }
      else if (e.key === '?') { e.preventDefault(); toggleShortcuts(); }
      else if (e.key === 'p' || e.key === 'P') { setPaused(!paused); }
      else if (e.key === 't' || e.key === 'T') { $('themeToggle')?.click(); }
      else if (e.key === 'a' || e.key === 'A') { toggleMode(); }
      else if (e.key === '\\') { setRailHidden(!railHidden); }
      else if (e.key === 'f' || e.key === 'F') { e.preventDefault(); toggleDetailZoom(); }
      else if (e.key === 'ArrowDown' || e.key === 'j' || e.key === 'ArrowUp' || e.key === 'k') {
        e.preventDefault();
        document.dispatchEvent(new CustomEvent('devconsole:step', { detail: { direction: e.key === 'ArrowUp' || e.key === 'k' ? -1 : 1, view: currentView } }));
      } else if (e.key === 'Enter') {
        // Only when a list row *itself* has focus (arrow/j-k nav lands focus there) — `matches`,
        // not `closest`: if focus is on the nested flag <button> instead, that button already
        // handles its own Enter natively, and also matching here would double-fire the toggle.
        const onRow = document.activeElement?.matches?.('.row, .trace-row');
        if (onRow) { e.preventDefault(); viewControllers[currentView]?.flagCurrent?.(); }
      } else if (e.key === 'b' || e.key === 'B') {
        if (currentView === 'network') { e.preventDefault(); viewControllers.network.pinCurrent(); }
      }
    });

    // Without a valid `code=` hash there's no token to exchange — on a first-ever load that's
    // just the static empty state, but after a successful connect the hash is stripped
    // (exchangeSessionCode, source !== 'manual') and the token lives only in the `token`
    // variable above, so a plain browser refresh would otherwise land here with no session and
    // no way to get one back. Render the "Connect this browser" card (session-code input +
    // Connect button, built by renderOverview/loadOverview when hasSession() is false) so the
    // user can always paste a fresh code, instead of leaving the static placeholder text in
    // index.html as a dead end.
    if (location.hash.includes('code=')) beginSessionCode();
    else loadOverview();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', wireEvents);
  else wireEvents();
})();
