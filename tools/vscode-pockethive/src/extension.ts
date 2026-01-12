import * as vscode from 'vscode';
import { randomUUID } from 'crypto';

type SwarmSummary = {
  id: string;
  status?: string;
  health?: string;
  heartbeat?: string;
  templateId?: string;
};

type ScenarioSummary = {
  id: string;
  name?: string;
};

type ScenarioDetail = {
  id?: string;
  name?: string;
  description?: string;
  template?: {
    image?: string;
    bees?: ScenarioBee[];
  };
  plan?: unknown;
};

type ScenarioBee = {
  role?: string;
  image?: string;
  work?: {
    in?: string;
    out?: string;
  };
};

type JournalEntry = {
  timestamp?: string;
  swarmId?: string;
  kind?: string;
  type?: string;
  origin?: string;
  scope?: {
    role?: string;
    instance?: string;
  };
  [key: string]: unknown;
};

type JournalPage = {
  items?: JournalEntry[];
};

const DEFAULT_ORCHESTRATOR_URL = 'http://localhost:8088/orchestrator';
const DEFAULT_SCENARIO_MANAGER_URL = 'http://localhost:8088/scenario-manager';
const SCENARIO_SCHEME = 'pockethive-scenario';
const SCENARIO_FILE_NAMES = new Set(['scenario.yaml', 'scenario.yml']);

let outputChannel: vscode.OutputChannel;

export function activate(context: vscode.ExtensionContext): void {
  outputChannel = vscode.window.createOutputChannel('PocketHive');
  context.subscriptions.push(outputChannel);

  const actionsProvider = new ActionsProvider();
  const hiveProvider = new HiveProvider();
  const buzzProvider = new BuzzProvider();
  const journalProvider = new JournalProvider();
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
    vscode.window.registerTreeDataProvider('pockethive.actions', actionsProvider),
    vscode.window.registerTreeDataProvider('pockethive.hive', hiveProvider),
    vscode.window.registerTreeDataProvider('pockethive.buzz', buzzProvider),
    vscode.window.registerTreeDataProvider('pockethive.journal', journalProvider),
    vscode.window.registerTreeDataProvider('pockethive.scenario', scenarioProvider),
    vscode.workspace.registerFileSystemProvider(SCENARIO_SCHEME, scenarioFsProvider, { isCaseSensitive: true })
  );
}

export function deactivate(): void {
  if (outputChannel) {
    outputChannel.dispose();
  }
}

class ActionsProvider implements vscode.TreeDataProvider<ActionItem> {
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

type HiveNode =
  | { kind: 'swarm'; swarm: SwarmSummary }
  | { kind: 'action'; swarmId: string; action: 'start' | 'stop' | 'remove' }
  | { kind: 'message'; message: string };

class HiveProvider implements vscode.TreeDataProvider<HiveNode> {
  private readonly emitter = new vscode.EventEmitter<HiveNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;

  refresh(): void {
    this.emitter.fire(undefined);
  }

  getTreeItem(element: HiveNode): vscode.TreeItem {
    if (element.kind === 'message') {
      return new vscode.TreeItem(element.message, vscode.TreeItemCollapsibleState.None);
    }

    if (element.kind === 'action') {
      const label = actionLabel(element.action);
      const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.None);
      item.iconPath = new vscode.ThemeIcon(actionIcon(element.action));
      item.command = { command: `pockethive.${element.action}Swarm`, title: label, arguments: [element.swarmId] };
      return item;
    }

    const item = new vscode.TreeItem(element.swarm.id, vscode.TreeItemCollapsibleState.Collapsed);
    item.description = formatSwarmDescription(element.swarm);
    item.tooltip = JSON.stringify(element.swarm, null, 2);
    item.command = { command: 'pockethive.openSwarmDetails', title: 'Open swarm details', arguments: [element.swarm.id] };
    return item;
  }

