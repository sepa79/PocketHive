import * as vscode from 'vscode';
import { getEnvironments, getActiveEnvironmentName, getBundlesFolders, getActiveBundlesFolder } from '../config';
import { isMcpRunning, onMcpStatusChange } from '../mcp/manager';
import { envStatus, type EnvironmentStatus } from '../mcp/tools';

type SettingsNode =
  | { kind: 'section'; label: string }
  | { kind: 'environment'; name: string; baseUrl: string; active: boolean; state: EnvironmentStatus['state'] | 'unchecked'; message?: string }
  | { kind: 'add-env' }
  | { kind: 'folder'; path: string; active: boolean }
  | { kind: 'add-folder' }
  | { kind: 'mcp-status'; running: boolean }
  | { kind: 'message'; message: string };

type EnvironmentViewState = EnvironmentStatus['state'] | 'unchecked';

export class SettingsProvider implements vscode.TreeDataProvider<SettingsNode> {
  private readonly emitter = new vscode.EventEmitter<SettingsNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;

  constructor() {
    onMcpStatusChange(() => this.emitter.fire(undefined));
  }

  refresh(): void { this.emitter.fire(undefined); }

  getTreeItem(node: SettingsNode): vscode.TreeItem {
    switch (node.kind) {
      case 'section': {
        const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.None);
        item.iconPath = new vscode.ThemeIcon('symbol-namespace');
        return item;
      }
      case 'environment': {
        const item = new vscode.TreeItem(node.name, vscode.TreeItemCollapsibleState.None);
        item.id = node.name;
        item.description = `${environmentStateLabel(node.state)}  ${node.baseUrl}`;
        item.tooltip = node.message ? `${node.baseUrl}\n${node.message}` : node.baseUrl;
        item.iconPath = environmentIcon(node.state, node.active);
        item.contextValue = node.active ? 'environment-active' : 'environment';
        item.command = {
          command: 'pockethive.setActiveEnvironment',
          title: 'Use environment',
          arguments: [node.name],
        };
        return item;
      }
      case 'add-env': {
        const item = new vscode.TreeItem('+ Add environment', vscode.TreeItemCollapsibleState.None);
        item.iconPath = new vscode.ThemeIcon('add');
        item.command = { command: 'pockethive.addEnvironment', title: 'Add environment' };
        return item;
      }
      case 'folder': {
        const parts = node.path.replace(/\\/g, '/').split('/').filter(Boolean);
        const name = parts[parts.length - 1] ?? node.path;
        const item = new vscode.TreeItem(name, vscode.TreeItemCollapsibleState.None);
        item.id = node.path;
        item.description = node.path;
        item.tooltip = node.path;
        item.iconPath = node.active
          ? new vscode.ThemeIcon('folder-active')
          : new vscode.ThemeIcon('folder');
        item.contextValue = node.active ? 'bundlesFolder-active' : 'bundlesFolder';
        item.command = {
          command: 'pockethive.setActiveBundlesFolder',
          title: 'Use bundles folder',
          arguments: [node.path],
        };
        return item;
      }
      case 'add-folder': {
        const item = new vscode.TreeItem('+ Add bundles folder', vscode.TreeItemCollapsibleState.None);
        item.iconPath = new vscode.ThemeIcon('add');
        item.command = { command: 'pockethive.addBundlesFolder', title: 'Add bundles folder' };
        return item;
      }
      case 'mcp-status': {
        const item = new vscode.TreeItem(
          `MCP Server: ${node.running ? 'Running' : 'Stopped'}`,
          vscode.TreeItemCollapsibleState.None
        );
        item.iconPath = node.running
          ? new vscode.ThemeIcon('circle-filled', new vscode.ThemeColor('testing.iconPassed'))
          : new vscode.ThemeIcon('circle-outline');
        item.contextValue = 'mcp-status';
        return item;
      }
      case 'message': {
        return new vscode.TreeItem(node.message, vscode.TreeItemCollapsibleState.None);
      }
    }
  }

  async getChildren(): Promise<SettingsNode[]> {
    const envs = getEnvironments();
    const activeName = getActiveEnvironmentName();
    const folders = getBundlesFolders();
    const activeFolder = getActiveBundlesFolder();
    let statuses = new Map<string, EnvironmentStatus>();
    if (isMcpRunning()) {
      try {
        const result = await envStatus();
        statuses = new Map(result.environments.map(env => [env.name, env]));
      } catch {
        statuses = new Map();
      }
    }

    const environmentNodes: SettingsNode[] = envs.map(e => {
      const status = statuses.get(e.name);
      const state: EnvironmentViewState = status?.state ?? 'unchecked';
      return {
        kind: 'environment',
        name: e.name,
        baseUrl: e.baseUrl,
        active: e.name === activeName,
        state,
        message: statusMessage(status),
      };
    });

    const nodes: SettingsNode[] = [
      { kind: 'section', label: 'ENVIRONMENTS' },
      ...environmentNodes,
      { kind: 'add-env' },
      { kind: 'section', label: 'BUNDLES FOLDERS' },
      ...folders.map(p => ({ kind: 'folder' as const, path: p, active: p === activeFolder })),
      { kind: 'add-folder' },
      { kind: 'section', label: 'MCP SERVER' },
      { kind: 'mcp-status', running: isMcpRunning() },
    ];

    return nodes;
  }
}

function environmentStateLabel(state: EnvironmentStatus['state'] | 'unchecked'): string {
  switch (state) {
    case 'active': return 'Active';
    case 'inactive': return 'Inactive';
    case 'inaccessible': return 'Inaccessible';
    case 'degraded': return 'Degraded';
    case 'auth-required': return 'Auth required';
    case 'auth-error': return 'Auth error';
    case 'invalid': return 'Invalid';
    default: return 'Unchecked';
  }
}

function environmentIcon(state: EnvironmentStatus['state'] | 'unchecked', active: boolean): vscode.ThemeIcon {
  if (state === 'active') return new vscode.ThemeIcon('check', new vscode.ThemeColor('testing.iconPassed'));
  if (state === 'inactive') return new vscode.ThemeIcon('circle-outline', new vscode.ThemeColor('charts.green'));
  if (state === 'degraded') return new vscode.ThemeIcon('warning', new vscode.ThemeColor('testing.iconQueued'));
  if (state === 'auth-required' || state === 'auth-error') return new vscode.ThemeIcon('lock', new vscode.ThemeColor('testing.iconQueued'));
  if (state === 'inaccessible' || state === 'invalid') return new vscode.ThemeIcon('error', new vscode.ThemeColor('testing.iconFailed'));
  return active ? new vscode.ThemeIcon('sync~spin') : new vscode.ThemeIcon('circle-outline');
}

function statusMessage(status: EnvironmentStatus | undefined): string | undefined {
  if (!status) return undefined;
  if (status.message) return status.message;
  const parts = Object.entries(status.services ?? {}).map(([name, service]) => {
    const suffix = service.ok ? service.status : (service.error || service.status);
    return `${name}: ${suffix}`;
  });
  return parts.length ? parts.join('\n') : undefined;
}
