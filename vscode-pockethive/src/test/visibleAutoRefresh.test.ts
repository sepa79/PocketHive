import assert from 'node:assert/strict';
import test from 'node:test';

import {
  TAB_DATA_REFRESH_INTERVAL_MS,
  VISIBLE_AUTO_REFRESH_SYSTEM_TIMERS,
  VisibleAutoRefresh,
  VisibleAutoRefreshTimerPort,
} from '../webview/visibleAutoRefresh';

interface ScheduledTask {
  readonly id: number;
  readonly delayMs: number;
  readonly action: () => Promise<void>;
}

class FakeTimers implements VisibleAutoRefreshTimerPort {
  readonly scheduled: ScheduledTask[] = [];
  readonly cancelled: number[] = [];
  private nextId = 1;

  schedule(delayMs: number, action: () => Promise<void>): unknown {
    const task = { id: this.nextId++, delayMs, action };
    this.scheduled.push(task);
    return task.id;
  }

  cancel(handle: unknown): void {
    this.cancelled.push(handle as number);
  }
}

test('schedules one fixed-cadence refresh only for a visible enabled workspace', () => {
  assert.equal(TAB_DATA_REFRESH_INTERVAL_MS, 15_000);
  const timers = new FakeTimers();
  const refresh = new VisibleAutoRefresh(async () => undefined, timers);

  refresh.setVisible(true);
  assert.equal(timers.scheduled.length, 0);

  refresh.setEnabled(true);
  assert.deepEqual(timers.scheduled.map(task => task.delayMs), [15_000]);
  refresh.setEnabled(true);
  refresh.setVisible(true);
  assert.equal(timers.scheduled.length, 1);

  refresh.defer();
  assert.deepEqual(timers.cancelled, [1]);
  assert.deepEqual(timers.scheduled.map(task => task.delayMs), [15_000, 15_000]);

  refresh.setVisible(false);
  assert.deepEqual(timers.cancelled, [1, 2]);
  refresh.setVisible(false);
  assert.deepEqual(timers.cancelled, [1, 2]);
  refresh.setVisible(true);
  assert.deepEqual(timers.scheduled.map(task => task.delayMs), [15_000, 15_000, 0]);

  refresh.setEnabled(false);
  assert.deepEqual(timers.cancelled, [1, 2, 3]);
  refresh.setEnabled(false);
  refresh.setVisible(false);
  refresh.dispose();
  assert.deepEqual(timers.scheduled.map(task => task.delayMs), [15_000, 15_000, 0]);
  assert.deepEqual(timers.cancelled, [1, 2, 3]);
});

test('starts immediately when an enabled workspace becomes visible for the first time', () => {
  const timers = new FakeTimers();
  const refresh = new VisibleAutoRefresh(async () => undefined, timers);

  refresh.setEnabled(true);
  assert.equal(timers.scheduled.length, 0);
  refresh.setVisible(true);

  assert.deepEqual(timers.scheduled.map(task => task.delayMs), [0]);
});

test('production timers execute, detach from process lifetime, and cancel the exact handle', async () => {
  const originalSetTimeout = globalThis.setTimeout;
  const originalClearTimeout = globalThis.clearTimeout;
  let unrefCalls = 0;
  const handle = { unref() { unrefCalls += 1; } } as unknown as NodeJS.Timeout;
  let scheduledDelay: number | undefined;
  let scheduledAction: (() => void) | undefined;
  let cancelled: unknown;
  globalThis.setTimeout = ((action: () => void, delayMs?: number) => {
    scheduledAction = action;
    scheduledDelay = delayMs;
    return handle;
  }) as typeof setTimeout;
  globalThis.clearTimeout = ((value: unknown) => { cancelled = value; }) as typeof clearTimeout;
  try {
    let calls = 0;
    const returned = VISIBLE_AUTO_REFRESH_SYSTEM_TIMERS.schedule(7, async () => { calls += 1; });
    assert.equal(returned, handle);
    assert.equal(scheduledDelay, 7);
    assert.equal(unrefCalls, 1);
    assert.ok(scheduledAction);

    scheduledAction();
    await Promise.resolve();
    assert.equal(calls, 1);

    VISIBLE_AUTO_REFRESH_SYSTEM_TIMERS.cancel(returned);
    assert.equal(cancelled, handle);
  } finally {
    globalThis.setTimeout = originalSetTimeout;
    globalThis.clearTimeout = originalClearTimeout;
  }
});

test('disposing a scheduled refresh cancels it exactly once', () => {
  const timers = new FakeTimers();
  const refresh = new VisibleAutoRefresh(async () => undefined, timers);
  refresh.setVisible(true);
  refresh.setEnabled(true);

  refresh.dispose();
  refresh.dispose();

  assert.deepEqual(timers.cancelled, [1]);
});

test('awaits each refresh before rearming and never overlaps an in-flight action', async () => {
  const timers = new FakeTimers();
  let calls = 0;
  let release!: () => void;
  const blocked = new Promise<void>(resolve => { release = resolve; });
  const refresh = new VisibleAutoRefresh(async () => {
    calls += 1;
    await blocked;
  }, timers);
  refresh.setVisible(true);
  refresh.setEnabled(true);

  const firstTick = timers.scheduled[0].action();
  await Promise.resolve();
  const duplicateTick = timers.scheduled[0].action();
  assert.equal(calls, 1);
  assert.equal(timers.scheduled.length, 1);

  release();
  await Promise.all([firstTick, duplicateTick]);
  assert.equal(calls, 1);
  assert.deepEqual(timers.scheduled.map(task => task.delayMs), [15_000, 15_000]);
});

test('does not rearm when hidden, disabled, or disposed during a refresh', async () => {
  for (const stop of [
    (refresh: VisibleAutoRefresh) => refresh.setVisible(false),
    (refresh: VisibleAutoRefresh) => refresh.setEnabled(false),
    (refresh: VisibleAutoRefresh) => refresh.dispose(),
  ]) {
    const timers = new FakeTimers();
    let release!: () => void;
    const blocked = new Promise<void>(resolve => { release = resolve; });
    const refresh = new VisibleAutoRefresh(() => blocked, timers);
    refresh.setVisible(true);
    refresh.setEnabled(true);
    const tick = timers.scheduled[0].action();
    await Promise.resolve();

    stop(refresh);
    release();
    await tick;

    assert.equal(timers.scheduled.length, 1);
  }
});

test('rearms after a failed refresh so a transient owner failure cannot stop polling', async () => {
  const timers = new FakeTimers();
  const refresh = new VisibleAutoRefresh(async () => { throw new Error('owner unavailable'); }, timers);
  refresh.setVisible(true);
  refresh.setEnabled(true);

  await assert.rejects(timers.scheduled[0].action(), /owner unavailable/);

  assert.deepEqual(timers.scheduled.map(task => task.delayMs), [15_000, 15_000]);
});