  async getChildren(element?: HiveNode): Promise<HiveNode[]> {
    if (element?.kind === 'swarm') {
      return [
        { kind: 'action', swarmId: element.swarm.id, action: 'start' },
        { kind: 'action', swarmId: element.swarm.id, action: 'stop' },
        { kind: 'action', swarmId: element.swarm.id, action: 'remove' }
      ];
    }

    if (element) {
      return [];
    }

    const config = resolveServiceConfig('orchestratorUrl');
    if ('error' in config) {
      return [{ kind: 'message', message: config.error }];
    }

    try {
      const swarms = await requestJson<SwarmSummary[]>(config.baseUrl, config.authToken, 'GET', '/api/swarms');
      if (!swarms.length) {
        return [{ kind: 'message', message: 'No swarms found.' }];
      }
      return swarms.map((swarm) => ({ kind: 'swarm', swarm }));
    } catch (error) {
      return [{ kind: 'message', message: `Failed to load swarms: ${formatError(error)}` }];
    }
  }
}

type BuzzNode = { kind: 'entry'; entry: JournalEntry } | { kind: 'message'; message: string };

class BuzzProvider implements vscode.TreeDataProvider<BuzzNode> {
  private readonly emitter = new vscode.EventEmitter<BuzzNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;

  refresh(): void {
    this.emitter.fire(undefined);
  }

  getTreeItem(element: BuzzNode): vscode.TreeItem {
    if (element.kind === 'message') {
      return new vscode.TreeItem(element.message, vscode.TreeItemCollapsibleState.None);
    }

    const label = formatEntryLabel(element.entry);
    const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.None);
    item.description = formatEntryDescription(element.entry);
    item.command = { command: 'pockethive.showEntry', title: 'Show entry', arguments: [element.entry, 'Buzz entry'] };
    return item;
  }

  async getChildren(): Promise<BuzzNode[]> {
    const config = resolveServiceConfig('orchestratorUrl');
    if ('error' in config) {
      return [{ kind: 'message', message: config.error }];
    }

    try {
      const page = await requestJson<JournalPage>(
        config.baseUrl,
        config.authToken,
        'GET',
        '/api/journal/hive/page?limit=50'
      );
      const entries = Array.isArray(page.items) ? page.items : [];
      if (!entries.length) {
        return [{ kind: 'message', message: 'No Buzz entries yet.' }];
      }
      return entries.map((entry) => ({ kind: 'entry', entry }));
    } catch (error) {
      return [{ kind: 'message', message: `Buzz unavailable: ${formatError(error)}` }];
    }
  }
}

type JournalNode =
  | { kind: 'swarm'; swarm: SwarmSummary }
  | { kind: 'entry'; entry: JournalEntry }
  | { kind: 'message'; message: string };

class JournalProvider implements vscode.TreeDataProvider<JournalNode> {
  private readonly emitter = new vscode.EventEmitter<JournalNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;

  refresh(): void {
    this.emitter.fire(undefined);
  }

  getTreeItem(element: JournalNode): vscode.TreeItem {
    if (element.kind === 'message') {
      return new vscode.TreeItem(element.message, vscode.TreeItemCollapsibleState.None);
    }

    if (element.kind === 'entry') {
      const item = new vscode.TreeItem(formatEntryLabel(element.entry), vscode.TreeItemCollapsibleState.None);
      item.description = formatEntryDescription(element.entry);
      item.command = { command: 'pockethive.showEntry', title: 'Show entry', arguments: [element.entry, 'Journal entry'] };
      return item;
    }

    const item = new vscode.TreeItem(element.swarm.id, vscode.TreeItemCollapsibleState.Collapsed);
    item.description = formatSwarmDescription(element.swarm);
    return item;
  }

