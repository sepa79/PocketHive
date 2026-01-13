import * as vscode from 'vscode';

import { getActiveHiveUrl, getHiveUrls } from '../config';

type SettingsNode =
  | { kind: 'add' }
  | { kind: 'instance'; url: string; active: boolean }
  | { kind: 'message'; message: string };

export class SettingsProvider implements vscode.TreeDataProvider<SettingsNode> {
  private readonly emitter = new vscode.EventEmitter<SettingsNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;

  refresh(): void {
    this.emitter.fire(undefined);
  }

  getTreeItem(element: SettingsNode): vscode.TreeItem {
    if (element.kind === 'add') {
      const item = new vscode.TreeItem('Add Hive URL', vscode.TreeItemCollapsibleState.None);
      item.iconPath = new vscode.ThemeIcon('add');
      item.command = { command: 'pockethive.addHiveUrl', title: 'Add Hive URL' };
      return item;
    }

    if (element.kind === 'message') {
      return new vscode.TreeItem(element.message, vscode.TreeItemCollapsibleState.None);
    }

    const item = new vscode.TreeItem(element.url, vscode.TreeItemCollapsibleState.None);
    if (element.active) {
      item.description = 'Active';
      item.iconPath = new vscode.ThemeIcon('check');
      item.contextValue = 'hiveInstanceActive';
    } else {
      item.contextValue = 'hiveInstance';
    }
    item.command = {
      command: 'pockethive.setActiveHiveUrl',
      title: 'Use Hive URL',
      arguments: [{ url: element.url }]
    };
    return item;
  }

  getChildren(): SettingsNode[] {
    const urls = getHiveUrls();
    const active = getActiveHiveUrl();
    const items: SettingsNode[] = [{ kind: 'add' }];
    if (!urls.length) {
      items.push({ kind: 'message', message: 'No Hive URLs configured.' });
      return items;
    }
    urls.forEach((url) => {
      items.push({ kind: 'instance', url, active: url === active });
    });
    return items;
  }
}
