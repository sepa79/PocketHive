import * as vscode from 'vscode';

import { requestJson } from '../api';
import { resolveServiceConfig } from '../config';
import { formatEntryDescription, formatEntryLabel, formatError, formatSwarmDescription } from '../format';
import { JournalEntry, SwarmSummary } from '../types';

type JournalNode =
  | { kind: 'swarm'; swarm: SwarmSummary }
  | { kind: 'entry'; entry: JournalEntry }
  | { kind: 'message'; message: string };

export class JournalProvider implements vscode.TreeDataProvider<JournalNode> {
  private readonly emitter = new vscode.EventEmitter<JournalNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;

  refresh(): void {
    this.emitter.fire(undefined);
  }

  getTreeItem(element: JournalNode): vscode.TreeItem {
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
    const config = resolveServiceConfig('orchestratorUrl');
    if ('error' in config) {
      return [{ kind: 'message', message: config.error }];
    }

    if (!element) {
      try {
        const swarms = await requestJson<SwarmSummary[]>(config.baseUrl, config.authToken, 'GET', '/api/swarms');
        if (!swarms.length) {
          return [{ kind: 'message', message: 'No swarms found.' }];
        }
        return swarms.map((swarm) => ({ kind: 'swarm', swarm }));
      } catch (error) {
        return [{ kind: 'message', message: `Failed to load swarms: ${formatError(error)}` }];
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
      const tail = entries.slice(Math.max(entries.length - 50, 0));
      if (!tail.length) {
        return [{ kind: 'message', message: 'No journal entries.' }];
      }
      return tail.map((entry) => ({ kind: 'entry', entry }));
    } catch (error) {
      return [{ kind: 'message', message: `Journal unavailable: ${formatError(error)}` }];
    }
  }
}