  async getChildren(element?: JournalNode): Promise<JournalNode[]> {
    const config = resolveServiceConfig('orchestratorUrl');
    if ('error' in config) {
      return [{ kind: 'message', message: config.error }];
    }

    if (!element) {
      try {
        const swarms = await requestJson<SwarmSummary[]>(config.baseUrl, config.authToken, 'GET', '/api/swarms');
        if (!swarms.length) {
          return [{ kind: 'message', message: 'No swarms found.' }];
        }
        return swarms.map((swarm) => ({ kind: 'swarm', swarm }));
      } catch (error) {
        return [{ kind: 'message', message: `Failed to load swarms: ${formatError(error)}` }];
      }
    }

    if (element.kind !== 'swarm') {
      return [];
    }

    try {
      const entries = await requestJson<JournalEntry[]>(
        config.baseUrl,
        config.authToken,
        'GET',
        `/api/swarms/${encodeURIComponent(element.swarm.id)}/journal`
      );
      const tail = entries.slice(Math.max(entries.length - 50, 0));
      if (!tail.length) {
        return [{ kind: 'message', message: 'No journal entries.' }];
      }
      return tail.map((entry) => ({ kind: 'entry', entry }));
    } catch (error) {
      return [{ kind: 'message', message: `Journal unavailable: ${formatError(error)}` }];
    }
  }
}

type ScenarioNode = { kind: 'scenario'; scenario: ScenarioSummary } | { kind: 'message'; message: string };

