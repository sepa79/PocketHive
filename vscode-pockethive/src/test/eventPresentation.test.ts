import assert from 'node:assert/strict';
import test from 'node:test';

import { ConnectionContractError } from '../connection/contracts';
import { EventPagePresentation } from '../webview/eventPresentation';

test('event presentation projects ten large records into bounded summaries and retains exact details', () => {
  let sequence = 0;
  const presentation = new EventPagePresentation(() => `detail-${++sequence}`);
  const records = Array.from({ length: 10 }, (_, index) => ({
    eventId: index + 1,
    timestamp: '2026-08-22T17:00:00.000Z',
    swarmId: 'test',
    runId: 'run-1',
    severity: 'ERROR',
    direction: 'LOCAL',
    kind: 'runtime-debug',
    type: 'runtime-log-snapshot',
    origin: 'orchestrator',
    routingKey: 'ph.control.event.runtime-debug.test',
    summary: 'Captured runtime logs',
    correlationId: `correlation-${index}`,
    extra: { logs: 'x'.repeat(28_000) },
    data: { password: 'owner-secret' },
  }));
  const ownerPage = { items: records, nextCursor: { ts: '2026-08-22T17:00:00Z', id: 10 }, hasMore: true };
  assert.equal(Buffer.byteLength(JSON.stringify(ownerPage)) > 64 * 1024, true);

  const projected = presentation.replace(ownerPage);

  assert.equal(projected.items.length, 10);
  assert.deepEqual(projected.items[0], {
    detailId: 'detail-1',
    eventId: 1,
    timestamp: '2026-08-22T17:00:00.000Z',
    severity: 'ERROR',
    kind: 'runtime-debug',
    type: 'runtime-log-snapshot',
    swarmId: 'test',
    runId: 'run-1',
    origin: 'orchestrator',
    direction: 'LOCAL',
    routingKey: 'ph.control.event.runtime-debug.test',
    summary: 'Captured runtime logs',
  });
  assert.deepEqual(projected.nextCursor, ownerPage.nextCursor);
  assert.equal(projected.hasMore, true);
  assert.equal(Buffer.byteLength(JSON.stringify(projected)) < 64 * 1024, true);
  for (const item of projected.items) {
    assert.equal(Object.hasOwn(item, 'extra'), false);
    assert.equal(Object.hasOwn(item, 'data'), false);
    assert.equal(Object.hasOwn(item, 'correlationId'), false);
  }
  assert.doesNotMatch(JSON.stringify(projected), /owner-secret|xxxxxxxxxxxxxxxx/);
  assert.equal(presentation.require('detail-1'), records[0]);
  assert.equal(presentation.require('detail-10'), records[9]);
});

test('event presentation omits absent optional summary fields without inferring values', () => {
  const presentation = new EventPagePresentation(() => 'detail-1');
  const projected = presentation.replace({
    items: [{
      eventId: null,
      timestamp: '2026-08-22T17:00:00.000Z',
      kind: 'signal',
      severity: null,
      type: null,
      swarmId: null,
      runId: null,
      origin: null,
      direction: null,
      routingKey: null,
      summary: null,
    }],
    nextCursor: null,
    hasMore: false,
  });

  assert.deepEqual(projected, {
    items: [{ detailId: 'detail-1', timestamp: '2026-08-22T17:00:00.000Z', kind: 'signal' }],
    nextCursor: null,
    hasMore: false,
  });
});

test('event presentation preserves valid string and zero numeric event IDs exactly', () => {
  let sequence = 0;
  const presentation = new EventPagePresentation(() => `detail-${++sequence}`);
  const projected = presentation.replace({
    items: [
      { eventId: 'event-1', timestamp: '2026-08-22T17:00:00Z', kind: 'signal' },
      { eventId: 0, timestamp: '2026-08-22T17:00:01Z', kind: 'outcome' },
    ],
    nextCursor: null,
    hasMore: false,
  });

  assert.equal(projected.items[0].eventId, 'event-1');
  assert.equal(projected.items[1].eventId, 0);
});

test('replacing or clearing an event page invalidates prior opaque IDs', () => {
  let sequence = 0;
  const presentation = new EventPagePresentation(() => `detail-${++sequence}`);
  presentation.replace(eventPage('first'));
  assert.equal((presentation.require('detail-1') as { summary: string }).summary, 'first');

  presentation.replace(eventPage('second'));
  assertContractError(
    () => presentation.require('detail-1'),
    'EVENT_DETAIL_NOT_AVAILABLE',
    'The selected event is no longer available. Refresh the current page.',
  );
  assert.equal((presentation.require('detail-2') as { summary: string }).summary, 'second');

  presentation.clear();
  assertContractError(() => presentation.require('detail-2'), 'EVENT_DETAIL_NOT_AVAILABLE');
});

