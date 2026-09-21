const FILTER_TIME_WINDOWS = Object.freeze({
  ALL: 'ALL',
  FIFTEEN_MINUTES: 'FIFTEEN_MINUTES',
  ONE_HOUR: 'ONE_HOUR',
} as const);

type PocketHiveTimeWindow = typeof FILTER_TIME_WINDOWS[keyof typeof FILTER_TIME_WINDOWS];

interface PocketHiveEventFilterCriteria {
  readonly timeWindow: PocketHiveTimeWindow;
  readonly kind: string;
  readonly severity: string;
  readonly search: string;
}

type EventRecord = Record<string, unknown>;

const SEARCH_FIELDS = Object.freeze([
  'timestamp', 'kind', 'type', 'severity', 'swarmId', 'origin', 'direction', 'routingKey', 'summary',
] as const);

function filterPocketHiveEvents<T extends EventRecord>(
  events: readonly T[],
  criteria: PocketHiveEventFilterCriteria,
  now = Date.now(),
): T[] {
  const search = criteria.search.trim().toLocaleLowerCase();
  return events.filter(event => {
    if (!insideWindow(event.timestamp, criteria.timeWindow, now)) return false;
    if (!matchesExact(event.kind, criteria.kind)) return false;
    if (!matchesExact(event.severity, criteria.severity)) return false;
    return SEARCH_FIELDS.some(field => displayString(event[field]).toLocaleLowerCase().includes(search));
  });
}

function insideWindow(timestamp: unknown, window: PocketHiveTimeWindow, now: number): boolean {
  if (window === FILTER_TIME_WINDOWS.ALL) return true;
  const observedAt = typeof timestamp === 'string' ? Date.parse(timestamp) : Number.NaN;
  const duration = window === FILTER_TIME_WINDOWS.FIFTEEN_MINUTES ? 15 * 60_000 : 60 * 60_000;
  return observedAt <= now && observedAt >= now - duration;
}

function matchesExact(value: unknown, expected: string): boolean {
  return expected === 'ALL' || displayString(value).toLocaleUpperCase() === expected.toLocaleUpperCase();
}

function displayString(value: unknown): string {
  return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
    ? String(value)
    : '';
}

const EVENT_FILTERS_API = Object.freeze({
  TIME_WINDOWS: FILTER_TIME_WINDOWS,
  filterEvents: filterPocketHiveEvents,
});
// Stryker disable all: this UMD bootstrap is exercised by the Playwright browser gate, not the Node command runner.
if (typeof module === 'undefined') {
  (globalThis as typeof globalThis & { PocketHiveEventFilters: typeof EVENT_FILTERS_API })
    .PocketHiveEventFilters = EVENT_FILTERS_API;
} else {
  module.exports = EVENT_FILTERS_API;
}
// Stryker restore all
