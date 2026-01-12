import * as vscode from 'vscode';

import { requestJson } from '../api';
import { resolveServiceConfig } from '../config';
import { formatError } from '../format';
import { ScenarioSummary } from '../types';

type ScenarioNode = { kind: 'scenario'; scenario: ScenarioSummary } | { kind: 'message'; message: string };

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

    const label = element.scenario.name || element.scenario.id;
    const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.None);
    if (element.scenario.name && element.scenario.name !== element.scenario.id) {
      item.description = element.scenario.id;
    }
    item.contextValue = 'scenarioItem';
    item.command = {
      command: 'pockethive.openScenarioRaw',
      title: 'Open scenario',
      arguments: [element.scenario.id]
    };
    return item;
  }

  async getChildren(): Promise<ScenarioNode[]> {
    const config = resolveServiceConfig('scenarioManagerUrl');
    if ('error' in config) {
      return [{ kind: 'message', message: config.error }];
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
