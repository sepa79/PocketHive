import * as vscode from 'vscode';

import { describeTimeWindow, filterEntriesByTime, getTimeWindowOptions, sortEntriesNewestFirst } from '../filters';
import { formatEntryDescription, formatEntryLabel, formatError } from '../format';
import { JournalEntry, JournalPage } from '../types';
import { isMcpRunning } from '../mcp/manager';
import { debugHiveJournal } from '../mcp/tools';

type BuzzNode =
  | { kind: 'filter' }
  | { kind: 'filter-option'; option: { label: string; ms: number | null }; active: boolean }
  | { kind: 'entry'; entry: JournalEntry }
  | { kind: 'message'; message: string };

export class BuzzProvider implements vscode.TreeDataProvider<BuzzNode> {
  private readonly emitter = new vscode.EventEmitter<BuzzNode | undefined>();
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

  getTreeItem(element: BuzzNode): vscode.TreeItem {
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
        command: 'pockethive.filterBuzz',
        title: 'Filter Buzz',
        arguments: [element.option]
      };
      return item;
    }

    if (element.kind === 'message') {
      return new vscode.TreeItem(element.message, vscode.TreeItemCollapsibleState.None);
    }

    const label = formatEntryLabel(element.entry);
    const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.None);
    item.description = formatEntryDescription(element.entry);
    item.command = { command: 'pockethive.showEntry', title: 'Show entry', arguments: [element.entry, 'Buzz entry'] };
    return item;
  }

  async getChildren(element?: BuzzNode): Promise<BuzzNode[]> {
    if (element?.kind === 'filter') {
      return getTimeWindowOptions().map((option) => ({
        kind: 'filter-option',
        option,
        active: option.ms === this.timeWindowMs
      }));
    }

    const filterNode: BuzzNode = { kind: 'filter' };
    if (!isMcpRunning()) {
      return [filterNode, { kind: 'message', message: 'MCP server not running. Check Settings.' }];
    }

    try {
      const page = await debugHiveJournal(50) as JournalPage;
      const entries = Array.isArray(page.items) ? page.items : [];
      const filtered = filterEntriesByTime(entries, this.timeWindowMs);
      const sorted = sortEntriesNewestFirst(filtered).slice(0, 50);
      if (!sorted.length) {
        return [filterNode, { kind: 'message', message: 'No Buzz entries for the selected window.' }];
      }
      return [filterNode, ...sorted.map((entry): BuzzNode => ({ kind: 'entry', entry }))];
    } catch (error) {
      return [filterNode, { kind: 'message', message: `Buzz unavailable: ${formatError(error)}` }];
    }
  }
}
