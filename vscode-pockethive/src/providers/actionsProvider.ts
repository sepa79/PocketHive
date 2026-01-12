import * as vscode from 'vscode';

export class ActionsProvider implements vscode.TreeDataProvider<ActionItem> {
  private readonly items: ActionItem[];

  constructor() {
    this.items = [
      new ActionItem('Configure Orchestrator URL', 'pockethive.configureOrchestratorUrl'),
      new ActionItem('Configure Scenario Manager URL', 'pockethive.configureScenarioManagerUrl'),
      new ActionItem('List swarms', 'pockethive.listSwarms'),
      new ActionItem('Start swarm', 'pockethive.startSwarm'),
      new ActionItem('Stop swarm', 'pockethive.stopSwarm'),
      new ActionItem('Remove swarm', 'pockethive.removeSwarm'),
      new ActionItem('Open Orchestrator', 'pockethive.openOrchestrator')
    ];
  }

  getTreeItem(element: ActionItem): vscode.TreeItem {
    return element;
  }

  getChildren(): ActionItem[] {
    return this.items;
  }
}

class ActionItem extends vscode.TreeItem {
  constructor(label: string, commandId: string) {
    super(label, vscode.TreeItemCollapsibleState.None);
    this.command = { command: commandId, title: label };
  }
}