class ScenarioProvider implements vscode.TreeDataProvider<ScenarioNode> {
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

async function configureOrchestratorUrl(): Promise<void> {
  const current = getConfigValue('orchestratorUrl');
  const next = await vscode.window.showInputBox({
    prompt: 'Orchestrator base URL (example: http://localhost:8088/orchestrator)',
    value: current ?? DEFAULT_ORCHESTRATOR_URL,
    ignoreFocusOut: true,
    validateInput: (value) => (value.trim().length === 0 ? 'Orchestrator URL is required.' : null)
  });

  if (!next) {
    return;
  }

  await vscode.workspace.getConfiguration('pockethive').update('orchestratorUrl', next.trim(), true);
  vscode.window.showInformationMessage('PocketHive: Orchestrator URL updated.');
}

async function configureScenarioManagerUrl(): Promise<void> {
  const current = getConfigValue('scenarioManagerUrl');
  const next = await vscode.window.showInputBox({
    prompt: 'Scenario Manager base URL (example: http://localhost:8088/scenario-manager)',
    value: current ?? DEFAULT_SCENARIO_MANAGER_URL,
    ignoreFocusOut: true,
    validateInput: (value) => (value.trim().length === 0 ? 'Scenario Manager URL is required.' : null)
  });

  if (!next) {
    return;
  }

  await vscode.workspace.getConfiguration('pockethive').update('scenarioManagerUrl', next.trim(), true);
  vscode.window.showInformationMessage('PocketHive: Scenario Manager URL updated.');
}

async function listSwarms(): Promise<void> {
  await withService('orchestratorUrl', async (baseUrl, authToken) => {
    const swarms = await requestJson<SwarmSummary[]>(baseUrl, authToken, 'GET', '/api/swarms');
    outputChannel.appendLine(`[${new Date().toISOString()}] GET /api/swarms`);
    outputChannel.appendLine(JSON.stringify(swarms, null, 2));
    outputChannel.show(true);
    vscode.window.showInformationMessage(`PocketHive: ${swarms.length} swarms listed.`);
  });
}

async function runSwarmCommand(action: 'start' | 'stop' | 'remove', swarmId?: string): Promise<void> {
  await withService('orchestratorUrl', async (baseUrl, authToken) => {
    const target = swarmId ?? (await pickSwarmId(baseUrl, authToken));
    if (!target) {
      return;
    }

    const body = { idempotencyKey: randomUUID() };
    const response = await requestJson<Record<string, unknown>>(
      baseUrl,
      authToken,
      'POST',
      `/api/swarms/${encodeURIComponent(target)}/${action}`,
      body
    );

    outputChannel.appendLine(`[${new Date().toISOString()}] POST /api/swarms/${target}/${action}`);
    outputChannel.appendLine(JSON.stringify(response, null, 2));
    outputChannel.show(true);
    vscode.window.showInformationMessage(`PocketHive: ${action} accepted for swarm '${target}'.`);
  });
}

async function openOrchestrator(): Promise<void> {
  await withService('orchestratorUrl', async (baseUrl) => {
    await vscode.env.openExternal(vscode.Uri.parse(baseUrl));
  });
}

async function openSwarmDetails(swarmId?: string): Promise<void> {
  await withService('orchestratorUrl', async (baseUrl, authToken) => {
    const target = swarmId ?? (await pickSwarmId(baseUrl, authToken));
    if (!target) {
      return;
    }

    const swarm = await requestJson<Record<string, unknown>>(
      baseUrl,
      authToken,
      'GET',
      `/api/swarms/${encodeURIComponent(target)}`
    );

    await openJsonDocument(`Swarm ${target}`, swarm);
  });
}

async function openScenarioRaw(scenarioId?: string | ScenarioSummary | ScenarioNode): Promise<void> {
  await withService('scenarioManagerUrl', async (baseUrl, authToken) => {
    const target = resolveScenarioId(scenarioId) ?? (await pickScenarioId(baseUrl, authToken));
    if (!target) {
      return;
    }

    outputChannel.appendLine(
      `[${new Date().toISOString()}] OPEN scenario ${target} via ${baseUrl}/scenarios/{id}/raw`
    );
    const uri = scenarioUri(target);
    const document = await vscode.workspace.openTextDocument(uri);
    if (document.languageId === 'plaintext') {
      await vscode.languages.setTextDocumentLanguage(document, 'yaml');
    }
    await vscode.window.showTextDocument(document, { preview: false });
  });
}

async function previewScenario(scenarioId?: string | ScenarioSummary | ScenarioNode): Promise<void> {
  await withService('scenarioManagerUrl', async (baseUrl, authToken) => {
    const target = resolveScenarioId(scenarioId) ?? (await pickScenarioId(baseUrl, authToken));
    if (!target) {
      return;
    }

    outputChannel.appendLine(
      `[${new Date().toISOString()}] PREVIEW scenario ${target} via ${baseUrl}/scenarios/{id}`
    );
    const scenario = await requestJson<ScenarioDetail>(
      baseUrl,
      authToken,
      'GET',
      `/scenarios/${encodeURIComponent(target)}`
    );

    const panel = vscode.window.createWebviewPanel(
      'pockethiveScenarioPreview',
      `Scenario: ${target}`,
      vscode.ViewColumn.Beside,
      { enableFindWidget: true }
    );
    panel.webview.html = renderScenarioPreviewHtml(scenario);
  });
}

async function showEntry(entry: JournalEntry, title?: string): Promise<void> {
  await openJsonDocument(title ?? 'Entry', entry);
}

async function withService<T>(
  key: 'orchestratorUrl' | 'scenarioManagerUrl',
  action: (baseUrl: string, authToken?: string) => Promise<T>
): Promise<T | undefined> {
  const config = resolveServiceConfig(key);
  if ('error' in config) {
    vscode.window.showErrorMessage(config.error);
    return undefined;
  }

  outputChannel.appendLine(`[${new Date().toISOString()}] CONFIG ${key}=${config.baseUrl}`);
  try {
    return await action(config.baseUrl, config.authToken);
  } catch (error) {
    const message = formatError(error);
    outputChannel.appendLine(`[${new Date().toISOString()}] ERROR ${message}`);
    outputChannel.show(true);
    vscode.window.showErrorMessage(`PocketHive: ${message}`);
    return undefined;
  }
}

function resolveServiceConfig(key: 'orchestratorUrl' | 'scenarioManagerUrl'):
  | { baseUrl: string; authToken?: string }
  | { error: string } {
  const raw = getConfigValue(key);
  if (!raw || raw.trim().length === 0) {
    return { error: `PocketHive: pockethive.${key} is not set.` };
  }

  const baseUrl = raw.trim().replace(/\/+$/, '');
  const authToken = getConfigValue('authToken')?.trim();
  return { baseUrl, authToken: authToken && authToken.length > 0 ? authToken : undefined };
}

function getConfigValue(key: 'orchestratorUrl' | 'scenarioManagerUrl' | 'authToken'): string | undefined {
  const config = vscode.workspace.getConfiguration('pockethive');
  if (key === 'orchestratorUrl') {
    return config.get<string>(key, DEFAULT_ORCHESTRATOR_URL);
  }
  if (key === 'scenarioManagerUrl') {
    return config.get<string>(key, DEFAULT_SCENARIO_MANAGER_URL);
  }
  return config.get<string>(key);
}

async function requestJson<T>(
  baseUrl: string,
  authToken: string | undefined,
  method: 'GET' | 'POST',
  path: string,
  body?: Record<string, unknown>
): Promise<T> {
  const url = `${baseUrl}${path}`;
  const headers: Record<string, string> = {
    Accept: 'application/json'
  };

  if (body) {
    headers['Content-Type'] = 'application/json';
  }

  if (authToken) {
    headers.Authorization = `Bearer ${authToken}`;
  }

  const response = await fetch(url, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined
  });

