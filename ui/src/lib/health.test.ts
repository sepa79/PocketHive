import { describe, expect, it } from 'vitest';
import { componentHealth, queueHealth, defaultRules } from './health';
import type { ComponentSummary, QueueInfo } from '../types/hive';

describe('health utilities', () => {
  it('computes queue health based on depth', () => {
    const q: QueueInfo = { name: 'a', role: 'consumer', depth: 50 };
    expect(queueHealth(q)).toBe('OK');
    q.depth = 200;
    expect(queueHealth(q)).toBe('WARN');
    q.depth = 2000;
    expect(queueHealth(q)).toBe('ALERT');
  });

  it('computes component health based on heartbeat', () => {
    const now = Date.now();
    const c: ComponentSummary = {
      componentId: 'c1',
      name: 'C1',
      type: 't',
      lastHeartbeatTs: now,
      health: 'OK',
      queues: [],
    };
    expect(componentHealth(c)).toBe('OK');
    c.lastHeartbeatTs = now - defaultRules.warnHeartbeatMs - 1;
    expect(componentHealth(c)).toBe('WARN');
    c.lastHeartbeatTs = now - defaultRules.alertHeartbeatMs - 1;
    expect(componentHealth(c)).toBe('ALERT');
  });
});