test('invalid pages and event field types fail explicitly and leave no retained details', () => {
  const invalid = new EventPagePresentation(() => 'detail-1');
  for (const page of [
    null,
    [],
    42,
    'page',
    { items: 'invalid', nextCursor: null, hasMore: false },
    { items: [null], nextCursor: null, hasMore: false },
    { items: [{}], nextCursor: null, hasMore: false },
    { items: [{ timestamp: '', kind: 'signal' }], nextCursor: null, hasMore: false },
    { items: [{ timestamp: '   ', kind: 'signal' }], nextCursor: null, hasMore: false },
    { items: [{ timestamp: 'now', kind: '' }], nextCursor: null, hasMore: false },
    { items: [{ timestamp: 'now', kind: '   ' }], nextCursor: null, hasMore: false },
    { items: [{ timestamp: 'now', kind: 'signal', severity: 42 }], nextCursor: null, hasMore: false },
    { items: [{ timestamp: 'now', kind: 'signal', severity: '' }], nextCursor: null, hasMore: false },
    { items: [{ timestamp: 'now', kind: 'signal', severity: '   ' }], nextCursor: null, hasMore: false },
    { items: [{ timestamp: 'now', kind: 'signal', eventId: '' }], nextCursor: null, hasMore: false },
    { items: [{ timestamp: 'now', kind: 'signal', eventId: '   ' }], nextCursor: null, hasMore: false },
    { items: [{ timestamp: 'now', kind: 'signal', eventId: true }], nextCursor: null, hasMore: false },
    { items: [{ timestamp: 'now', kind: 'signal', eventId: Number.NaN }], nextCursor: null, hasMore: false },
    { items: [{ timestamp: 'now', kind: 'signal', eventId: Number.POSITIVE_INFINITY }], nextCursor: null, hasMore: false },
    { items: [{ timestamp: 'now', kind: 'signal' }], nextCursor: null, hasMore: 'yes' },
    { items: [{ timestamp: 'now', kind: 'signal' }], nextCursor: {}, hasMore: false },
    { items: [{ timestamp: 'now', kind: 'signal' }], nextCursor: { ts: 'now', id: '1' }, hasMore: false },
    { items: [{ timestamp: 'now', kind: 'signal' }], nextCursor: { ts: 'now', id: Number.NaN }, hasMore: false },
    { items: [{ timestamp: 'now', kind: 'signal' }], nextCursor: { ts: '', id: 1 }, hasMore: false },
    { items: [{ timestamp: 'now', kind: 'signal' }], nextCursor: { ts: 'now', id: 1, payload: 'no' }, hasMore: false },
  ]) {
    assertContractError(
      () => invalid.replace(page),
      'COMPANION_EVENT_PAGE_INVALID',
      'PocketHive returned an invalid event page.',
    );
    assertContractError(() => invalid.require('detail-1'), 'EVENT_DETAIL_NOT_AVAILABLE');
  }

  const functionPage = Object.assign(() => undefined, { items: [], nextCursor: null, hasMore: false });
  assertContractError(
    () => invalid.replace(functionPage),
    'COMPANION_EVENT_PAGE_INVALID',
    'PocketHive returned an invalid event page.',
  );
});

test('opaque IDs must be non-blank and unique within a page generation', () => {
  const blankId = new EventPagePresentation(() => '   ');
  assertContractError(
    () => blankId.replace(eventPage('blank')),
    'COMPANION_EVENT_PAGE_INVALID',
    'PocketHive returned an invalid event page.',
  );

  const duplicateId = new EventPagePresentation(() => 'detail-1');
  assertContractError(() => duplicateId.replace({
    items: [
      { timestamp: '2026-08-22T17:00:00Z', kind: 'signal' },
      { timestamp: '2026-08-22T17:00:01Z', kind: 'signal' },
    ],
    nextCursor: null,
    hasMore: false,
  }), 'EVENT_DETAIL_ID_COLLISION', 'Event detail ID collision');
  assertContractError(() => duplicateId.require('detail-1'), 'EVENT_DETAIL_NOT_AVAILABLE');
});

function eventPage(summary: string): unknown {
  return {
    items: [{ timestamp: '2026-08-22T17:00:00Z', kind: 'signal', summary }],
    nextCursor: null,
    hasMore: false,
  };
}

function assertContractError(action: () => unknown, code: string, message?: string): void {
  assert.throws(action, error => error instanceof ConnectionContractError
    && error.code === code
    && (message === undefined || error.message === `${code}: ${message}`));
}