  const text = await response.text();

  if (!response.ok) {
    const statusLine = `${response.status} ${response.statusText}`.trim();
    throw new Error(text ? `${statusLine}: ${text}` : statusLine);
  }

  if (!text) {
    return undefined as T;
  }

  try {
    return JSON.parse(text) as T;
  } catch (error) {
    throw new Error(`Invalid JSON response from ${path}.`);
  }
}

async function requestText(
  baseUrl: string,
  authToken: string | undefined,
  method: 'GET' | 'POST' | 'PUT',
  path: string,
  body?: string
): Promise<string> {
  const url = `${baseUrl}${path}`;
  const headers: Record<string, string> = {
    Accept: 'text/plain'
  };

  if (body) {
    headers['Content-Type'] = 'text/plain';
  }

  if (authToken) {
    headers.Authorization = `Bearer ${authToken}`;
  }

  const response = await fetch(url, {
    method,
    headers,
    body
  });

  const text = await response.text();

  if (!response.ok) {
    const statusLine = `${response.status} ${response.statusText}`.trim();
    throw new Error(text ? `${statusLine}: ${text}` : statusLine);
  }

  return text;
}

async function pickSwarmId(baseUrl: string, authToken: string | undefined): Promise<string | undefined> {
  let swarms: SwarmSummary[] = [];

  try {
    swarms = await requestJson<SwarmSummary[]>(baseUrl, authToken, 'GET', '/api/swarms');
  } catch (error) {
    outputChannel.appendLine(`[${new Date().toISOString()}] WARN ${formatError(error)}`);
  }

  if (swarms.length > 0) {
    const items = swarms.map((swarm) => ({
      label: swarm.id,
      description: swarm.status ?? 'unknown',
      detail: swarm.health ? `health: ${swarm.health}` : undefined,
      swarmId: swarm.id
    }));

    const pick = await vscode.window.showQuickPick(items, {
      placeHolder: 'Select a swarm',
      ignoreFocusOut: true
    });

    return pick?.swarmId;
  }

  const manual = await vscode.window.showInputBox({
    prompt: 'Swarm id',
    ignoreFocusOut: true,
    validateInput: (value) => (value.trim().length === 0 ? 'Swarm id is required.' : null)
  });

  return manual?.trim();
}

