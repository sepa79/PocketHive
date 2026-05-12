import * as vscode from 'vscode';
import { bundleList, type BundleSummary } from '../mcp/tools';
import { getActiveBundlesFolder } from '../config';
import { isMcpRunning } from '../mcp/manager';

type ScenarioNode =
  | { kind: 'folder-header'; path: string }
  | { kind: 'bundle'; bundle: BundleSummary; validationState: ValidationState }
  | { kind: 'message'; message: string; icon?: string };

type ValidationState = 'unknown' | 'validating' | 'passed' | 'failed';

// Simple in-memory validation cache (survives provider refreshes within a session)
const _validationCache = new Map<string, { state: ValidationState; error?: string; at: number }>();

export class ScenarioProvider implements vscode.TreeDataProvider<ScenarioNode> {
  private readonly emitter = new vscode.EventEmitter<ScenarioNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;

  refresh(): void { this.emitter.fire(undefined); }

  setValidationResult(bundleName: string, state: 'passed' | 'failed', error?: string): void {
    _validationCache.set(bundleName, { state, error, at: Date.now() });
    this.emitter.fire(undefined);
  }

  setValidating(bundleName: string): void {
    _validationCache.set(bundleName, { state: 'validating', at: Date.now() });
    this.emitter.fire(undefined);
  }

  getTreeItem(node: ScenarioNode): vscode.TreeItem {
    if (node.kind === 'folder-header') {
      const parts = node.path.replace(/\\/g, '/').split('/').filter(Boolean);
      const name = parts[parts.length - 1] ?? node.path;
      const item = new vscode.TreeItem(name, vscode.TreeItemCollapsibleState.None);
      item.description = 'active bundles folder';
      item.tooltip = node.path;
      item.iconPath = new vscode.ThemeIcon('folder-active');
      return item;
    }

    if (node.kind === 'message') {
      const item = new vscode.TreeItem(node.message, vscode.TreeItemCollapsibleState.None);
      item.iconPath = new vscode.ThemeIcon(node.icon ?? 'info');
      return item;
    }

    const { bundle, validationState } = node;
    const item = new vscode.TreeItem(bundle.name, vscode.TreeItemCollapsibleState.None);
    item.contextValue = `bundle-${validationState}`;
    item.iconPath = validationIcon(validationState);

    const tags: string[] = [];
    if (bundle.hasTemplates) tags.push('templates');
    if (bundle.hasDatasets) tags.push('datasets');
    if (bundle.hasSut) tags.push('sut');
    item.description = tags.join(' · ') || undefined;

    const cached = _validationCache.get(bundle.name);
    if (validationState === 'failed' && cached?.error) {
      item.tooltip = `FAIL: ${cached.error}`;
    } else if (validationState === 'passed') {
      const ago = cached ? Math.round((Date.now() - cached.at) / 60000) : 0;
      item.tooltip = `Validated ${ago}m ago`;
    }

    return item;
  }

  async getChildren(element?: ScenarioNode): Promise<ScenarioNode[]> {
    if (element) return [];

    if (!isMcpRunning()) {
      return [{ kind: 'message', message: 'MCP server not running. Check Settings.', icon: 'warning' }];
    }

    const folder = getActiveBundlesFolder();
    const header: ScenarioNode = folder
      ? { kind: 'folder-header', path: folder }
      : { kind: 'message', message: 'No bundles folder configured. Add one in Settings.', icon: 'warning' };

    if (!folder) return [header];

    try {
      const { bundles } = await bundleList();
      if (!bundles.length) {
        return [header, { kind: 'message', message: 'No bundles found.', icon: 'circle-outline' }];
      }
      return [
        header,
        ...bundles.map(bundle => ({
          kind: 'bundle' as const,
          bundle,
          validationState: (_validationCache.get(bundle.name)?.state ?? 'unknown') as ValidationState,
        })),
      ];
    } catch (err) {
      return [header, { kind: 'message', message: `Failed to load bundles: ${String(err)}`, icon: 'error' }];
    }
  }
}

function validationIcon(state: ValidationState): vscode.ThemeIcon {
  switch (state) {
    case 'passed':    return new vscode.ThemeIcon('check', new vscode.ThemeColor('testing.iconPassed'));
    case 'failed':    return new vscode.ThemeIcon('error', new vscode.ThemeColor('testing.iconFailed'));
    case 'validating': return new vscode.ThemeIcon('sync~spin');
    default:          return new vscode.ThemeIcon('circle-outline');
  }
}
