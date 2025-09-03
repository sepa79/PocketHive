import type { ComponentSummary, HealthLevel, QueueInfo } from '../types/hive';

export interface HealthRules {
  warnHeartbeatMs: number;
  alertHeartbeatMs: number;
  warnDepth: number;
  alertDepth: number;
  warnOldestSec: number;
  alertOldestSec: number;
}

export const defaultRules: HealthRules = {
  warnHeartbeatMs: 10_000,
  alertHeartbeatMs: 30_000,
  warnDepth: 100,
  alertDepth: 1000,
  warnOldestSec: 60,
  alertOldestSec: 300,
};

export function queueHealth(queue: QueueInfo, rules: HealthRules = defaultRules): HealthLevel {
  const depth = queue.depth ?? 0;
  const oldest = queue.oldestAgeSec ?? 0;

  if (depth > rules.alertDepth || oldest > rules.alertOldestSec) return 'ALERT';
  if (depth > rules.warnDepth || oldest > rules.warnOldestSec) return 'WARN';
  return 'OK';
}

export function componentHealth(summary: ComponentSummary, rules: HealthRules = defaultRules): HealthLevel {
  const now = Date.now();
  const hbAge = summary.lastHeartbeatTs ? now - summary.lastHeartbeatTs : Number.MAX_SAFE_INTEGER;
  const worstQueue = summary.queues.reduce<HealthLevel>((worst, q) => {
    const h = queueHealth(q, rules);
    if (h === 'ALERT') return 'ALERT';
    if (h === 'WARN' && worst === 'OK') return 'WARN';
    return worst;
  }, 'OK');

  if (hbAge > rules.alertHeartbeatMs || worstQueue === 'ALERT') return 'ALERT';
  if (hbAge > rules.warnHeartbeatMs || worstQueue === 'WARN') return 'WARN';
  return 'OK';
}

export function healthColor(level: HealthLevel): string {
  switch (level) {
    case 'OK':
      return 'bg-green-500';
    case 'WARN':
      return 'bg-yellow-400';
    case 'ALERT':
    default:
      return 'bg-red-500';
  }
}
