import * as vscode from 'vscode';

import { isMcpRunning } from './mcp/manager';

type RefreshTarget = {
  key: 'hive' | 'buzz' | 'journal' | 'scenario' | 'settings';
  refresh(): void;
};

const DEFAULT_INTERVAL_SECONDS: Record<RefreshTarget['key'], number> = {
  hive: 10,
  buzz: 20,
  journal: 20,
  scenario: 60,
  settings: 30,
};

export function registerAutoRefresh(context: vscode.ExtensionContext, targets: RefreshTarget[]): void {
  for (const target of targets) {
    let disposed = false;
    let timer: NodeJS.Timeout | undefined;
    const tick = () => {
      if (disposed) return;
      try {
        if (autoRefreshEnabled() && isMcpRunning()) target.refresh();
      } finally {
        timer = setTimeout(tick, intervalMs(target.key));
      }
    };
    timer = setTimeout(tick, intervalMs(target.key));
    context.subscriptions.push({
      dispose: () => {
        disposed = true;
        if (timer) clearTimeout(timer);
      },
    });
  }
}

function autoRefreshEnabled(): boolean {
  return vscode.workspace.getConfiguration('pockethive.autoRefresh').get<boolean>('enabled') ?? true;
}

function intervalMs(key: RefreshTarget['key']): number {
  const configured = vscode.workspace
    .getConfiguration('pockethive.autoRefresh.intervals')
    .get<number>(key);
  const seconds = Number.isFinite(configured) && configured ? configured : DEFAULT_INTERVAL_SECONDS[key];
  return Math.max(5, seconds) * 1000;
}