async function pickScenarioId(baseUrl: string, authToken: string | undefined): Promise<string | undefined> {
  let scenarios: ScenarioSummary[] = [];

  try {
    scenarios = await requestJson<ScenarioSummary[]>(baseUrl, authToken, 'GET', '/scenarios');
  } catch (error) {
    outputChannel.appendLine(`[${new Date().toISOString()}] WARN ${formatError(error)}`);
  }

  if (scenarios.length > 0) {
    const items = scenarios.map((scenario) => ({
      label: scenario.name || scenario.id,
      description: scenario.name && scenario.name !== scenario.id ? scenario.id : undefined,
      scenarioId: scenario.id
    }));

    const pick = await vscode.window.showQuickPick(items, {
      placeHolder: 'Select a scenario',
      ignoreFocusOut: true
    });

    return pick?.scenarioId;
  }

  const manual = await vscode.window.showInputBox({
    prompt: 'Scenario id',
    ignoreFocusOut: true,
    validateInput: (value) => (value.trim().length === 0 ? 'Scenario id is required.' : null)
  });

  return manual?.trim();
}

async function openJsonDocument(title: string, data: unknown): Promise<void> {
  const content = JSON.stringify(data, null, 2);
  const document = await vscode.workspace.openTextDocument({ content, language: 'json' });
  await vscode.window.showTextDocument(document, { preview: false });
  outputChannel.appendLine(`[${new Date().toISOString()}] OPEN ${title}`);
  outputChannel.show(true);
}

function formatSwarmDescription(swarm: SwarmSummary): string {
  const parts: string[] = [];
  if (swarm.status) {
    parts.push(swarm.status);
  }
  if (swarm.health) {
    parts.push(swarm.health);
  }
  return parts.join(' / ');
}

function actionLabel(action: 'start' | 'stop' | 'remove'): string {
  if (action === 'start') {
    return 'Start';
  }
  if (action === 'stop') {
    return 'Stop';
  }
  return 'Remove';
}

function actionIcon(action: 'start' | 'stop' | 'remove'): string {
  if (action === 'start') {
    return 'play';
  }
  if (action === 'stop') {
    return 'debug-stop';
  }
  return 'trash';
}

function formatEntryLabel(entry: JournalEntry): string {
  const timestamp = entry.timestamp ? entry.timestamp.replace('T', ' ').replace('Z', '') : 'unknown time';
  const kind = entry.kind ?? 'event';
  const type = entry.type ?? 'unknown';
  return `${timestamp} ${kind}/${type}`;
}

function formatEntryDescription(entry: JournalEntry): string | undefined {
  const role = entry.scope?.role;
  const instance = entry.scope?.instance;
  if (role && instance) {
    return `${role}/${instance}`;
  }
  return entry.origin;
}

function formatError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function resolveScenarioId(arg: unknown): string | undefined {
  if (!arg) {
    return undefined;
  }
  if (typeof arg === 'string') {
    return arg;
  }
  if (typeof arg === 'object') {
    if ('id' in arg && typeof (arg as { id?: unknown }).id === 'string') {
      return (arg as { id: string }).id;
    }
    if ('scenario' in arg) {
      const scenario = (arg as { scenario?: unknown }).scenario;
      if (scenario && typeof scenario === 'object' && 'id' in scenario) {
        const id = (scenario as { id?: unknown }).id;
        return typeof id === 'string' ? id : undefined;
      }
    }
  }
  return undefined;
}

