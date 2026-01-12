import { JournalEntry, SwarmSummary } from './types';

type SwarmAction = 'start' | 'stop' | 'remove';

export function formatSwarmDescription(swarm: SwarmSummary): string {
  const parts: string[] = [];
  if (swarm.status) {
    parts.push(swarm.status);
  }
  if (swarm.health) {
    parts.push(swarm.health);
  }
  return parts.join(' / ');
}

export function actionLabel(action: SwarmAction): string {
  if (action === 'start') {
    return 'Start';
  }
  if (action === 'stop') {
    return 'Stop';
  }
  return 'Remove';
}

export function actionIcon(action: SwarmAction): string {
  if (action === 'start') {
    return 'play';
  }
  if (action === 'stop') {
    return 'debug-stop';
  }
  return 'trash';
}

export function formatEntryLabel(entry: JournalEntry): string {
  const timestamp = entry.timestamp ? entry.timestamp.replace('T', ' ').replace('Z', '') : 'unknown time';
  const kind = entry.kind ?? 'event';
  const type = entry.type ?? 'unknown';
  return `${timestamp} ${kind}/${type}`;
}

export function formatEntryDescription(entry: JournalEntry): string | undefined {
  const role = entry.scope?.role;
  const instance = entry.scope?.instance;
  if (role && instance) {
    return `${role}/${instance}`;
  }
  return entry.origin;
}

export function formatError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
