import * as vscode from 'vscode';

import {
  listSwarms,
  runSwarmCommand, runAllSwarms, openUi, openSwarmDetails,
  openScenarioRaw, openScenarioFile, previewScenario, showEntry,
  // New MCP-backed commands
  addEnvironmentCommand, setActiveEnvironmentCommand, removeEnvironmentCommand,
  addBundlesFolderCommand, setActiveBundlesFolderCommand,
  validateBundleCommand, deployBundleCommand,
  startSwarmMcp, stopSwarmMcp, removeSwarmMcp,
  openSwarmDetailsMcp, openJournalMcp, openQueuesMcp,
  restartMcpServerCommand,
} from './commands';

import { registerAutoRefresh } from './autoRefresh';
import { PREVIEW_SCHEME, SCENARIO_SCHEME } from './constants';
import { ScenarioEditorProvider } from './editors/scenarioEditor';
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
import { startMcpServer, stopMcpServer, onMcpStatusChange } from './mcp/manager';
import { migrateSettingsIfNeeded } from './config';

let statusBarItem: vscode.StatusBarItem;

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const outputChannel = initOutputChannel();
  context.subscriptions.push(outputChannel);

  // Migrate legacy hiveUrls → environments on first run
  await migrateSettingsIfNeeded();

  // Providers
  const previewProvider = initPreviewProvider();
  const hiveProvider = new HiveProvider();
  const buzzProvider = new BuzzProvider(loadTimeWindow(context.workspaceState, 'buzzTimeWindowMs'));
  const journalProvider = new JournalProvider(loadTimeWindow(context.workspaceState, 'journalTimeWindowMs'));
  const scenarioProvider = new ScenarioProvider();
  const scenarioFsProvider = new ScenarioFileSystemProvider();
  const settingsProvider = new SettingsProvider();

  registerAutoRefresh(context, [
    { key: 'hive', refresh: () => hiveProvider.refresh() },
    { key: 'buzz', refresh: () => buzzProvider.refresh() },
    { key: 'journal', refresh: () => journalProvider.refresh() },
    { key: 'scenario', refresh: () => scenarioProvider.refresh() },
    { key: 'settings', refresh: () => settingsProvider.refresh() },
  ]);

  // Status bar
  statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 10);
  statusBarItem.command = 'pockethive.showSettings';
  updateStatusBar('stopped');
  statusBarItem.show();
  context.subscriptions.push(statusBarItem);

  onMcpStatusChange(status => {
    updateStatusBar(status);
    hiveProvider.refresh();
    scenarioProvider.refresh();
    settingsProvider.refresh();
  });

  // Start MCP server
  startMcpServer(context).catch(err => {
    outputChannel.appendLine(`[PocketHive] MCP server start failed: ${err}`);
  });

  context.subscriptions.push(
    // ── Existing commands ──────────────────────────────────────────────────
    ScenarioEditorProvider.register(context),
    vscode.commands.registerCommand('pockethive.listSwarms', listSwarms),
    vscode.commands.registerCommand('pockethive.startSwarm', (swarmId?: string) => runSwarmCommand('start', swarmId)),
    vscode.commands.registerCommand('pockethive.stopSwarm', (swarmId?: string) => runSwarmCommand('stop', swarmId)),
    vscode.commands.registerCommand('pockethive.removeSwarm', (swarmId?: string) => runSwarmCommand('remove', swarmId)),
    vscode.commands.registerCommand('pockethive.startAllSwarms', () => runAllSwarms('start').then(() => hiveProvider.refresh())),
    vscode.commands.registerCommand('pockethive.stopAllSwarms', () => runAllSwarms('stop').then(() => hiveProvider.refresh())),
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
      configureTimeWindow('Buzz', buzzProvider, context.workspaceState, 'buzzTimeWindowMs', option)),
    vscode.commands.registerCommand('pockethive.filterJournal', (option) =>
      configureTimeWindow('Journal', journalProvider, context.workspaceState, 'journalTimeWindowMs', option)),
    vscode.commands.registerCommand('pockethive.helpSettings', () => openHelp('settings')),
    vscode.commands.registerCommand('pockethive.helpHive', () => openHelp('hive')),
    vscode.commands.registerCommand('pockethive.helpBuzz', () => openHelp('buzz')),
    vscode.commands.registerCommand('pockethive.helpJournal', () => openHelp('journal')),
    vscode.commands.registerCommand('pockethive.helpScenario', () => openHelp('scenario')),

    // ── New MCP-backed commands ────────────────────────────────────────────
    vscode.commands.registerCommand('pockethive.addEnvironment', () => addEnvironmentCommand(context)),
    vscode.commands.registerCommand('pockethive.setActiveEnvironment', (target: unknown) => setActiveEnvironmentCommand(target, context)),
    vscode.commands.registerCommand('pockethive.removeEnvironment', (target: unknown) => removeEnvironmentCommand(target)),
    vscode.commands.registerCommand('pockethive.addBundlesFolder', () => addBundlesFolderCommand(context)),
    vscode.commands.registerCommand('pockethive.setActiveBundlesFolder', (target: unknown) => setActiveBundlesFolderCommand(target, context)),
    vscode.commands.registerCommand('pockethive.validateBundle', (target: unknown) => validateBundleCommand(target, scenarioProvider)),
    vscode.commands.registerCommand('pockethive.deployBundle', (target: unknown) => deployBundleCommand(target)),
    vscode.commands.registerCommand('pockethive.startSwarmMcp', (target: unknown) => startSwarmMcp(target).then(() => hiveProvider.refresh())),
    vscode.commands.registerCommand('pockethive.stopSwarmMcp', (target: unknown) => stopSwarmMcp(target).then(() => hiveProvider.refresh())),
    vscode.commands.registerCommand('pockethive.removeSwarmMcp', (target: unknown) => removeSwarmMcp(target, hiveProvider)),
    vscode.commands.registerCommand('pockethive.openSwarmDetailsMcp', (target: unknown) => openSwarmDetailsMcp(target)),
    vscode.commands.registerCommand('pockethive.openJournal', (target: unknown) => openJournalMcp(target)),
    vscode.commands.registerCommand('pockethive.openQueueMonitor', (target: unknown) => openQueuesMcp(target)),
    vscode.commands.registerCommand('pockethive.restartMcpServer', () => restartMcpServerCommand(context)),
    vscode.commands.registerCommand('pockethive.showSettings', () =>
      vscode.commands.executeCommand('pockethive.settings.focus')),

    // ── Tree view providers ────────────────────────────────────────────────
    vscode.window.registerTreeDataProvider('pockethive.hive', hiveProvider),
    vscode.window.registerTreeDataProvider('pockethive.buzz', buzzProvider),
    vscode.window.registerTreeDataProvider('pockethive.journal', journalProvider),
    vscode.window.registerTreeDataProvider('pockethive.scenario', scenarioProvider),
    vscode.window.registerTreeDataProvider('pockethive.settings', settingsProvider),
    vscode.workspace.registerTextDocumentContentProvider(PREVIEW_SCHEME, previewProvider),
    vscode.workspace.registerFileSystemProvider(SCENARIO_SCHEME, scenarioFsProvider, { isCaseSensitive: true }),

    // ── Config change listener ─────────────────────────────────────────────
    vscode.workspace.onDidChangeConfiguration(event => {
      if (event.affectsConfiguration('pockethive.activeEnvironment') ||
          event.affectsConfiguration('pockethive.environments') ||
          event.affectsConfiguration('pockethive.activeBundlesFolder') ||
          event.affectsConfiguration('pockethive.pockethiveRoot')) {
        startMcpServer(context).catch(() => {});
      }
      if (event.affectsConfiguration('pockethive')) {
        settingsProvider.refresh();
        hiveProvider.refresh();
        scenarioProvider.refresh();
      }
    }),
  );
}

export async function deactivate(): Promise<void> {
  await stopMcpServer();
  disposeOutputChannel();
}

function updateStatusBar(status: string): void {
  const env = vscode.workspace.getConfiguration('pockethive').get<string>('activeEnvironment') ?? '';
  const label = env || 'not configured';
  const dot = status === 'running' ? '●' : status === 'starting' ? '◌' : '○';
  statusBarItem.text = `🐝 PocketHive: ${label}  ${dot}`;
  statusBarItem.tooltip = `PocketHive MCP: ${status}`;
}
