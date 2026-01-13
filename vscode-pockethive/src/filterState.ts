import * as vscode from 'vscode';

import { describeTimeWindow, pickTimeWindow, TimeWindowConsumer, TimeWindowOption } from './filters';

export type TimeWindowKey = 'buzzTimeWindowMs' | 'journalTimeWindowMs';

export function loadTimeWindow(state: vscode.Memento, key: TimeWindowKey): number | null {
  const value = state.get<number | null>(key);
  if (typeof value === 'number') {
    return value;
  }
  return null;
}

export async function configureTimeWindow(
  label: string,
  consumer: TimeWindowConsumer,
  state: vscode.Memento,
  key: TimeWindowKey,
  option?: TimeWindowOption
): Promise<void> {
  let resolved = option;
  if (!resolved) {
    const current = loadTimeWindow(state, key);
    resolved = await pickTimeWindow(current);
  }
  if (!resolved) {
    return;
  }
  await state.update(key, resolved.ms);
  consumer.setTimeWindowMs(resolved.ms);
  consumer.refresh();
  vscode.window.showInformationMessage(
    `PocketHive: ${label} filter set to '${describeTimeWindow(resolved.ms)}'.`
  );
}
