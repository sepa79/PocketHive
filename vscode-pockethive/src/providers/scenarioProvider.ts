import * as vscode from 'vscode';

import { requestJson } from '../api';
import { resolveServiceConfig } from '../config';
import { formatError } from '../format';
import { ScenarioSummary } from '../types';

type ScenarioNode =
  | { kind: 'scenario'; scenario: ScenarioSummary }
  | { kind: 'folder'; scenarioId: string; label: string; folder: 'schemas' | 'http-templates' }
  | { kind: 'file'; scenarioId: string; label: string; fileType: 'scenario' | 'schema' | 'http-template'; path?: string }
  | { kind: 'message'; message: string };

export class ScenarioProvider implements vscode.TreeDataProvider<ScenarioNode> {
  private readonly emitter = new vscode.EventEmitter<ScenarioNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;

  refresh(): void {
    this.emitter.fire(undefined);
  }

  getTreeItem(element: ScenarioNode): vscode.TreeItem {
    if (element.kind === 'message') {
      return new vscode.TreeItem(element.message, vscode.TreeItemCollapsibleState.None);
    }

    if (element.kind === 'scenario') {
      const label = element.scenario.name || element.scenario.id;
      const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.Collapsed);
      if (element.scenario.name && element.scenario.name !== element.scenario.id) {
        item.description = element.scenario.id;
      }
      item.contextValue = 'scenarioItem';
      return item;
    }

    if (element.kind === 'folder') {
      const item = new vscode.TreeItem(element.label, vscode.TreeItemCollapsibleState.Collapsed);
      item.iconPath = new vscode.ThemeIcon('folder');
      return item;
    }

    const item = new vscode.TreeItem(element.label, vscode.TreeItemCollapsibleState.None);
    item.iconPath = new vscode.ThemeIcon('file');
    item.command = {
      command: 'pockethive.openScenarioFile',
      title: 'Open scenario file',
      arguments: [{ scenarioId: element.scenarioId, kind: element.fileType, path: element.path }]
    };
    return item;
  }

  async getChildren(element?: ScenarioNode): Promise<ScenarioNode[]> {
    const config = resolveServiceConfig('scenarioManagerUrl');
    if ('error' in config) {
      return [{ kind: 'message', message: config.error }];
    }

    if (element?.kind === 'scenario') {
      return [
        { kind: 'file', scenarioId: element.scenario.id, label: 'scenario.yaml', fileType: 'scenario' },
        { kind: 'folder', scenarioId: element.scenario.id, label: 'schemas', folder: 'schemas' },
        { kind: 'folder', scenarioId: element.scenario.id, label: 'http-templates', folder: 'http-templates' }
      ];
    }

    if (element?.kind === 'folder') {
      try {
        if (element.folder === 'schemas') {
          const files = await requestJson<string[]>(
            config.baseUrl,
            config.authToken,
            'GET',
            `/scenarios/${encodeURIComponent(element.scenarioId)}/schemas`
          );
          if (!files.length) {
            return [{ kind: 'message', message: 'No schema files.' }];
          }
          return files.map((path) => ({
            kind: 'file',
            scenarioId: element.scenarioId,
            label: path,
            fileType: 'schema',
            path
          }));
        }

        const files = await requestJson<string[]>(
          config.baseUrl,
          config.authToken,
          'GET',
          `/scenarios/${encodeURIComponent(element.scenarioId)}/http-templates`
        );
        if (!files.length) {
          return [{ kind: 'message', message: 'No HTTP templates.' }];
        }
        return files.map((path) => ({
          kind: 'file',
          scenarioId: element.scenarioId,
          label: path,
          fileType: 'http-template',
          path
        }));
      } catch (error) {
        return [{ kind: 'message', message: `Failed to load files: ${formatError(error)}` }];
      }
    }

    if (element) {
      return [];
    }

    try {
      const scenarios = await requestJson<ScenarioSummary[]>(config.baseUrl, config.authToken, 'GET', '/scenarios');
      if (!scenarios.length) {
        return [{ kind: 'message', message: 'No scenarios found.' }];
      }
      return scenarios.map((scenario) => ({ kind: 'scenario', scenario }));
    } catch (error) {
      return [{ kind: 'message', message: `Failed to load scenarios: ${formatError(error)}` }];
    }
  }
}
