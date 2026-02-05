const state = {
  file: null,
  messages: [],
  filtered: [],
  selectedIndex: null,
  indexes: {
    swarms: new Set(),
    kinds: new Set(),
    types: new Set(),
    roles: new Set(),
    instances: new Set(),
    origins: new Set(),
    threads: new Map(), // correlationId -> {count, firstTsMs, swarms:Set}
  },
};

const customSelects = new Map();

const els = {
  fileInput: document.getElementById("fileInput"),
  resetBtn: document.getElementById("resetBtn"),
  dropZone: document.getElementById("dropZone"),
  fileName: document.getElementById("fileName"),
  parsedCount: document.getElementById("parsedCount"),
  errorCount: document.getElementById("errorCount"),
  filteredCount: document.getElementById("filteredCount"),
  filterSwarm: document.getElementById("filterSwarm"),
  filterKind: document.getElementById("filterKind"),
  filterType: document.getElementById("filterType"),
  filterRole: document.getElementById("filterRole"),
  filterInstance: document.getElementById("filterInstance"),
  filterOrigin: document.getElementById("filterOrigin"),
  filterCorrelation: document.getElementById("filterCorrelation"),
  filterRoutingKey: document.getElementById("filterRoutingKey"),
  clearFiltersBtn: document.getElementById("clearFiltersBtn"),
  threads: document.getElementById("threads"),
  timeline: document.getElementById("timeline"),
  details: document.getElementById("details"),
  selectedSummary: document.getElementById("selectedSummary"),
  copyBodyBtn: document.getElementById("copyBodyBtn"),
};

