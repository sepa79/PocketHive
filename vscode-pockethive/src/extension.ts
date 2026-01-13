import * as vscode from 'vscode';

import {
  configureOrchestratorUrl,
  configureScenarioManagerUrl,
  listSwarms,
  openOrchestrator,
  openScenarioRaw,
  openSwarmDetails,
  previewScenario,
  runSwarmCommand,
  showEntry
} from './commands';
import { PREVIEW_SCHEME, SCENARIO_SCHEME } from './constants';
import { configureTimeWindow, loadTimeWindow } from './filterState';
import { ScenarioFileSystemProvider } from './fs/scenarioFileSystemProvider';
import { openHelp } from './help';
import { disposeOutputChannel, initOutputChannel } from './output';
import { initPreviewProvider } from './preview';
import { ActionsProvider } from './providers/actionsProvider';
import { BuzzProvider } from './providers/buzzProvider';
import { HiveProvider } from './providers/hiveProvider';
import { JournalProvider } from './providers/journalProvider';
import { ScenarioProvider } from './providers/scenarioProvider';

export function activate(context: vscode.ExtensionContext): void {
  const outputChannel = initOutputChannel();
  context.subscriptions.push(outputChannel);

  const previewProvider = initPreviewProvider();
  const actionsProvider = new ActionsProvider();
  const hiveProvider = new HiveProvider();
  const buzzProvider = new BuzzProvider(loadTimeWindow(context.workspaceState, 'buzzTimeWindowMs'));
  const journalProvider = new JournalProvider(loadTimeWindow(context.workspaceState, 'journalTimeWindowMs'));
  const scenarioProvider = new ScenarioProvider();
  const scenarioFsProvider = new ScenarioFileSystemProvider();

  context.subscriptions.push(
    vscode.commands.registerCommand('pockethive.configureOrchestratorUrl', configureOrchestratorUrl),
    vscode.commands.registerCommand('pockethive.configureScenarioManagerUrl', configureScenarioManagerUrl),
    vscode.commands.registerCommand('pockethive.listSwarms', listSwarms),
    vscode.commands.registerCommand('pockethive.startSwarm', (swarmId?: string) => runSwarmCommand('start', swarmId)),
    vscode.commands.registerCommand('pockethive.stopSwarm', (swarmId?: string) => runSwarmCommand('stop', swarmId)),
    vscode.commands.registerCommand('pockethive.removeSwarm', (swarmId?: string) => runSwarmCommand('remove', swarmId)),
    vscode.commands.registerCommand('pockethive.openOrchestrator', openOrchestrator),
    vscode.commands.registerCommand('pockethive.openSwarmDetails', openSwarmDetails),
    vscode.commands.registerCommand('pockethive.openScenarioRaw', openScenarioRaw),
    vscode.commands.registerCommand('pockethive.previewScenario', previewScenario),
    vscode.commands.registerCommand('pockethive.showEntry', showEntry),
    vscode.commands.registerCommand('pockethive.refreshHive', () => hiveProvider.refresh()),
    vscode.commands.registerCommand('pockethive.refreshBuzz', () => buzzProvider.refresh()),
    vscode.commands.registerCommand('pockethive.refreshJournal', () => journalProvider.refresh()),
    vscode.commands.registerCommand('pockethive.refreshScenario', () => scenarioProvider.refresh()),
    vscode.commands.registerCommand('pockethive.filterBuzz', (option) =>
      configureTimeWindow('Buzz', buzzProvider, context.workspaceState, 'buzzTimeWindowMs', option)
    ),
    vscode.commands.registerCommand('pockethive.filterJournal', (option) =>
      configureTimeWindow('Journal', journalProvider, context.workspaceState, 'journalTimeWindowMs', option)
    ),
    vscode.commands.registerCommand('pockethive.helpActions', () => openHelp('actions')),
    vscode.commands.registerCommand('pockethive.helpHive', () => openHelp('hive')),
    vscode.commands.registerCommand('pockethive.helpBuzz', () => openHelp('buzz')),
    vscode.commands.registerCommand('pockethive.helpJournal', () => openHelp('journal')),
    vscode.commands.registerCommand('pockethive.helpScenario', () => openHelp('scenario')),
    vscode.window.registerTreeDataProvider('pockethive.actions', actionsProvider),
    vscode.window.registerTreeDataProvider('pockethive.hive', hiveProvider),
    vscode.window.registerTreeDataProvider('pockethive.buzz', buzzProvider),
    vscode.window.registerTreeDataProvider('pockethive.journal', journalProvider),
    vscode.window.registerTreeDataProvider('pockethive.scenario', scenarioProvider),
    vscode.workspace.registerTextDocumentContentProvider(PREVIEW_SCHEME, previewProvider),
    vscode.workspace.registerFileSystemProvider(SCENARIO_SCHEME, scenarioFsProvider, { isCaseSensitive: true })
  );
}

export function deactivate(): void {
  disposeOutputChannel();
}
