import * as vscode from 'vscode';

import { requestJson } from '../api';
import { resolveServiceConfig } from '../config';
import { describeTimeWindow, filterEntriesByTime, getTimeWindowOptions, sortEntriesNewestFirst } from '../filters';
import { formatEntryDescription, formatEntryLabel, formatError, formatSwarmDescription } from '../format';
import { JournalEntry, SwarmSummary } from '../types';

type JournalNode =
  | { kind: 'filter' }
  | { kind: 'filter-option'; option: { label: string; ms: number | null }; active: boolean }
  | { kind: 'swarm'; swarm: SwarmSummary }
  | { kind: 'entry'; entry: JournalEntry }
  | { kind: 'message'; message: string };

export class JournalProvider implements vscode.TreeDataProvider<JournalNode> {
  private readonly emitter = new vscode.EventEmitter<JournalNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;
  private timeWindowMs: number | null;

  constructor(timeWindowMs: number | null = null) {
    this.timeWindowMs = timeWindowMs;
  }

  refresh(): void {
    this.emitter.fire(undefined);
  }

  setTimeWindowMs(value: number | null): void {
    this.timeWindowMs = value;
  }

  getTreeItem(element: JournalNode): vscode.TreeItem {
    if (element.kind === 'filter') {
      const label = `Filter: ${describeTimeWindow(this.timeWindowMs)}`;
      const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.Collapsed);
      item.iconPath = new vscode.ThemeIcon('filter');
      return item;
    }

    if (element.kind === 'filter-option') {
      const item = new vscode.TreeItem(element.option.label, vscode.TreeItemCollapsibleState.None);
      if (element.active) {
        item.description = 'Current';
        item.iconPath = new vscode.ThemeIcon('check');
      }
      item.command = {
        command: 'pockethive.filterJournal',
        title: 'Filter Journal',
        arguments: [element.option]
      };
      return item;
    }

    if (element.kind === 'message') {
      return new vscode.TreeItem(element.message, vscode.TreeItemCollapsibleState.None);
    }

    if (element.kind === 'entry') {
      const item = new vscode.TreeItem(formatEntryLabel(element.entry), vscode.TreeItemCollapsibleState.None);
      item.description = formatEntryDescription(element.entry);
      item.command = { command: 'pockethive.showEntry', title: 'Show entry', arguments: [element.entry, 'Journal entry'] };
      return item;
    }

    const item = new vscode.TreeItem(element.swarm.id, vscode.TreeItemCollapsibleState.Collapsed);
    item.description = formatSwarmDescription(element.swarm);
    return item;
  }

  async getChildren(element?: JournalNode): Promise<JournalNode[]> {
    if (element?.kind === 'filter') {
      return getTimeWindowOptions().map((option) => ({
        kind: 'filter-option',
        option,
        active: option.ms === this.timeWindowMs
      }));
    }

    const filterNode: JournalNode = { kind: 'filter' };
    const config = resolveServiceConfig('orchestratorUrl');
    if ('error' in config) {
      return [filterNode, { kind: 'message', message: config.error }];
    }

    if (!element) {
      try {
        const swarms = await requestJson<SwarmSummary[]>(config.baseUrl, config.authToken, 'GET', '/api/swarms');
        if (!swarms.length) {
          return [filterNode, { kind: 'message', message: 'No swarms found.' }];
        }
        return [filterNode, ...swarms.map((swarm): JournalNode => ({ kind: 'swarm', swarm }))];
      } catch (error) {
        return [filterNode, { kind: 'message', message: `Failed to load swarms: ${formatError(error)}` }];
      }
    }

    if (element.kind !== 'swarm') {
      return [];
    }

    try {
      const entries = await requestJson<JournalEntry[]>(
        config.baseUrl,
        config.authToken,
        'GET',
        `/api/swarms/${encodeURIComponent(element.swarm.id)}/journal`
      );
      const filtered = filterEntriesByTime(entries, this.timeWindowMs);
      const sorted = sortEntriesNewestFirst(filtered).slice(0, 50);
      if (!sorted.length) {
        return [{ kind: 'message', message: 'No journal entries for the selected window.' }];
      }
      return sorted.map((entry) => ({ kind: 'entry', entry }));
    } catch (error) {
      return [{ kind: 'message', message: `Journal unavailable: ${formatError(error)}` }];
    }
  }
}