function renderScenarioPreviewHtml(scenario: ScenarioDetail): string {
  const bees = scenario.template?.bees ?? [];
  const title = escapeHtml(scenario.name ?? scenario.id ?? 'Scenario preview');
  const description = scenario.description ? escapeHtml(scenario.description) : '';
  const controllerImage = scenario.template?.image ? escapeHtml(scenario.template.image) : 'unknown';
  const beeCards = bees.length
    ? bees
        .map((bee) => {
          const role = escapeHtml(bee.role ?? 'unknown');
          const image = escapeHtml(bee.image ?? 'unknown');
          const input = escapeHtml(bee.work?.in ?? '-');
          const output = escapeHtml(bee.work?.out ?? '-');
          return `
            <div class="bee-card">
              <div class="bee-title">${role}</div>
              <div class="bee-row"><span>image</span><strong>${image}</strong></div>
              <div class="bee-row"><span>in</span><strong>${input}</strong></div>
              <div class="bee-row"><span>out</span><strong>${output}</strong></div>
            </div>
          `;
        })
        .join('')
    : '<div class="empty">No bees declared.</div>';

  return `<!DOCTYPE html>
  <html lang="en">
    <head>
      <meta charset="UTF-8">
      <meta name="viewport" content="width=device-width, initial-scale=1.0">
      <style>
        :root {
          color-scheme: light dark;
        }
        body {
          margin: 0;
          padding: 20px;
          font-family: var(--vscode-font-family);
          color: var(--vscode-foreground);
          background: var(--vscode-editor-background);
        }
        h1 {
          font-size: 20px;
          margin: 0 0 6px 0;
        }
        .muted {
          color: var(--vscode-descriptionForeground);
          margin-bottom: 16px;
        }
        .summary {
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
          gap: 12px;
          margin-bottom: 16px;
        }
        .summary-card {
          border: 1px solid var(--vscode-panel-border);
          border-radius: 8px;
          padding: 12px;
          background: var(--vscode-editorWidget-background);
        }
        .summary-card span {
          display: block;
          font-size: 11px;
          text-transform: uppercase;
          letter-spacing: 0.04em;
          color: var(--vscode-descriptionForeground);
          margin-bottom: 6px;
        }
        .bee-grid {
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
          gap: 12px;
        }
        .bee-card {
          border: 1px solid var(--vscode-panel-border);
          border-radius: 10px;
          padding: 12px;
          background: var(--vscode-editorWidget-background);
        }
        .bee-title {
          font-size: 14px;
          font-weight: 600;
          margin-bottom: 8px;
        }
        .bee-row {
          display: flex;
          justify-content: space-between;
          font-size: 12px;
          padding: 4px 0;
          border-top: 1px solid var(--vscode-panel-border);
        }
        .bee-row span {
          color: var(--vscode-descriptionForeground);
        }
        .empty {
          padding: 12px;
          border: 1px dashed var(--vscode-panel-border);
          border-radius: 8px;
          color: var(--vscode-descriptionForeground);
        }
      </style>
    </head>
    <body>
      <h1>${title}</h1>
      <div class="muted">${description}</div>
      <div class="summary">
        <div class="summary-card">
          <span>Scenario id</span>
          <div>${escapeHtml(scenario.id ?? 'unknown')}</div>
        </div>
        <div class="summary-card">
          <span>Controller image</span>
          <div>${controllerImage}</div>
        </div>
        <div class="summary-card">
          <span>Bees</span>
          <div>${bees.length}</div>
        </div>
      </div>
      <h2>Bees</h2>
      <div class="bee-grid">
        ${beeCards}
      </div>
    </body>
  </html>`;
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

class ScenarioFileSystemProvider implements vscode.FileSystemProvider {
  private readonly emitter = new vscode.EventEmitter<vscode.FileChangeEvent[]>();
  readonly onDidChangeFile = this.emitter.event;
  private readonly stats = new Map<string, vscode.FileStat>();

  watch(): vscode.Disposable {
    return new vscode.Disposable(() => undefined);
  }

  async stat(uri: vscode.Uri): Promise<vscode.FileStat> {
    const parsed = parseScenarioPath(uri);
    if (!parsed) {
      throw vscode.FileSystemError.FileNotFound(uri);
    }

    const existing = this.stats.get(uri.toString());
    if (existing) {
      return existing;
    }

    const now = Date.now();
    if (parsed.kind === 'root' || parsed.kind === 'scenario') {
      return { type: vscode.FileType.Directory, ctime: now, mtime: now, size: 0 };
    }

    return { type: vscode.FileType.File, ctime: now, mtime: now, size: 0 };
  }

  async readDirectory(uri: vscode.Uri): Promise<[string, vscode.FileType][]> {
    const parsed = parseScenarioPath(uri);
    if (!parsed) {
      throw vscode.FileSystemError.FileNotFound(uri);
    }

    if (parsed.kind === 'root') {
      const config = resolveServiceConfig('scenarioManagerUrl');
      if ('error' in config) {
        throw vscode.FileSystemError.Unavailable(config.error);
      }

      const scenarios = await requestJson<ScenarioSummary[]>(config.baseUrl, config.authToken, 'GET', '/scenarios');
      return scenarios.map((scenario) => [scenario.id, vscode.FileType.Directory]);
    }

    if (parsed.kind === 'scenario') {
      return [['scenario.yaml', vscode.FileType.File]];
    }

    return [];
  }

  async readFile(uri: vscode.Uri): Promise<Uint8Array> {
    const parsed = parseScenarioPath(uri);
    if (!parsed || parsed.kind !== 'file') {
      throw vscode.FileSystemError.FileNotFound(uri);
    }

    const config = resolveServiceConfig('scenarioManagerUrl');
    if ('error' in config) {
      throw vscode.FileSystemError.Unavailable(config.error);
    }

    const raw = await requestText(
      config.baseUrl,
      config.authToken,
      'GET',
      `/scenarios/${encodeURIComponent(parsed.scenarioId)}/raw`
    );
    const content = Buffer.from(raw, 'utf8');
    this.stats.set(uri.toString(), {
      type: vscode.FileType.File,
      ctime: Date.now(),
      mtime: Date.now(),
      size: content.length
    });
    return content;
  }

  async writeFile(uri: vscode.Uri, content: Uint8Array): Promise<void> {
    const parsed = parseScenarioPath(uri);
    if (!parsed || parsed.kind !== 'file') {
      throw vscode.FileSystemError.FileNotFound(uri);
    }

    const config = resolveServiceConfig('scenarioManagerUrl');
    if ('error' in config) {
      throw vscode.FileSystemError.Unavailable(config.error);
    }

    await requestText(
      config.baseUrl,
      config.authToken,
      'PUT',
      `/scenarios/${encodeURIComponent(parsed.scenarioId)}/raw`,
      Buffer.from(content).toString('utf8')
    );

    this.stats.set(uri.toString(), {
      type: vscode.FileType.File,
      ctime: Date.now(),
      mtime: Date.now(),
      size: content.length
    });
    this.emitter.fire([{ type: vscode.FileChangeType.Changed, uri }]);
  }

  createDirectory(): void {
    throw vscode.FileSystemError.NoPermissions('Scenario filesystem is read/write per file only.');
  }

  delete(): void {
    throw vscode.FileSystemError.NoPermissions('Scenario filesystem deletion is not supported.');
  }

  rename(): void {
    throw vscode.FileSystemError.NoPermissions('Scenario filesystem rename is not supported.');
  }
}

function scenarioUri(scenarioId: string): vscode.Uri {
  return vscode.Uri.from({ scheme: SCENARIO_SCHEME, path: `/${scenarioId}/scenario.yaml` });
}

function parseScenarioPath(uri: vscode.Uri):
  | { kind: 'root' }
  | { kind: 'scenario'; scenarioId: string }
  | { kind: 'file'; scenarioId: string }
  | null {
  const segments = uri.path.split('/').filter(Boolean);
  if (segments.length === 0) {
    return { kind: 'root' };
  }
  if (segments.length === 1) {
    return { kind: 'scenario', scenarioId: segments[0] };
  }
  if (segments.length === 2 && SCENARIO_FILE_NAMES.has(segments[1])) {
    return { kind: 'file', scenarioId: segments[0] };
  }
  return null;
}
