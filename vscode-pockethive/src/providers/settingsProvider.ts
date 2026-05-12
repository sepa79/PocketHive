import * as vscode from 'vscode';
import { getEnvironments, getActiveEnvironmentName, getBundlesFolders, getActiveBundlesFolder } from '../config';
import { isMcpRunning, onMcpStatusChange, type McpStatus } from '../mcp/manager';

type SettingsNode =
  | { kind: 'section'; label: string }
  | { kind: 'environment'; name: string; baseUrl: string; active: boolean }
  | { kind: 'add-env' }
  | { kind: 'folder'; path: string; active: boolean }
  | { kind: 'add-folder' }
  | { kind: 'mcp-status'; running: boolean }
  | { kind: 'message'; message: string };

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
        item.description = node.baseUrl;
        item.iconPath = node.active
          ? new vscode.ThemeIcon('check', new vscode.ThemeColor('testing.iconPassed'))
          : new vscode.ThemeIcon('circle-outline');
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

  getChildren(): SettingsNode[] {
    const envs = getEnvironments();
    const activeName = getActiveEnvironmentName();
    const folders = getBundlesFolders();
    const activeFolder = getActiveBundlesFolder();

    const nodes: SettingsNode[] = [
      { kind: 'section', label: 'ENVIRONMENTS' },
      ...envs.map(e => ({ kind: 'environment' as const, name: e.name, baseUrl: e.baseUrl, active: e.name === activeName })),
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
