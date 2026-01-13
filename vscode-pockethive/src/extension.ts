import * as vscode from 'vscode';

import {
  addHiveUrl,
  listSwarms,
  openScenarioFile,
  openUi,
  openScenarioRaw,
  openSwarmDetails,
  previewScenario,
  removeHiveUrl,
  runAllSwarms,
  runSwarmCommand,
  setActiveHiveUrl,
  showEntry
} from './commands';
import { PREVIEW_SCHEME, SCENARIO_SCHEME } from './constants';
import { configureTimeWindow, loadTimeWindow } from './filterState';
import { ScenarioFileSystemProvider } from './fs/scenarioFileSystemProvider';
import { openHelp } from './help';
import { disposeOutputChannel, initOutputChannel } from './output';
import { initPreviewProvider } from './preview';
import { BuzzProvider } from './providers/buzzProvider';
import { HiveProvider } from './providers/hiveProvider';
import { JournalProvider } from './providers/journalProvider';
import { ScenarioProvider } from './providers/scenarioProvider';
import { SettingsProvider } from './providers/settingsProvider';

export function activate(context: vscode.ExtensionContext): void {
  const outputChannel = initOutputChannel();
  context.subscriptions.push(outputChannel);

  const previewProvider = initPreviewProvider();
  const hiveProvider = new HiveProvider();
  const buzzProvider = new BuzzProvider(loadTimeWindow(context.workspaceState, 'buzzTimeWindowMs'));
  const journalProvider = new JournalProvider(loadTimeWindow(context.workspaceState, 'journalTimeWindowMs'));
  const scenarioProvider = new ScenarioProvider();
  const scenarioFsProvider = new ScenarioFileSystemProvider();
  const settingsProvider = new SettingsProvider();

  context.subscriptions.push(
    vscode.commands.registerCommand('pockethive.addHiveUrl', addHiveUrl),
    vscode.commands.registerCommand('pockethive.setActiveHiveUrl', setActiveHiveUrl),
    vscode.commands.registerCommand('pockethive.removeHiveUrl', removeHiveUrl),
    vscode.commands.registerCommand('pockethive.listSwarms', listSwarms),
    vscode.commands.registerCommand('pockethive.startSwarm', (swarmId?: string) => runSwarmCommand('start', swarmId)),
    vscode.commands.registerCommand('pockethive.stopSwarm', (swarmId?: string) => runSwarmCommand('stop', swarmId)),
    vscode.commands.registerCommand('pockethive.removeSwarm', (swarmId?: string) => runSwarmCommand('remove', swarmId)),
    vscode.commands.registerCommand('pockethive.startAllSwarms', () => runAllSwarms('start')),
    vscode.commands.registerCommand('pockethive.stopAllSwarms', () => runAllSwarms('stop')),
    vscode.commands.registerCommand('pockethive.openUi', openUi),
    vscode.commands.registerCommand('pockethive.openSwarmDetails', openSwarmDetails),
    vscode.commands.registerCommand('pockethive.openScenarioRaw', openScenarioRaw),
    vscode.commands.registerCommand('pockethive.openScenarioFile', openScenarioFile),
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
    vscode.commands.registerCommand('pockethive.helpSettings', () => openHelp('settings')),
    vscode.commands.registerCommand('pockethive.helpHive', () => openHelp('hive')),
    vscode.commands.registerCommand('pockethive.helpBuzz', () => openHelp('buzz')),
    vscode.commands.registerCommand('pockethive.helpJournal', () => openHelp('journal')),
    vscode.commands.registerCommand('pockethive.helpScenario', () => openHelp('scenario')),
    vscode.window.registerTreeDataProvider('pockethive.hive', hiveProvider),
    vscode.window.registerTreeDataProvider('pockethive.buzz', buzzProvider),
    vscode.window.registerTreeDataProvider('pockethive.journal', journalProvider),
    vscode.window.registerTreeDataProvider('pockethive.scenario', scenarioProvider),
    vscode.window.registerTreeDataProvider('pockethive.settings', settingsProvider),
    vscode.workspace.registerTextDocumentContentProvider(PREVIEW_SCHEME, previewProvider),
    vscode.workspace.registerFileSystemProvider(SCENARIO_SCHEME, scenarioFsProvider, { isCaseSensitive: true })
  );

  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration((event) => {
      if (event.affectsConfiguration('pockethive')) {
        settingsProvider.refresh();
      }
    })
  );
}

export function deactivate(): void {
  disposeOutputChannel();
}
