export function summarizePatch(patch) {
  if (!patch || typeof patch !== "object" || Array.isArray(patch)) {
    return { empty: true, topLevelKeys: [] };
  }
  return {
    empty: Object.keys(patch).length === 0,
    topLevelKeys: Object.keys(patch).sort(),
  };
}

export function isPlainObject(value) {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

export function cloneJson(value) {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value));
}

export function deepMergeConfig(current, updates) {
  const merged = isPlainObject(current) ? cloneJson(current) : {};
  for (const [key, value] of Object.entries(updates || {})) {
    if (isPlainObject(value) && isPlainObject(merged[key])) {
      merged[key] = deepMergeConfig(merged[key], value);
    } else {
      merged[key] = cloneJson(value);
    }
  }
  return merged;
}

export function textValue(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

export function journalItems(page) {
  if (Array.isArray(page)) return page;
  if (Array.isArray(page?.items)) return page.items;
  return [];
}

function scopedJournalEntry(entry, { role, instanceId }) {
  const scope = isPlainObject(entry?.scope) ? entry.scope : {};
  return textValue(scope.role) === role && textValue(scope.instance) === instanceId;
}

export function configFromJournalEntry(entry) {
  const data = isPlainObject(entry?.data) ? entry.data : {};
  const raw = isPlainObject(entry?.raw) ? entry.raw : {};
  const rawData = isPlainObject(raw.data) ? raw.data : {};
  if (isPlainObject(data.config)) return data.config;
  if (isPlainObject(rawData.config)) return rawData.config;
  return null;
}

export function latestComponentConfigFromJournalPage(page, { role, instanceId }) {
  const selected = journalItems(page).find((entry) =>
    textValue(entry?.type) === "status-full" &&
    scopedJournalEntry(entry, { role, instanceId }) &&
    configFromJournalEntry(entry)
  );
  if (!selected) return null;
  const config = configFromJournalEntry(selected);
  return {
    matchedRole: textValue(selected.scope?.role),
    matchedInstanceId: textValue(selected.scope?.instance),
    receivedAt: selected.timestamp || null,
    runId: selected.runId || null,
    config: cloneJson(config),
  };
}

export function planComponentConfigUpdate({ currentConfig, patch, allowEmptyPatch = false }) {
  if (!patch || typeof patch !== "object" || Array.isArray(patch)) {
    throw new Error("patch must be an object");
  }
  const patchSummary = summarizePatch(patch);
  if (patchSummary.empty && !allowEmptyPatch) {
    throw new Error("EMPTY_PATCH_REJECTED: pass allowEmptyPatch=true only when an explicit empty config-update is intended");
  }
  const mergedConfig = deepMergeConfig(currentConfig, patch);
  return {
    patchSummary,
    dispatchPatch: cloneJson(patch),
    mergedConfig,
    mergedConfigSummary: summarizePatch(mergedConfig),
  };
}
