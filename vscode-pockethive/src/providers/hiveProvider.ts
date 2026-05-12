import * as vscode from 'vscode';
import { swarmList, type SwarmSummary } from '../mcp/tools';
import { getActiveEnvironment } from '../config';
import { isMcpRunning } from '../mcp/manager';

type HiveNode =
  | { kind: 'env-header'; name: string; baseUrl: string }
  | { kind: 'swarm'; swarm: SwarmSummary }
  | { kind: 'message'; message: string; icon?: string };

export class HiveProvider implements vscode.TreeDataProvider<HiveNode> {
  private readonly emitter = new vscode.EventEmitter<HiveNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;

  refresh(): void { this.emitter.fire(undefined); }

  getTreeItem(node: HiveNode): vscode.TreeItem {
    if (node.kind === 'env-header') {
      const item = new vscode.TreeItem(
        `${node.name}  —  ${node.baseUrl}`,
        vscode.TreeItemCollapsibleState.None
      );
      item.iconPath = new vscode.ThemeIcon('server-environment');
      item.description = 'active';
      return item;
    }

    if (node.kind === 'message') {
      const item = new vscode.TreeItem(node.message, vscode.TreeItemCollapsibleState.None);
      item.iconPath = new vscode.ThemeIcon(node.icon ?? 'info');
      return item;
    }

    const { swarm } = node;
    const status = swarm.status ?? 'UNKNOWN';
    const item = new vscode.TreeItem(swarm.id, vscode.TreeItemCollapsibleState.None);
    item.description = status;
    item.tooltip = `Template: ${swarm.templateId ?? '—'}\nStatus: ${status}\nHealth: ${swarm.health ?? '—'}`;
    item.iconPath = statusIcon(status);
    item.contextValue = `swarm-${status.toLowerCase()}`;
    item.command = {
      command: 'pockethive.openSwarmDetails',
      title: 'Open swarm details',
      arguments: [swarm.id],
    };
    return item;
  }

  async getChildren(element?: HiveNode): Promise<HiveNode[]> {
    if (element) return [];

    if (!isMcpRunning()) {
      return [{ kind: 'message', message: 'MCP server not running. Check Settings.', icon: 'warning' }];
    }

    const env = getActiveEnvironment();
    const header: HiveNode = env
      ? { kind: 'env-header', name: env.name, baseUrl: env.baseUrl }
      : { kind: 'message', message: 'No environment configured. Add one in Settings.', icon: 'warning' };

    if (!env) return [header];

    try {
      const swarms = await swarmList();
      if (!swarms.length) {
        return [header, { kind: 'message', message: 'No swarms running.', icon: 'circle-outline' }];
      }
      return [header, ...swarms.map(swarm => ({ kind: 'swarm' as const, swarm }))];
    } catch (err) {
      return [header, { kind: 'message', message: `Failed to load swarms: ${String(err)}`, icon: 'error' }];
    }
  }
}

function statusIcon(status: string): vscode.ThemeIcon {
  switch (status.toUpperCase()) {
    case 'RUNNING': return new vscode.ThemeIcon('play-circle', new vscode.ThemeColor('testing.iconPassed'));
    case 'READY':   return new vscode.ThemeIcon('circle-filled', new vscode.ThemeColor('charts.blue'));
    case 'STOPPED': return new vscode.ThemeIcon('circle-outline');
    case 'FAILED':  return new vscode.ThemeIcon('error', new vscode.ThemeColor('testing.iconFailed'));
    default:        return new vscode.ThemeIcon('question');
  }
}
