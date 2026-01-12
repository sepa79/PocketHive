import * as vscode from 'vscode';

import { requestJson } from '../api';
import { resolveServiceConfig } from '../config';
import { formatEntryDescription, formatEntryLabel, formatError } from '../format';
import { JournalEntry, JournalPage } from '../types';

type BuzzNode = { kind: 'entry'; entry: JournalEntry } | { kind: 'message'; message: string };

export class BuzzProvider implements vscode.TreeDataProvider<BuzzNode> {
  private readonly emitter = new vscode.EventEmitter<BuzzNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;

  refresh(): void {
    this.emitter.fire(undefined);
  }

  getTreeItem(element: BuzzNode): vscode.TreeItem {
    if (element.kind === 'message') {
      return new vscode.TreeItem(element.message, vscode.TreeItemCollapsibleState.None);
    }

    const label = formatEntryLabel(element.entry);
    const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.None);
    item.description = formatEntryDescription(element.entry);
    item.command = { command: 'pockethive.showEntry', title: 'Show entry', arguments: [element.entry, 'Buzz entry'] };
    return item;
  }

  async getChildren(): Promise<BuzzNode[]> {
    const config = resolveServiceConfig('orchestratorUrl');
    if ('error' in config) {
      return [{ kind: 'message', message: config.error }];
    }

    try {
      const page = await requestJson<JournalPage>(
        config.baseUrl,
        config.authToken,
        'GET',
        '/api/journal/hive/page?limit=50'
      );
      const entries = Array.isArray(page.items) ? page.items : [];
      if (!entries.length) {
        return [{ kind: 'message', message: 'No Buzz entries yet.' }];
      }
      return entries.map((entry) => ({ kind: 'entry', entry }));
    } catch (error) {
      return [{ kind: 'message', message: `Buzz unavailable: ${formatError(error)}` }];
    }
  }
}
