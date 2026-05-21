import * as vscode from 'vscode';

import { describeTimeWindow, filterEntriesByTime, getTimeWindowOptions, sortEntriesNewestFirst } from '../filters';
import { formatEntryDescription, formatEntryLabel, formatError, formatSwarmDescription } from '../format';
import { JournalEntry, JournalPage, SwarmSummary } from '../types';
import { isMcpRunning } from '../mcp/manager';
import { debugJournal, swarmList } from '../mcp/tools';

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
    if (!isMcpRunning()) {
      return [filterNode, { kind: 'message', message: 'MCP server not running. Check Settings.' }];
    }

    if (!element) {
      try {
        const swarms = await swarmList();
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
      const page = await debugJournal(element.swarm.id, 50) as JournalPage | JournalEntry[];
      const entries = Array.isArray(page) ? page : (Array.isArray(page.items) ? page.items : []);
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
