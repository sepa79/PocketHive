import * as vscode from 'vscode';

import { JournalEntry } from './types';

export type TimeWindowOption = {
  label: string;
  ms: number | null;
  description?: string;
};

const TIME_WINDOW_OPTIONS: TimeWindowOption[] = [
  { label: 'All time', ms: null, description: 'No time filter.' },
  { label: 'Last 15 minutes', ms: 15 * 60 * 1000 },
  { label: 'Last 1 hour', ms: 60 * 60 * 1000 },
  { label: 'Last 6 hours', ms: 6 * 60 * 60 * 1000 },
  { label: 'Last 24 hours', ms: 24 * 60 * 60 * 1000 },
  { label: 'Last 7 days', ms: 7 * 24 * 60 * 60 * 1000 }
];

export function getTimeWindowOptions(): TimeWindowOption[] {
  return [...TIME_WINDOW_OPTIONS];
}

export async function pickTimeWindow(currentMs: number | null): Promise<TimeWindowOption | undefined> {
  const items = TIME_WINDOW_OPTIONS.map((option) => ({
    label: option.label,
    description: option.description,
    window: option
  }));

  const currentLabel = TIME_WINDOW_OPTIONS.find((option) => option.ms === currentMs)?.label;
  const pick = await vscode.window.showQuickPick(items, {
    placeHolder: currentLabel ? `Current: ${currentLabel}` : 'Select time window',
    ignoreFocusOut: true
  });

  return pick?.window;
}

export function describeTimeWindow(windowMs: number | null): string {
  const match = TIME_WINDOW_OPTIONS.find((option) => option.ms === windowMs);
  if (match) {
    return match.label;
  }
  if (windowMs == null) {
    return 'All time';
  }
  const minutes = Math.round(windowMs / 60000);
  return `Custom (${minutes}m)`;
}

export function filterEntriesByTime(entries: JournalEntry[], windowMs: number | null): JournalEntry[] {
  if (windowMs == null) {
    return entries;
  }
  const cutoff = Date.now() - windowMs;
  return entries.filter((entry) => {
    const timestampMs = parseTimestamp(entry.timestamp);
    if (timestampMs == null) {
      return false;
    }
    return timestampMs >= cutoff;
  });
}

export function sortEntriesNewestFirst(entries: JournalEntry[]): JournalEntry[] {
  return [...entries].sort((left, right) => {
    const leftTs = parseTimestamp(left.timestamp) ?? 0;
    const rightTs = parseTimestamp(right.timestamp) ?? 0;
    return rightTs - leftTs;
  });
}

function parseTimestamp(value: unknown): number | null {
  if (!value) {
    return null;
  }
  if (value instanceof Date) {
    const time = value.getTime();
    return Number.isNaN(time) ? null : time;
  }
  if (typeof value === 'string' || typeof value === 'number') {
    const time = new Date(value).getTime();
    return Number.isNaN(time) ? null : time;
  }
  return null;
}

export type TimeWindowConsumer = {
  setTimeWindowMs: (ms: number | null) => void;
  refresh: () => void;
};