function syntaxHighlightJson(json) {
  return String(json).replace(
    /(\"(?:\\u[a-fA-F0-9]{4}|\\[^u]|[^\\\"])*\"\\s*:)|(\"(?:\\u[a-fA-F0-9]{4}|\\[^u]|[^\\\"])*\")|\\b(true|false)\\b|\\bnull\\b|-?\\d+(?:\\.\\d+)?(?:[eE][+\\-]?\\d+)?|[{}\\[\\],]/g,
    (m, key, str, bool) => {
      if (key) return `<span class="jsonKey">${escapeHtml(m)}</span>`;
      if (str) return `<span class="jsonString">${escapeHtml(m)}</span>`;
      if (bool) return `<span class="jsonBoolean">${escapeHtml(m)}</span>`;
      if (m === "null") return `<span class="jsonNull">null</span>`;
      if (/^-?\d/.test(m)) return `<span class="jsonNumber">${escapeHtml(m)}</span>`;
      return `<span class="jsonPunc">${escapeHtml(m)}</span>`;
    }
  );
}

function ensureCustomSelect(select) {
  if (customSelects.has(select)) {
    return customSelects.get(select);
  }

  select.classList.add("nativeHidden");

  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "cselectBtn";
  btn.setAttribute("aria-haspopup", "listbox");
  btn.setAttribute("aria-expanded", "false");

  const valueSpan = document.createElement("span");
  valueSpan.className = "value";
  const chevSpan = document.createElement("span");
  chevSpan.className = "chev";
  chevSpan.textContent = "▾";

  btn.appendChild(valueSpan);
  btn.appendChild(chevSpan);

  // Insert button right after the native select (within label)
  select.insertAdjacentElement("afterend", btn);

  const api = {
    select,
    btn,
    valueSpan,
    open: false,
    menuEl: null,
    cleanup: () => {},
  };

  function syncLabel() {
    const selected = select.selectedOptions?.[0]?.textContent ?? select.value ?? "(all)";
    valueSpan.textContent = selected;
  }

  function closeMenu() {
    if (!api.open) return;
    api.open = false;
    btn.setAttribute("aria-expanded", "false");
    if (api.menuEl) {
      api.menuEl.remove();
      api.menuEl = null;
    }
    api.cleanup?.();
    api.cleanup = () => {};
  }

  function openMenu() {
    if (api.open) return;
    api.open = true;
    btn.setAttribute("aria-expanded", "true");

    const rect = btn.getBoundingClientRect();
    const menu = document.createElement("div");
    menu.className = "cselectMenu";
    menu.style.left = `${Math.round(rect.left)}px`;
    menu.style.top = `${Math.round(rect.bottom + 6)}px`;
    menu.style.width = `${Math.round(rect.width)}px`;

    const itemsWrap = document.createElement("div");
    itemsWrap.className = "items";

    const currentValue = select.value;
    for (const opt of Array.from(select.options)) {
      const item = document.createElement("button");
      item.type = "button";
      item.className = "cselectItem";
      item.setAttribute("role", "option");
      const isActive = opt.value === currentValue;
      if (isActive) item.classList.add("active");

      const t = document.createElement("span");
      t.textContent = opt.textContent ?? opt.value;
      const check = document.createElement("span");
      check.className = "check";
      check.textContent = isActive ? "✓" : "";

      item.appendChild(t);
      item.appendChild(check);
      item.addEventListener("click", () => {
        select.value = opt.value;
        select.dispatchEvent(new Event("change", { bubbles: true }));
        syncLabel();
        closeMenu();
      });
      itemsWrap.appendChild(item);
    }

    menu.appendChild(itemsWrap);
    document.body.appendChild(menu);
    api.menuEl = menu;

    const onDocDown = (e) => {
      if (e.target === btn || btn.contains(e.target)) return;
      if (api.menuEl && (e.target === api.menuEl || api.menuEl.contains(e.target))) return;
      closeMenu();
    };
    const onKey = (e) => {
      if (e.key === "Escape") closeMenu();
    };
    const onReposition = () => {
      if (!api.open || !api.menuEl) return;
      const r = btn.getBoundingClientRect();
      api.menuEl.style.left = `${Math.round(r.left)}px`;
      api.menuEl.style.top = `${Math.round(r.bottom + 6)}px`;
      api.menuEl.style.width = `${Math.round(r.width)}px`;
    };

    document.addEventListener("mousedown", onDocDown, true);
    document.addEventListener("keydown", onKey, true);
    window.addEventListener("resize", onReposition);
    window.addEventListener("scroll", onReposition, true);
    api.cleanup = () => {
      document.removeEventListener("mousedown", onDocDown, true);
      document.removeEventListener("keydown", onKey, true);
      window.removeEventListener("resize", onReposition);
      window.removeEventListener("scroll", onReposition, true);
    };

    // Initial focus behaviour
    itemsWrap.querySelector(".cselectItem.active")?.focus?.();
  }

  btn.addEventListener("click", () => {
    if (api.open) closeMenu();
    else openMenu();
  });

  select.addEventListener("change", () => {
    syncLabel();
  });

  syncLabel();

  api.syncLabel = syncLabel;
  api.closeMenu = closeMenu;

  customSelects.set(select, api);
  return api;
}

function ensureAllCustomSelects() {
  for (const el of [els.filterSwarm, els.filterKind, els.filterType, els.filterRole, els.filterInstance]) {
    ensureCustomSelect(el);
  }
}

function trimIsoToMillis(iso) {
  if (!iso || typeof iso !== "string") return null;
  // Example: 2026-01-28T15:37:27.114318901Z -> 2026-01-28T15:37:27.114Z
  const z = iso.endsWith("Z") ? "Z" : "";
  const base = z ? iso.slice(0, -1) : iso;
  const dot = base.indexOf(".");
  if (dot === -1) return z ? iso : `${iso}Z`;
  const head = base.slice(0, dot);
  const frac = base.slice(dot + 1).replace(/[^\d]/g, "");
  const ms = (frac + "000").slice(0, 3);
  return `${head}.${ms}${z || "Z"}`;
}

function toMillis(iso) {
  const trimmed = trimIsoToMillis(iso);
  if (!trimmed) return null;
  const t = Date.parse(trimmed);
  return Number.isFinite(t) ? t : null;
}

function fmtTime(ms) {
  if (!ms) return "—";
  const d = new Date(ms);
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  const ss = String(d.getSeconds()).padStart(2, "0");
  const mss = String(d.getMilliseconds()).padStart(3, "0");
  return `${hh}:${mm}:${ss}.${mss}`;
}

function safeString(v) {
  if (v === null || v === undefined) return "";
  return String(v);
}

function normaliseMessage(entry) {
  const recordedAt = entry.timestamp || null;
  const routingKey = entry.routingKey || null;
  const headers = entry.headers || {};

  let body = entry.body;
  if (typeof body === "string") {
    body = JSON.parse(body);
  }

  const kind = body?.kind ?? null;
  const type = body?.type ?? null;
  const origin = body?.origin ?? null;

  const scope = body?.scope ?? {};
  const swarmId = scope?.swarmId ?? null;
  const role = scope?.role ?? null;
  const instance = scope?.instance ?? null;

  const correlationId = body?.correlationId ?? null;
  const idempotencyKey = body?.idempotencyKey ?? null;

  const bodyTsMs = toMillis(body?.timestamp);
  const recordedTsMs = toMillis(recordedAt);
  const tsMs = bodyTsMs ?? recordedTsMs ?? null;

  return {
    recordedAt,
    routingKey,
    headers,
    body,
    tsMs,
    kind,
    type,
    origin,
    scope: { swarmId, role, instance },
    correlationId,
    idempotencyKey,
  };
}

function resetAll() {
  state.file = null;
  state.messages = [];
  state.filtered = [];
  state.selectedIndex = null;
  state.indexes = {
    swarms: new Set(),
    kinds: new Set(),
    types: new Set(),
    roles: new Set(),
    instances: new Set(),
    origins: new Set(),
    threads: new Map(),
  };

  els.fileName.textContent = "—";
  els.parsedCount.textContent = "0";
  els.errorCount.textContent = "0";
  els.filteredCount.textContent = "0";
  els.resetBtn.disabled = true;
  els.clearFiltersBtn.disabled = true;
  els.copyBodyBtn.disabled = true;
  els.selectedSummary.textContent = "—";
  els.details.textContent = "Select a timeline row…";
  els.details.classList.add("empty");

  setSelectOptions(els.filterSwarm, ["(all)"]);
  setSelectOptions(els.filterKind, ["(all)"]);
  setSelectOptions(els.filterType, ["(all)"]);
  setSelectOptions(els.filterRole, ["(all)"]);
  setSelectOptions(els.filterInstance, ["(all)"]);
  ensureAllCustomSelects();
  for (const el of [els.filterSwarm, els.filterKind, els.filterType, els.filterRole, els.filterInstance]) {
    customSelects.get(el)?.syncLabel?.();
    customSelects.get(el)?.closeMenu?.();
  }
  els.filterOrigin.value = "";
  els.filterCorrelation.value = "";
  els.filterRoutingKey.value = "";

  renderThreads();
  renderTimeline();
}

function setSelectOptions(select, options, selected) {
  select.innerHTML = "";
  for (const opt of options) {
    const o = document.createElement("option");
    o.value = opt;
    o.textContent = opt;
    select.appendChild(o);
  }
  if (selected && options.includes(selected)) {
    select.value = selected;
  } else {
    select.value = options[0] ?? "";
  }
}

function buildIndexes() {
  const idx = state.indexes;
  idx.swarms.clear();
  idx.kinds.clear();
  idx.types.clear();
  idx.roles.clear();
  idx.instances.clear();
  idx.origins.clear();
  idx.threads.clear();

  for (const m of state.messages) {
    if (m.scope.swarmId) idx.swarms.add(m.scope.swarmId);
    if (m.kind) idx.kinds.add(m.kind);
    if (m.type) idx.types.add(m.type);
    if (m.scope.role) idx.roles.add(m.scope.role);
    if (m.scope.instance) idx.instances.add(m.scope.instance);
    if (m.origin) idx.origins.add(m.origin);

    if (m.correlationId) {
      const key = m.correlationId;
      const existing =
        idx.threads.get(key) || { count: 0, firstTsMs: null, swarms: new Set() };
      existing.count += 1;
      if (!existing.firstTsMs || (m.tsMs && m.tsMs < existing.firstTsMs)) {
        existing.firstTsMs = m.tsMs;
      }
      if (m.scope.swarmId) {
        existing.swarms.add(m.scope.swarmId);
      }
      idx.threads.set(key, existing);
    }
  }
}

function hydrateFilterOptions() {
  const idx = state.indexes;

  const sortStr = (a, b) => String(a).localeCompare(String(b));
  setSelectOptions(els.filterSwarm, ["(all)", ...Array.from(idx.swarms).sort(sortStr)]);
  setSelectOptions(els.filterKind, ["(all)", ...Array.from(idx.kinds).sort(sortStr)]);
  setSelectOptions(els.filterType, ["(all)", ...Array.from(idx.types).sort(sortStr)]);
  setSelectOptions(els.filterRole, ["(all)", ...Array.from(idx.roles).sort(sortStr)]);
  setSelectOptions(
    els.filterInstance,
    ["(all)", ...Array.from(idx.instances).sort(sortStr)]
  );

  ensureAllCustomSelects();
  for (const el of [els.filterSwarm, els.filterKind, els.filterType, els.filterRole, els.filterInstance]) {
    customSelects.get(el)?.syncLabel?.();
    customSelects.get(el)?.closeMenu?.();
  }
}

function currentFilters() {
  const toVal = (sel) => (sel.value === "(all)" ? null : sel.value);
  return {
    swarmId: toVal(els.filterSwarm),
    kind: toVal(els.filterKind),
    type: toVal(els.filterType),
    role: toVal(els.filterRole),
    instance: toVal(els.filterInstance),
    originContains: safeString(els.filterOrigin.value).trim() || null,
    correlationContains: safeString(els.filterCorrelation.value).trim() || null,
    rkContains: safeString(els.filterRoutingKey.value).trim() || null,
  };
}

function applyFilters() {
  const f = currentFilters();
  const originNeedle = f.originContains?.toLowerCase() ?? null;
  const corrNeedle = f.correlationContains?.toLowerCase() ?? null;
  const rkNeedle = f.rkContains?.toLowerCase() ?? null;

  const filtered = [];
  for (const m of state.messages) {
    if (f.swarmId && m.scope.swarmId !== f.swarmId) continue;
    if (f.kind && m.kind !== f.kind) continue;
    if (f.type && m.type !== f.type) continue;
    if (f.role && m.scope.role !== f.role) continue;
    if (f.instance && m.scope.instance !== f.instance) continue;
    if (originNeedle && !safeString(m.origin).toLowerCase().includes(originNeedle)) continue;
    if (corrNeedle && !safeString(m.correlationId).toLowerCase().includes(corrNeedle)) continue;
    if (rkNeedle && !safeString(m.routingKey).toLowerCase().includes(rkNeedle)) continue;
    filtered.push(m);
  }

  state.filtered = filtered;
  els.filteredCount.textContent = String(filtered.length);
  els.clearFiltersBtn.disabled = state.messages.length === 0;

  renderThreads();
  renderTimeline();
  clearSelection();
}

function clearSelection() {
  state.selectedIndex = null;
  els.selectedSummary.textContent = "—";
  els.copyBodyBtn.disabled = true;
  els.details.textContent = "Select a timeline row…";
  els.details.classList.add("empty");
  document.querySelectorAll("#timeline tr.active").forEach((tr) => tr.classList.remove("active"));
}

function renderThreads() {
  const f = currentFilters();
  const corrNeedle = safeString(f.correlationContains || "").trim().toLowerCase();

  if (state.messages.length === 0) {
    els.threads.classList.add("empty");
    els.threads.textContent = "Load a file first.";
    return;
  }

  const filteredMsgs = state.filtered.length ? state.filtered : state.messages;
  const groups = new Map();
  for (const m of filteredMsgs) {
    if (!m.correlationId) continue;
    const g = groups.get(m.correlationId) || { count: 0, firstTsMs: null, swarmIds: new Set() };
    g.count += 1;
    if (!g.firstTsMs || (m.tsMs && m.tsMs < g.firstTsMs)) g.firstTsMs = m.tsMs;
    if (m.scope.swarmId) g.swarmIds.add(m.scope.swarmId);
    groups.set(m.correlationId, g);
  }

  let items = Array.from(groups.entries()).map(([id, g]) => ({
    id,
    count: g.count,
    firstTsMs: g.firstTsMs,
    swarmIds: Array.from(g.swarmIds),
  }));
  if (corrNeedle) {
    items = items.filter((x) => x.id.toLowerCase().includes(corrNeedle));
  }
  items.sort((a, b) => (a.firstTsMs ?? 0) - (b.firstTsMs ?? 0));

  els.threads.innerHTML = "";
  els.threads.classList.remove("empty");

  if (items.length === 0) {
    els.threads.classList.add("empty");
    els.threads.textContent = "No correlation threads in current filter.";
    return;
  }

  for (const item of items.slice(0, 200)) {
    const div = document.createElement("div");
    div.className = "thread";
    div.dataset.correlationId = item.id;
    div.innerHTML = `
      <div>
        <div class="id">${escapeHtml(item.id)}</div>
        <div class="meta">${fmtTime(item.firstTsMs)} • swarms: ${escapeHtml(item.swarmIds.join(", ") || "—")}</div>
      </div>
      <div class="count">${item.count}</div>
    `;
    div.addEventListener("click", () => {
      els.filterCorrelation.value = item.id;
      applyFilters();
      setActiveThread(item.id);
    });
    els.threads.appendChild(div);
  }
}

function setActiveThread(correlationId) {
  document.querySelectorAll(".thread.active").forEach((x) => x.classList.remove("active"));
  const el = els.threads.querySelector(`.thread[data-correlation-id="${cssEscape(correlationId)}"]`);
  if (el) el.classList.add("active");
}

function renderTimeline() {
  const items = state.filtered.length ? state.filtered : state.messages;
  els.timeline.innerHTML = "";

  if (state.messages.length === 0) {
    els.timeline.classList.add("empty");
    els.timeline.innerHTML = `<tr><td colspan="5" class="emptyCell">Load a recording to start.</td></tr>`;
    return;
  }

  if (items.length === 0) {
    els.timeline.classList.add("empty");
    els.timeline.innerHTML = `<tr><td colspan="5" class="emptyCell">No messages match current filters.</td></tr>`;
    return;
  }

  els.timeline.classList.remove("empty");

  // MVP: render first N rows to keep things snappy.
  const MAX = 1200;
  const slice = items.slice(0, MAX);
  for (let i = 0; i < slice.length; i += 1) {
    const m = slice[i];
    const tr = document.createElement("tr");
    tr.dataset.index = String(i);
    const scopeText = `${m.scope.swarmId ?? "—"} / ${m.scope.role ?? "—"} / ${m.scope.instance ?? "—"}`;
    tr.innerHTML = `
      <td class="colTime">${escapeHtml(fmtTime(m.tsMs))}</td>
      <td class="colKind"><span class="pill ${escapeHtml(m.kind || "")}">${escapeHtml((m.kind || "—") + "." + (m.type || "—"))}</span></td>
      <td class="colScope">${escapeHtml(scopeText)}</td>
      <td class="colOrigin">${escapeHtml(m.origin ?? "—")}</td>
      <td class="colCorr">${escapeHtml(m.correlationId ?? "—")}</td>
    `;
    tr.addEventListener("click", () => selectMessage(i));
    els.timeline.appendChild(tr);
  }

  if (items.length > MAX) {
    const tr = document.createElement("tr");
    tr.innerHTML = `<td colspan="5" class="emptyCell">Showing first ${MAX} of ${items.length} (MVP). Narrow filters to inspect more.</td>`;
    els.timeline.appendChild(tr);
  }
}

function selectMessage(filteredIndex) {
  const items = state.filtered.length ? state.filtered : state.messages;
  const m = items[filteredIndex];
  if (!m) return;

  clearSelection();
  state.selectedIndex = filteredIndex;
  els.copyBodyBtn.disabled = false;
  const sum = `${fmtTime(m.tsMs)} • ${m.kind}.${m.type} • ${m.scope.swarmId ?? "—"} / ${m.scope.role ?? "—"} / ${m.scope.instance ?? "—"}`;
  els.selectedSummary.textContent = sum;

  const wrapper = {
    recordedAt: m.recordedAt,
    routingKey: m.routingKey,
    headers: m.headers,
    body: m.body,
  };
  const pretty = JSON.stringify(wrapper, null, 2);
  els.details.innerHTML = syntaxHighlightJson(pretty);
  els.details.classList.remove("empty");

  // highlight row
  const rows = Array.from(els.timeline.querySelectorAll("tr[data-index]"));
  const active = rows.find((r) => r.dataset.index === String(filteredIndex));
  if (active) active.classList.add("active");
}

function escapeHtml(s) {
  return String(s)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function cssEscape(s) {
  // Minimal escape for attribute selectors.
  return String(s).replaceAll("\\", "\\\\").replaceAll('"', '\\"');
}

async function loadFile(file) {
  resetAll();
  state.file = file;
  els.fileName.textContent = file.name;
  els.resetBtn.disabled = false;
  els.clearFiltersBtn.disabled = false;

  const decoder = new TextDecoder("utf-8");
  const reader = file.stream().getReader();

  let buf = "";
  let parsed = 0;
  let errors = 0;
  const messages = [];

  const updateCounts = () => {
    els.parsedCount.textContent = String(parsed);
    els.errorCount.textContent = String(errors);
  };

  const flushLine = (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    try {
      const entry = JSON.parse(trimmed);
      const m = normaliseMessage(entry);
      messages.push(m);
      parsed += 1;
      if (parsed % 250 === 0) updateCounts();
    } catch {
      errors += 1;
      if (errors % 50 === 0) updateCounts();
    }
  };

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf("\n")) !== -1) {
        const line = buf.slice(0, idx);
        buf = buf.slice(idx + 1);
        flushLine(line);
      }
    }
    buf += decoder.decode();
    if (buf.trim()) {
      flushLine(buf);
    }
  } finally {
    try {
      reader.releaseLock();
    } catch {
      // ignore
    }
  }

  messages.sort((a, b) => {
    const ta = a.tsMs ?? 0;
    const tb = b.tsMs ?? 0;
    return ta - tb;
  });

  state.messages = messages;
  state.filtered = messages;
  els.parsedCount.textContent = String(messages.length);
  els.errorCount.textContent = String(errors);
  els.filteredCount.textContent = String(messages.length);

  buildIndexes();
  hydrateFilterOptions();
  renderThreads();
  renderTimeline();
}

