import * as vscode from 'vscode';

import { requestJson } from '../api';
import { resolveServiceConfig } from '../config';
import { formatError, formatSwarmDescription } from '../format';
import { SwarmSummary } from '../types';

type HiveNode = { kind: 'swarm'; swarm: SwarmSummary } | { kind: 'message'; message: string };

export class HiveProvider implements vscode.TreeDataProvider<HiveNode> {
  private readonly emitter = new vscode.EventEmitter<HiveNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;

  refresh(): void {
    this.emitter.fire(undefined);
  }

  getTreeItem(element: HiveNode): vscode.TreeItem {
    if (element.kind === 'message') {
      return new vscode.TreeItem(element.message, vscode.TreeItemCollapsibleState.None);
    }

    const item = new vscode.TreeItem(element.swarm.id, vscode.TreeItemCollapsibleState.None);
    item.description = formatSwarmDescription(element.swarm);
    item.tooltip = JSON.stringify(element.swarm, null, 2);
    item.contextValue = 'swarmItem';
    item.command = { command: 'pockethive.openSwarmDetails', title: 'Open swarm details', arguments: [element.swarm.id] };
    return item;
  }

  async getChildren(element?: HiveNode): Promise<HiveNode[]> {
    if (element) {
      return [];
    }

    const config = resolveServiceConfig('orchestratorUrl');
    if ('error' in config) {
      return [{ kind: 'message', message: config.error }];
    }

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
}
