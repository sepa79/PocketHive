import assert from 'node:assert/strict';
import test from 'node:test';

interface FilterCriteria {
  readonly timeWindow: string;
  readonly kind: string;
  readonly severity: string;
  readonly search: string;
}

const { filterEvents, TIME_WINDOWS } = require('../webview/eventFilters') as {
  filterEvents<T extends Record<string, unknown>>(events: readonly T[], criteria: FilterCriteria, now?: number): T[];
  TIME_WINDOWS: Readonly<Record<'ALL' | 'FIFTEEN_MINUTES' | 'ONE_HOUR', string>>;
};

const NOW = Date.parse('2026-08-19T10:45:00.000Z');
const events = [
  {
    timestamp: '2026-08-19T10:42:18.000Z', severity: 'INFO', kind: 'signal', type: 'swarm-start',
    swarmId: 'nightly-smoke', origin: 'orchestrator', direction: 'OUT',
  },
  {
    timestamp: '2026-08-19T10:41:53.000Z', severity: 'ERROR', kind: 'runtime-debug', type: 'runtime-log-snapshot',
    swarmId: 'auth-regression', origin: 'orchestrator', direction: 'LOCAL',
  },
  {
    timestamp: '2026-08-19T09:00:00.000Z', severity: 'WARN', kind: 'metric', type: 'status-delta',
    swarmId: 'checkout-load', origin: 'marshal-bee', direction: 'IN',
  },
];

test('event filters combine explicit time, kind, severity and search criteria', () => {
  assert.deepEqual(filterEvents(events, {
    timeWindow: TIME_WINDOWS.FIFTEEN_MINUTES,
    kind: 'runtime-debug',
    severity: 'ERROR',
    search: 'AUTH-regression',
  }, NOW), [events[1]]);
  assert.deepEqual(filterEvents(events, {
    timeWindow: TIME_WINDOWS.ONE_HOUR,
    kind: 'ALL', severity: 'ALL', search: '',
  }, NOW), events.slice(0, 2));
});

test('kind and severity filters each narrow independently and remain case insensitive', () => {
  assert.deepEqual(filterEvents(events, {
    timeWindow: TIME_WINDOWS.ALL, kind: 'SIGNAL', severity: 'ALL', search: '',
  }, NOW), [events[0]]);
  assert.deepEqual(filterEvents(events, {
    timeWindow: TIME_WINDOWS.ALL, kind: 'ALL', severity: 'error', search: '',
  }, NOW), [events[1]]);
  assert.deepEqual(filterEvents(events, {
    timeWindow: TIME_WINDOWS.ALL, kind: 'ALL', severity: 'ALL', search: '  AUTH-regression  ',
  }, NOW), [events[1]]);
});

test('time windows distinguish fifteen minutes from one hour and include exact boundaries only', () => {
  const halfHour = { ...events[0], timestamp: '2026-08-19T10:15:00.000Z' };
  const exactNow = { ...events[0], timestamp: '2026-08-19T10:45:00.000Z' };
  const exactLower = { ...events[0], timestamp: '2026-08-19T10:30:00.000Z' };
  const future = { ...events[0], timestamp: '2026-08-19T10:45:00.001Z' };
  const tooOld = { ...events[0], timestamp: '2026-08-19T10:29:59.999Z' };
  const all = [halfHour, exactNow, exactLower, future, tooOld];
  assert.deepEqual(filterEvents(all, {
    timeWindow: TIME_WINDOWS.FIFTEEN_MINUTES, kind: 'ALL', severity: 'ALL', search: '',
  }, NOW), [exactNow, exactLower]);
  assert.deepEqual(filterEvents([halfHour], {
    timeWindow: TIME_WINDOWS.ONE_HOUR, kind: 'ALL', severity: 'ALL', search: '',
  }, NOW), [halfHour]);
});

test('invalid timestamps never enter a bounded time window and all-time does not invent a filter', () => {
  const invalid = { ...events[0], timestamp: 'not-a-date' };
  const nonString = { ...events[0], timestamp: NOW };
  assert.deepEqual(filterEvents([invalid, nonString], {
    timeWindow: TIME_WINDOWS.FIFTEEN_MINUTES,
    kind: 'ALL', severity: 'ALL', search: '',
  }, NOW), []);
  assert.deepEqual(filterEvents([invalid], {
    timeWindow: TIME_WINDOWS.ALL,
    kind: 'ALL', severity: 'ALL', search: '',
  }, NOW), [invalid]);
  const numericYear = { ...events[0], timestamp: 2026 };
  assert.deepEqual(filterEvents([numericYear], {
    timeWindow: TIME_WINDOWS.ONE_HOUR, kind: 'ALL', severity: 'ALL', search: '',
  }, Date.parse('2026-01-01T00:30:00.000Z')), []);
});

test('search is bounded to known display fields and never serializes arbitrary nested owner data', () => {
  const event = { ...events[0], data: { secretNestedValue: 'do-not-search' }, routingKey: 'ph.control.signal' };
  assert.deepEqual(filterEvents([event], {
    timeWindow: TIME_WINDOWS.ALL,
    kind: 'ALL', severity: 'ALL', search: 'do-not-search',
  }, NOW), []);
  assert.deepEqual(filterEvents([event], {
    timeWindow: TIME_WINDOWS.ALL,
    kind: 'ALL', severity: 'ALL', search: 'ph.control',
  }, NOW), [event]);
  const primitiveFields = { ...events[0], type: 404, summary: true };
  assert.deepEqual(filterEvents([primitiveFields], {
    timeWindow: TIME_WINDOWS.ALL, kind: 'ALL', severity: 'ALL', search: '404',
  }, NOW), [primitiveFields]);
  assert.deepEqual(filterEvents([primitiveFields], {
    timeWindow: TIME_WINDOWS.ALL, kind: 'ALL', severity: 'ALL', search: 'true',
  }, NOW), [primitiveFields]);
  assert.deepEqual(filterEvents([{ ...primitiveFields, type: { value: 'hidden' }, summary: null }], {
    timeWindow: TIME_WINDOWS.ALL, kind: 'ALL', severity: 'ALL', search: 'hidden',
  }, NOW), []);
  for (const search of ['[object object]', 'Stryker was here!']) {
    assert.deepEqual(filterEvents([{ ...primitiveFields, type: { value: 'hidden' }, summary: null }], {
      timeWindow: TIME_WINDOWS.ALL, kind: 'ALL', severity: 'ALL', search,
    }, NOW), []);
  }
});
