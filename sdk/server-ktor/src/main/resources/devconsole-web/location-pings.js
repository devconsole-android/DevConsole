/**
 * @author Zakaria
 * @since 26/10/26
 */
(() => {
  'use strict';

  const STATUS_LABELS = { sent: 'Sent', failed: 'Failed', dropped: 'Dropped on device', offline: 'Offline' };
  const SHORT_STATUS_LABELS = { sent: 'Sent', failed: 'Failed', dropped: 'Dropped', offline: 'Offline' };
  const STATUS_COLOR_TOKENS = { sent: '--success', failed: '--error', dropped: '--warn', offline: '--muted' };
  const TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
  const MAX_LIST_ROWS = 2000;
  const MAX_GAP_LABELS = 400;
  const AUTO_REFRESH_MS = 10000;
  const FOCUS_ZOOM = 17;
  const EARTH_RADIUS_METERS = 6371000;

  const $ = (id) => document.getElementById(id);

  let dashboard = null;
  let controlsBound = false;
  let overview = null;
  let detail = null;
  let selectedStream = '';
  let selectedFile = '';
  let statusFilter = 'all';
  let appStateFilter = 'all';
  let selectedIndex = -1;
  let openPoint = null;
  let fittedTrackKey = '';
  let autoRefreshTimer = null;

  let map = null;
  let routeLayer = null;
  let pointLayer = null;
  let labelLayer = null;
  const markersByIndex = new Map();

  async function load(context) {
    dashboard = context;
    bindOnce();
    ensureMap();
    requestAnimationFrame(() => map?.invalidateSize());
    await refresh();
  }

  async function refresh() {
    if (!dashboard.hasSession()) {
      overview = null;
      detail = null;
      renderOverview();
      renderTrack();
      return;
    }
    const response = await fetch('/api/v1/location-pings', { headers: dashboard.auth() });
    if (!response.ok) {
      overview = null;
      detail = null;
      renderOverview();
      renderTrack(response.status === 403 ? 'Network capture is disabled for this app run.' : 'Location pings unavailable: ' + response.status);
      return;
    }
    overview = await response.json();
    renderOverview();
    await loadSelectedTrack();
  }

  async function loadSelectedTrack() {
    if (!selectedFile) {
      detail = null;
      renderTrack();
      return;
    }
    const query = new URLSearchParams({ stream: selectedStream, file: selectedFile });
    const response = await fetch('/api/v1/location-pings/track?' + query, { headers: dashboard.auth() });
    detail = response.ok ? await response.json() : null;
    renderTrack();
  }

  function renderOverview() {
    const tracks = overview?.tracks || [];
    const streams = overview?.streams || [];
    dashboard.setNavCount(tracks.length);
    $('lpBadge').textContent = badgeText();
    renderStreamSwitches(streams);
    chooseSelection(streams, tracks);
    renderStreamPicker(streams, tracks);
    renderTrackPicker(tracks);
    const schema = overview?.schema || {};
    $('lpTrackLabel').textContent = schema.groupLabel || 'Group';
    $('lpFixGapsToggle').hidden = !schema.hasFixTime;
    $('lpFixGapHead').hidden = !schema.hasFixTime;
    $('lpAppStateGroup').hidden = !schema.hasAppState;
    $('lpDeleteTrack').disabled = !selectedFile;
    $('lpDeleteAll').disabled = tracks.length === 0;
    const storageError = overview?.storage?.lastError;
    showNote(storageError || '');
  }

  function badgeText() {
    if (!overview) return '';
    if (!overview.configured) return 'not configured';
    const count = overview.tracks.length;
    const storage = overview.storage;
    return count + ' track' + (count === 1 ? '' : 's') + ' · ' + formatBytes(storage.usedBytes) + ' of ' + formatBytes(storage.maxBytes);
  }

  function renderStreamSwitches(streams) {
    const container = $('lpStreams');
    if (!streams.length) {
      container.innerHTML = '';
      return;
    }
    container.innerHTML = '<span class="toolbar-group-label">Recording</span>' + streams.map((stream) =>
      `<label class="lp-stream${stream.enabled ? ' on' : ''}"><input type="checkbox" data-stream="${dashboard.esc(stream.name)}"${stream.enabled ? ' checked' : ''}>` +
      `<span class="lp-stream-name">${dashboard.esc(stream.name)}</span><span class="lp-stream-path">${dashboard.esc(stream.path)}</span></label>`,
    ).join('');
  }

  function chooseSelection(streams, tracks) {
    const streamNames = availableStreamNames(streams, tracks);
    if (!streamNames.includes(selectedStream)) {
      selectedStream = tracks[0]?.stream || streamNames[0] || '';
    }
    const streamTracks = tracks.filter((track) => track.stream === selectedStream);
    if (!streamTracks.some((track) => track.file === selectedFile)) {
      selectedFile = streamTracks[0]?.file || '';
    }
  }

  function availableStreamNames(streams, tracks) {
    const names = streams.map((stream) => stream.name);
    tracks.forEach((track) => { if (!names.includes(track.stream)) names.push(track.stream); });
    return names;
  }

  function renderStreamPicker(streams, tracks) {
    const names = availableStreamNames(streams, tracks);
    $('lpStream').innerHTML = names.map((name) => {
      const count = tracks.filter((track) => track.stream === name).length;
      return `<option value="${dashboard.esc(name)}"${name === selectedStream ? ' selected' : ''}>${dashboard.esc(name)} (${count})</option>`;
    }).join('');
    $('lpStream').disabled = names.length === 0;
  }

  function renderTrackPicker(tracks) {
    const streamTracks = tracks.filter((track) => track.stream === selectedStream);
    $('lpTrack').innerHTML = streamTracks.length
      ? streamTracks.map((track) =>
        `<option value="${dashboard.esc(track.file)}"${track.file === selectedFile ? ' selected' : ''}>` +
        `${dashboard.esc(track.groupId)} · ${dashboard.esc(track.capturedDate)} · ${formatBytes(track.sizeBytes)}</option>`,
      ).join('')
      : '<option value="">No pings recorded</option>';
    $('lpTrack').disabled = streamTracks.length === 0;
  }

  function filteredPoints() {
    const points = detail?.points || [];
    return points.filter((point) =>
      (statusFilter === 'all' || point.status === statusFilter) &&
      (appStateFilter === 'all' || appStateOf(point) === appStateFilter));
  }

  function appStateOf(point) {
    const value = (point.appState || '').toLowerCase();
    if (value.includes('fore')) return 'foreground';
    if (value.includes('back')) return 'background';
    return value;
  }

  function renderTrack(problem) {
    const points = filteredPoints();
    renderEmptyState(problem, points);
    renderMap(points);
    renderList(points);
    renderMetrics(points);
    if (detail?.truncated) showNote('This track is longer than the dashboard reads at once; only its first pings are shown.');
  }

  function renderEmptyState(problem, points) {
    const message = emptyMessage(problem, points);
    $('lpEmpty').hidden = !message;
    if (message) {
      $('lpEmpty').innerHTML = `<div class="empty-state"><svg class="ic empty-icon" aria-hidden="true"><use href="#dc-pin"/></svg>` +
        `<div class="empty-title">${dashboard.esc(message.title)}</div><div class="empty-sub">${dashboard.esc(message.sub)}</div></div>`;
    }
  }

  function emptyMessage(problem, points) {
    if (!dashboard.hasSession()) {
      return { title: 'Not connected yet', sub: 'Open DevConsole on the device → More → scan the QR or paste the code in Overview to see recorded location pings.' };
    }
    if (problem) return { title: 'Location pings', sub: problem };
    if (overview && !overview.configured) {
      return { title: 'No ping streams configured', sub: 'Register your location-ping endpoints with DevConsoleConfig.withLocationPingTracking(...) — see docs/LOCATION_PINGS.md.' };
    }
    if (!detail) {
      return { title: 'No pings recorded yet', sub: 'Switch a stream on under Recording, then use the app. Pings are written to the device as they are sent.' };
    }
    if (!points.length) return { title: 'No pings match', sub: 'Every ping in this track is hidden by the current filters.' };
    return null;
  }

  function ensureMap() {
    if (map || !window.L) return;
    map = L.map('lpMap', { preferCanvas: true, zoomControl: true, worldCopyJump: true, attributionControl: false }).setView([0, 0], 2);
    L.tileLayer(TILE_URL, { maxZoom: 19 }).addTo(map);
    routeLayer = L.layerGroup().addTo(map);
    pointLayer = L.layerGroup().addTo(map);
    labelLayer = L.layerGroup().addTo(map);
    map.on('moveend', renderGapLabels);
    new MutationObserver(() => { if (detail) renderTrack(); })
      .observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  }

  function renderMap(points) {
    if (!map) return;
    routeLayer.clearLayers();
    pointLayer.clearLayers();
    markersByIndex.clear();
    if (!points.length) {
      labelLayer.clearLayers();
      return;
    }
    const latLngs = points.map((point) => [point.lat, point.lng]);
    L.polyline(latLngs, { color: cssColor('--signal'), weight: 3, opacity: 0.7 }).addTo(routeLayer);
    const lastIndex = points[points.length - 1].index;
    const firstIndex = points[0].index;
    points.forEach((point) => {
      const endpoint = point.index === firstIndex || point.index === lastIndex;
      const marker = L.circleMarker([point.lat, point.lng], markerStyle(point, endpoint))
        .on('click', () => selectPoint(point.index, { openDetails: true }))
        .bindTooltip(() => markerTooltip(point), { direction: 'top', offset: [0, -6] })
        .addTo(pointLayer);
      markersByIndex.set(point.index, marker);
    });
    fitTrackOnce(latLngs);
    renderGapLabels();
  }

  function markerStyle(point, endpoint) {
    const selected = point.index === selectedIndex;
    return {
      radius: selected ? 9 : endpoint ? 7 : 5,
      color: selected ? cssColor('--ink') : endpoint ? cssColor('--panel') : cssColor('--ground'),
      weight: selected || endpoint ? 3 : 1,
      fillColor: cssColor(STATUS_COLOR_TOKENS[point.status] || '--muted'),
      fillOpacity: 0.95,
    };
  }

  function markerTooltip(point) {
    return dashboard.esc('#' + (point.index + 1) + ' · ' + statusText(point) + ' · ' + formatClock(point.deviceTime));
  }

  function fitTrackOnce(latLngs) {
    const key = selectedStream + '/' + selectedFile + '/' + statusFilter + '/' + appStateFilter;
    if (key === fittedTrackKey) return;
    fittedTrackKey = key;
    map.fitBounds(L.latLngBounds(latLngs), { padding: [32, 32], maxZoom: FOCUS_ZOOM });
  }

  function renderGapLabels() {
    if (!map) return;
    labelLayer.clearLayers();
    const showDevice = $('lpDeviceGaps').checked;
    const showFix = $('lpFixGaps').checked && !$('lpFixGapsToggle').hidden;
    if (!showDevice && !showFix) return;
    const bounds = map.getBounds();
    filteredPoints()
      .filter((point) => bounds.contains([point.lat, point.lng]))
      .slice(0, MAX_GAP_LABELS)
      .forEach((point) => {
        const parts = [];
        if (showDevice) parts.push('d ' + formatGap(point.deviceGapSeconds));
        if (showFix) parts.push('f ' + formatGap(point.fixGapSeconds));
        L.tooltip({ permanent: true, direction: 'right', offset: [6, 0], className: 'lp-gap-label' })
          .setLatLng([point.lat, point.lng])
          .setContent(dashboard.esc(parts.join(' · ')))
          .addTo(labelLayer);
      });
  }

  function renderList(points) {
    const rows = points.slice(0, MAX_LIST_ROWS).map((point) => {
      const appState = appStateOf(point);
      const appStateMark = appState === 'foreground' ? 'F' : appState === 'background' ? 'B' : '';
      return `<button type="button" class="lp-row${point.index === selectedIndex ? ' selected' : ''}" data-index="${point.index}">` +
        `<span class="lp-row-index">#${point.index + 1}<i class="lp-dot lp-${point.status}"></i><span class="lp-row-app">${appStateMark}</span></span>` +
        `<span>${formatGap(point.deviceGapSeconds)}</span>` +
        `<span${$('lpFixGapHead').hidden ? ' hidden' : ''}>${formatGap(point.fixGapSeconds)}</span>` +
        `<span class="lp-row-status lp-text-${point.status}">${dashboard.esc(shortStatusText(point))}</span></button>`;
    });
    if (points.length > MAX_LIST_ROWS) {
      rows.push(`<div class="lp-list-more">${points.length - MAX_LIST_ROWS} more pings — narrow the filter to list them.</div>`);
    }
    $('lpList').innerHTML = rows.join('');
  }

  function renderMetrics(points) {
    const strip = $('lpMetrics');
    const all = detail?.points || [];
    strip.hidden = all.length === 0;
    if (!all.length) return;
    const count = (status) => points.filter((point) => point.status === status).length;
    const sent = count('sent');
    const failed = count('failed');
    const dropped = count('dropped');
    const offline = count('offline');
    const delivered = points.length ? Math.round((sent / points.length) * 100) : 0;
    const span = points.length > 1 ? (points[points.length - 1].deviceTime - points[0].deviceTime) / 1000 : 0;
    strip.innerHTML = [
      metric('Pings shown', String(points.length), points.length === all.length ? '' : 'of ' + all.length, 'signal'),
      metric('Delivered', delivered + '%', sent + ' sent', delivered >= 95 ? 'success' : 'warn'),
      metric('Not delivered', String(failed + dropped + offline), failed + ' failed · ' + dropped + ' dropped · ' + offline + ' offline', failed + dropped + offline ? 'error' : 'muted'),
      metric('Route', formatDistance(routeMeters(points)), 'over ' + formatGap(span), 'put'),
    ].join('');
  }

  function metric(label, value, sub, tone) {
    return `<div class="metric"><div class="metric-label">${dashboard.esc(label)}</div><div class="metric-value-row">` +
      `<span class="metric-value tone-text-${tone}">${dashboard.esc(value)}</span>${sub ? `<span class="metric-sub">${dashboard.esc(sub)}</span>` : ''}</div></div>`;
  }

  function selectPoint(index, { openDetails = false, focus = false } = {}) {
    const previous = selectedIndex;
    selectedIndex = index;
    const point = (detail?.points || []).find((candidate) => candidate.index === index);
    restyleMarker(previous);
    restyleMarker(index);
    markersByIndex.get(index)?.bringToFront();
    document.querySelectorAll('#lpList .lp-row.selected').forEach((row) => row.classList.remove('selected'));
    const row = document.querySelector(`#lpList .lp-row[data-index="${index}"]`);
    row?.classList.add('selected');
    row?.scrollIntoView({ block: 'nearest' });
    if (point && focus) map?.setView([point.lat, point.lng], Math.max(map.getZoom(), FOCUS_ZOOM));
    if (point && openDetails) openPingDetails(point);
  }

  function restyleMarker(index) {
    const marker = markersByIndex.get(index);
    const point = (detail?.points || []).find((candidate) => candidate.index === index);
    if (!marker || !point) return;
    const points = filteredPoints();
    const endpoint = point.index === points[0]?.index || point.index === points[points.length - 1]?.index;
    marker.setStyle(markerStyle(point, endpoint));
    marker.setRadius(markerStyle(point, endpoint).radius);
  }

  function openPingDetails(point) {
    openPoint = point;
    $('lpPingTitleText').textContent = 'Ping #' + (point.index + 1);
    const rows = [
      ['Status', `<i class="lp-dot lp-${point.status}"></i>${dashboard.esc(statusText(point))}`],
      ['Device time', dashboard.esc(formatDateTime(point.deviceTime))],
      ['Device time gap', dashboard.esc(formatGap(point.deviceGapSeconds))],
    ];
    if (point.fixTime != null) {
      rows.push(['Fix time', dashboard.esc(formatDateTime(point.fixTime))]);
      rows.push(['Fix time gap', dashboard.esc(formatGap(point.fixGapSeconds))]);
    }
    if (point.appState) rows.push(['App state', dashboard.esc(point.appState)]);
    rows.push(['Latitude', dashboard.esc(String(point.lat))], ['Longitude', dashboard.esc(String(point.lng))]);
    Object.entries(point.fields || {}).forEach(([key, value]) => rows.push([dashboard.esc(key), dashboard.esc(value)]));
    $('lpPingRows').innerHTML = rows.map(([label, value]) =>
      `<div class="lp-ping-row"><span class="lp-ping-label">${label}</span><span class="lp-ping-value">${value}</span></div>`).join('');
    $('lpPingModal').hidden = false;
    $('lpPingClose').focus();
  }

  function closePingDetails() {
    $('lpPingModal').hidden = true;
    openPoint = null;
  }

  async function copyOpenPoint() {
    if (!openPoint) return;
    await dashboard.copyToClipboard(JSON.stringify(openPoint, null, 2), 'Ping #' + (openPoint.index + 1));
  }

  async function toggleStream(name, enabled) {
    const response = await fetch('/api/v1/location-pings/streams/' + encodeURIComponent(name), {
      method: 'PUT',
      headers: dashboard.controlHeaders(),
      body: String(enabled),
    });
    if (response.ok) {
      dashboard.toast((enabled ? 'Recording ' : 'Stopped recording ') + name + ' pings.');
    } else {
      dashboard.toast('Could not change ' + name + ': ' + response.status, 'error');
    }
    await refresh();
  }

  async function deleteSelectedTrack() {
    if (!selectedFile) return;
    const ok = await dashboard.openConfirm('Delete this track?', `${selectedFile} is removed from the device. This cannot be undone.`, 'Delete track');
    if (!ok) return;
    const query = new URLSearchParams({ stream: selectedStream, file: selectedFile });
    const response = await fetch('/api/v1/location-pings/track?' + query, { method: 'DELETE', headers: dashboard.controlHeaders() });
    dashboard.toast(response.ok ? 'Track deleted.' : 'Could not delete the track: ' + response.status, response.ok ? undefined : 'error');
    selectedFile = '';
    await refresh();
  }

  async function deleteEverything() {
    const ok = await dashboard.openConfirm('Delete every recorded ping?', 'All location-ping files are removed from the device, for every stream. This cannot be undone.', 'Delete all');
    if (!ok) return;
    const response = await fetch('/api/v1/location-pings', { method: 'DELETE', headers: dashboard.controlHeaders() });
    if (response.ok) {
      const result = await response.json();
      dashboard.toast(result.filesLeft ? `Deleted ${result.filesDeleted} files; ${result.filesLeft} could not be removed.` : `Deleted ${result.filesDeleted} files.`, result.filesLeft ? 'error' : undefined);
    } else {
      dashboard.toast('Could not delete pings: ' + response.status, 'error');
    }
    selectedFile = '';
    await refresh();
  }

  function setAutoRefresh(enabled) {
    clearInterval(autoRefreshTimer);
    autoRefreshTimer = enabled
      ? setInterval(() => { if (dashboard.isVisible() && dashboard.hasSession()) refresh(); }, AUTO_REFRESH_MS)
      : null;
  }

  function showNote(text) {
    $('lpNote').hidden = !text;
    $('lpNote').textContent = text;
  }

  function wireSegment(id, onChange) {
    $(id).addEventListener('click', (event) => {
      const button = event.target.closest('button[data-value]');
      if (!button) return;
      $(id).querySelectorAll('button').forEach((candidate) => candidate.classList.toggle('active', candidate === button));
      onChange(button.dataset.value);
    });
  }

  function bindOnce() {
    if (controlsBound) return;
    controlsBound = true;
    $('lpStreams').addEventListener('change', (event) => {
      const input = event.target.closest('input[data-stream]');
      if (input) toggleStream(input.dataset.stream, input.checked);
    });
    $('lpStream').onchange = (event) => {
      selectedStream = event.target.value;
      selectedFile = '';
      renderOverview();
      loadSelectedTrack();
    };
    $('lpTrack').onchange = (event) => {
      selectedFile = event.target.value;
      selectedIndex = -1;
      $('lpDeleteTrack').disabled = !selectedFile;
      loadSelectedTrack();
    };
    wireSegment('lpStatusSeg', (value) => { statusFilter = value; renderTrack(); });
    wireSegment('lpAppStateSeg', (value) => { appStateFilter = value; renderTrack(); });
    $('lpDeviceGaps').onchange = renderGapLabels;
    $('lpFixGaps').onchange = renderGapLabels;
    $('lpAutoRefresh').onchange = (event) => setAutoRefresh(event.target.checked);
    $('lpRefresh').onclick = refresh;
    $('lpDeleteTrack').onclick = deleteSelectedTrack;
    $('lpDeleteAll').onclick = deleteEverything;
    $('lpList').addEventListener('click', (event) => {
      const row = event.target.closest('.lp-row');
      if (!row) return;
      const index = Number(row.dataset.index);
      if (index === selectedIndex) {
        const point = (detail?.points || []).find((candidate) => candidate.index === index);
        if (point) openPingDetails(point);
      } else {
        selectPoint(index, { focus: true });
      }
    });
    $('lpPingClose').onclick = closePingDetails;
    $('lpPingModal').addEventListener('click', (event) => { if (event.target === $('lpPingModal')) closePingDetails(); });
    $('lpPingModal').addEventListener('keydown', (event) => { if (event.key === 'Escape') closePingDetails(); });
    $('lpPingCopy').onclick = copyOpenPoint;
    $('lpPingShow').onclick = () => {
      const point = openPoint;
      closePingDetails();
      if (point) selectPoint(point.index, { focus: true });
    };
  }

  function statusText(point) {
    if (point.statusCode != null) return 'HTTP ' + point.statusCode;
    return STATUS_LABELS[point.status] || point.status;
  }

  function shortStatusText(point) {
    if (point.statusCode != null) return 'HTTP ' + point.statusCode;
    return SHORT_STATUS_LABELS[point.status] || point.status;
  }

  function cssColor(token) {
    return getComputedStyle(document.documentElement).getPropertyValue(token).trim() || '#888';
  }

  function routeMeters(points) {
    let meters = 0;
    for (let i = 1; i < points.length; i += 1) meters += metersBetween(points[i - 1], points[i]);
    return meters;
  }

  function metersBetween(from, to) {
    const toRadians = (degrees) => (degrees * Math.PI) / 180;
    const latDelta = toRadians(to.lat - from.lat);
    const lngDelta = toRadians(to.lng - from.lng);
    const haversine = Math.sin(latDelta / 2) ** 2 +
      Math.cos(toRadians(from.lat)) * Math.cos(toRadians(to.lat)) * Math.sin(lngDelta / 2) ** 2;
    return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
  }

  function formatDistance(meters) {
    return meters < 1000 ? Math.round(meters) + ' m' : (meters / 1000).toFixed(2) + ' km';
  }

  function formatGap(seconds) {
    if (seconds == null) return '–';
    const rounded = Math.round(seconds * 10) / 10;
    if (Math.abs(rounded) < 60) return (Number.isInteger(rounded) ? rounded : rounded.toFixed(1)) + 's';
    const whole = Math.round(Math.abs(seconds));
    const sign = seconds < 0 ? '-' : '';
    if (whole < 3600) return sign + Math.floor(whole / 60) + 'm ' + String(whole % 60).padStart(2, '0') + 's';
    return sign + Math.floor(whole / 3600) + 'h ' + String(Math.floor((whole % 3600) / 60)).padStart(2, '0') + 'm';
  }

  function formatBytes(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  function formatClock(epochMs) {
    return new Date(epochMs).toLocaleTimeString();
  }

  function formatDateTime(epochMs) {
    return new Date(epochMs).toLocaleString() + ' (' + epochMs + ')';
  }

  window.DevConsoleLocationPings = { load };
})();