function wireEvents() {
  els.fileInput.addEventListener("change", async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    await loadFile(file);
  });

  els.resetBtn.addEventListener("click", () => resetAll());

  els.clearFiltersBtn.addEventListener("click", () => {
    els.filterSwarm.value = "(all)";
    els.filterKind.value = "(all)";
    els.filterType.value = "(all)";
    els.filterRole.value = "(all)";
    els.filterInstance.value = "(all)";
    els.filterOrigin.value = "";
    els.filterCorrelation.value = "";
    els.filterRoutingKey.value = "";
    applyFilters();
  });

  const reapply = () => applyFilters();
  [els.filterSwarm, els.filterKind, els.filterType, els.filterRole, els.filterInstance].forEach(
    (el) => el.addEventListener("change", reapply)
  );
  [els.filterOrigin, els.filterCorrelation, els.filterRoutingKey].forEach((el) =>
    el.addEventListener("input", debounce(reapply, 120))
  );

  els.copyBodyBtn.addEventListener("click", async () => {
    const items = state.filtered.length ? state.filtered : state.messages;
    const m = items[state.selectedIndex];
    if (!m) return;
    const wrapper = {
      recordedAt: m.recordedAt,
      routingKey: m.routingKey,
      headers: m.headers,
      body: m.body,
    };
    await navigator.clipboard.writeText(JSON.stringify(wrapper, null, 2));
  });

  // Drag & drop
  const dz = els.dropZone;
  const setOver = (over) => dz.classList.toggle("dragover", over);

  dz.addEventListener("dragover", (e) => {
    e.preventDefault();
    setOver(true);
  });
  dz.addEventListener("dragleave", () => setOver(false));
  dz.addEventListener("drop", async (e) => {
    e.preventDefault();
    setOver(false);
    const file = e.dataTransfer?.files?.[0];
    if (!file) return;
    await loadFile(file);
  });

  dz.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") {
      els.fileInput.click();
    }
  });
}

function debounce(fn, ms) {
  let t = null;
  return (...args) => {
    if (t) clearTimeout(t);
    t = setTimeout(() => fn(...args), ms);
  };
}

resetAll();
ensureAllCustomSelects();
wireEvents();
