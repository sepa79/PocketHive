import * as vscode from 'vscode';

import {
  resolveHiveBaseUrl,
  getEnvironments, getActiveEnvironmentName, setActiveEnvironment,
  addEnvironment, removeEnvironment, addBundlesFolder, setActiveBundlesFolder,
  getActiveBundlesFolder, migrateSettingsIfNeeded,
} from './config';
import { formatError, formatSwarmDescription } from './format';
import { scenarioUri } from './fs/scenarioFileSystemProvider';
import { getOutputChannel } from './output';
import { pickScenarioId, pickSwarmId } from './pickers';
import { openJsonPreview, openPreviewDocument } from './preview';
import { renderScenarioPreviewHtml } from './scenarioPreview';
import { ScenarioDetail, ScenarioSummary } from './types';
import { restartMcpServer } from './mcp/manager';
import * as McpTools from './mcp/tools';

// ── Environment management ────────────────────────────────────────────────────

export async function addEnvironmentCommand(context: vscode.ExtensionContext): Promise<void> {
  const name = await vscode.window.showInputBox({
    title: 'Add Environment (1/2)',
    prompt: 'Environment name (e.g. local, nft-remote)',
    ignoreFocusOut: true,
    validateInput: v => v.trim() ? null : 'Name is required',
  });
  if (!name) return;

  const baseUrl = await vscode.window.showInputBox({
    title: 'Add Environment (2/2)',
    prompt: 'PocketHive base URL (e.g. http://localhost:8088)',
    value: 'http://localhost:8088',
    ignoreFocusOut: true,
    validateInput: v => v.trim() ? null : 'URL is required',
  });
  if (!baseUrl) return;

  const authToken = await vscode.window.showInputBox({
    title: 'Add Environment (3/4)',
    prompt: 'Optional PocketHive auth token for this environment',
    password: true,
    ignoreFocusOut: true,
  });

  const authUsername = await vscode.window.showInputBox({
    title: 'Add Environment (4/4)',
    prompt: 'Optional local/dev auth username (used when auth token is blank)',
    value: authToken?.trim() ? '' : 'local-admin',
    ignoreFocusOut: true,
  });

  await addEnvironment({
    name: name.trim(),
    baseUrl: baseUrl.trim(),
    rabbitUser: 'guest',
    ...(authToken?.trim() ? { authToken: authToken.trim() } : {}),
    ...(authUsername?.trim() ? { authUsername: authUsername.trim() } : {}),
  });
  vscode.window.showInformationMessage(`PocketHive: Environment '${name}' added.`);
  await restartMcpServer(context);
}

export async function setActiveEnvironmentCommand(name: string, context: vscode.ExtensionContext): Promise<void> {
  await setActiveEnvironment(name);
  vscode.window.showInformationMessage(`PocketHive: Switched to '${name}'.`);
  await restartMcpServer(context);
}

export async function removeEnvironmentCommand(name: string): Promise<void> {
  const choice = await vscode.window.showWarningMessage(
    `Remove environment '${name}'?`, { modal: true }, 'Remove'
  );
  if (choice !== 'Remove') return;
  await removeEnvironment(name);
  vscode.window.showInformationMessage(`PocketHive: Environment '${name}' removed.`);
}

// ── Bundles folder management ─────────────────────────────────────────────────

export async function addBundlesFolderCommand(context: vscode.ExtensionContext): Promise<void> {
  const uris = await vscode.window.showOpenDialog({
    canSelectFolders: true, canSelectFiles: false, canSelectMany: false,
    openLabel: 'Select bundles folder',
  });
  if (!uris?.length) return;
  const folderPath = uris[0].fsPath;
  await addBundlesFolder(folderPath);
  await setActiveBundlesFolder(folderPath);
  vscode.window.showInformationMessage(`PocketHive: Bundles folder set to '${folderPath}'.`);
  await restartMcpServer(context);
}

export async function setActiveBundlesFolderCommand(folderPath: string, context: vscode.ExtensionContext): Promise<void> {
  await setActiveBundlesFolder(folderPath);
  vscode.window.showInformationMessage(`PocketHive: Active bundles folder set.`);
  await restartMcpServer(context);
}

// ── Bundle actions ────────────────────────────────────────────────────────────

export async function validateBundleCommand(
  bundleName: string,
  scenarioProvider: { setValidating(n: string): void; setValidationResult(n: string, s: 'passed' | 'failed', e?: string): void }
): Promise<void> {
  scenarioProvider.setValidating(bundleName);
  try {
    const { jobId } = await McpTools.bundleValidate(bundleName);
    // Poll until done
    let result: McpTools.ValidationResult;
    for (let i = 0; i < 30; i++) {
      await new Promise(r => setTimeout(r, 3000));
      result = await McpTools.bundleValidateResult(jobId);
      if (result.status !== 'running') break;
    }
    result = result!;
    if (result.status === 'error') {
      scenarioProvider.setValidationResult(bundleName, 'failed', result.error);
      vscode.window.showErrorMessage(`PocketHive: Validation error for '${bundleName}': ${result.error}`);
      return;
    }
    const structural = result.structural;
    if (!structural) {
      const message = result.note ?? 'No validation result was returned.';
      scenarioProvider.setValidationResult(bundleName, 'failed', message);
      vscode.window.showErrorMessage(`PocketHive: '${bundleName}' validation failed. ${message}`);
    } else if (!structural.ok) {
      const message = structural.errors[0]?.message ?? 'Bundle structure check failed.';
      scenarioProvider.setValidationResult(bundleName, 'failed', message);
      vscode.window.showErrorMessage(`PocketHive: '${bundleName}' validation failed. ${message}`);
    } else {
      scenarioProvider.setValidationResult(bundleName, 'passed');
      const warningText = structural.warnings.length ? ` ${structural.warnings.length} warning(s).` : '';
      vscode.window.showInformationMessage(`PocketHive: '${bundleName}' structure check passed.${warningText}`);
    }
  } catch (err) {
    scenarioProvider.setValidationResult(bundleName, 'failed', String(err));
    vscode.window.showErrorMessage(`PocketHive: Validation failed — ${String(err)}`);
  }
}

export async function deployBundleCommand(bundleName: string): Promise<void> {
  try {
    await McpTools.scenarioDeploy(bundleName);
    vscode.window.showInformationMessage(`PocketHive: '${bundleName}' deployed.`);
  } catch (err) {
    vscode.window.showErrorMessage(`PocketHive: Deploy failed — ${String(err)}`);
  }
}

// ── Swarm actions (MCP) ───────────────────────────────────────────────────────

export async function startSwarmMcp(swarmId: string): Promise<void> {
  try {
    await McpTools.swarmStart(swarmId);
    vscode.window.showInformationMessage(`PocketHive: Swarm '${swarmId}' started.`);
  } catch (err) {
    vscode.window.showErrorMessage(`PocketHive: Start failed — ${String(err)}`);
  }
}

export async function stopSwarmMcp(swarmId: string): Promise<void> {
  try {
    await McpTools.swarmStop(swarmId);
    vscode.window.showInformationMessage(`PocketHive: Swarm '${swarmId}' stopped.`);
  } catch (err) {
    vscode.window.showErrorMessage(`PocketHive: Stop failed — ${String(err)}`);
  }
}

export async function removeSwarmMcp(swarmId: string, hiveProvider: { refresh(): void }): Promise<void> {
  const choice = await vscode.window.showWarningMessage(
    `Remove swarm '${swarmId}'? This tears down all containers and queues.`,
    { modal: true }, 'Remove'
  );
  if (choice !== 'Remove') return;
  try {
    await McpTools.swarmRemove(swarmId);
    vscode.window.showInformationMessage(`PocketHive: Swarm '${swarmId}' removed.`);
    hiveProvider.refresh();
  } catch (err) {
    vscode.window.showErrorMessage(`PocketHive: Remove failed — ${String(err)}`);
  }
}

export async function openSwarmDetailsMcp(swarmId: string): Promise<void> {
  try {
    const detail = await McpTools.swarmGet(swarmId);
    await openJsonPreview(`Swarm ${swarmId}`, detail);
  } catch (err) {
    vscode.window.showErrorMessage(`PocketHive: ${String(err)}`);
  }
}

export async function openJournalMcp(swarmId: string): Promise<void> {
  try {
    const journal = await McpTools.debugJournal(swarmId, 50);
    await openJsonPreview(`Journal: ${swarmId}`, journal);
  } catch (err) {
    vscode.window.showErrorMessage(`PocketHive: ${String(err)}`);
  }
}

export async function openQueuesMcp(swarmId: string): Promise<void> {
  try {
    const queues = await McpTools.debugQueues(swarmId);
    await openJsonPreview(`Queues: ${swarmId}`, queues);
  } catch (err) {
    vscode.window.showErrorMessage(`PocketHive: ${String(err)}`);
  }
}

// ── MCP server ────────────────────────────────────────────────────────────────

export async function restartMcpServerCommand(context: vscode.ExtensionContext): Promise<void> {
  await restartMcpServer(context);
  vscode.window.showInformationMessage('PocketHive: MCP server restarted.');
}

// ── Direct API commands ───────────────────────────────────────────────────────

type ScenarioFileTarget = { scenarioId: string; kind: 'scenario' | 'schema' | 'template'; path?: string };

export async function listSwarms(): Promise<void> {
  await withMcp(async () => {
    const swarms = await McpTools.swarmList();
    const outputChannel = getOutputChannel();
    outputChannel.appendLine(`[${new Date().toISOString()}] MCP swarm.list`);
    outputChannel.appendLine(JSON.stringify(swarms, null, 2));
    outputChannel.show(true);
    vscode.window.showInformationMessage(`PocketHive: ${swarms.length} swarms listed.`);
  });
}

export async function runSwarmCommand(action: 'start' | 'stop' | 'remove', swarmTarget?: unknown): Promise<void> {
  await withMcp(async () => {
    const target = resolveSwarmId(swarmTarget) ?? (await pickSwarmId());
    if (!target) return;
    if (action === 'remove') {
      const choice = await vscode.window.showWarningMessage(
        `Remove swarm '${target}'? This will delete queues and stop the controller.`, { modal: true }, 'Remove'
      );
      if (choice !== 'Remove') return;
    }
    const response = action === 'start'
      ? await McpTools.swarmStart(target)
      : action === 'stop'
        ? await McpTools.swarmStop(target)
        : await McpTools.swarmRemove(target);
    const outputChannel = getOutputChannel();
    outputChannel.appendLine(`[${new Date().toISOString()}] MCP swarm.${action} ${target}`);
    outputChannel.appendLine(JSON.stringify(response, null, 2));
    outputChannel.show(true);
    vscode.window.showInformationMessage(`PocketHive: ${action} accepted for swarm '${target}'.`);
  });
}

export async function runAllSwarms(action: 'start' | 'stop'): Promise<void> {
  await withMcp(async () => {
    const swarms = await McpTools.swarmList();
    if (!swarms.length) { vscode.window.showInformationMessage('PocketHive: No swarms found.'); return; }
    const outputChannel = getOutputChannel();
    outputChannel.appendLine(`[${new Date().toISOString()}] MCP ${action.toUpperCase()} all swarms (${swarms.length})`);
    const failures: string[] = [];
    for (const swarm of swarms) {
      try {
        if (action === 'start') await McpTools.swarmStart(swarm.id);
        else await McpTools.swarmStop(swarm.id);
      } catch (error) { failures.push(`${swarm.id}: ${formatError(error)}`); }
    }
    if (failures.length > 0) {
      outputChannel.appendLine(`[${new Date().toISOString()}] WARN failures:`);
      failures.forEach((line) => outputChannel.appendLine(line));
      outputChannel.show(true);
      vscode.window.showWarningMessage(`PocketHive: ${failures.length} swarms failed to ${action}.`);
    } else {
      vscode.window.showInformationMessage(`PocketHive: ${action} sent to ${swarms.length} swarms.`);
    }
  });
}

export async function openUi(): Promise<void> {
  const base = resolveHiveBaseUrl();
  if ('error' in base) { vscode.window.showErrorMessage(base.error); return; }
  await vscode.env.openExternal(vscode.Uri.parse(base.baseUrl));
}

export async function openSwarmDetails(swarmId?: string): Promise<void> {
  await withMcp(async () => {
    const target = swarmId ?? (await pickSwarmId());
    if (!target) return;
    const swarm = await McpTools.swarmGet(target);
    await openJsonPreview(`Swarm ${target}`, swarm);
  });
}

export async function openScenarioRaw(scenarioId?: string | ScenarioSummary | { scenario?: ScenarioSummary }): Promise<void> {
  await withMcp(async () => {
    const target = resolveScenarioId(scenarioId) ?? (await pickScenarioId());
    if (!target) return;
    const outputChannel = getOutputChannel();
    outputChannel.appendLine(`[${new Date().toISOString()}] MCP OPEN scenario ${target}`);
    const uri = scenarioUri(target);
    try {
      await vscode.commands.executeCommand('vscode.openWith', uri, 'pockethive.scenarioEditor');
      return;
    } catch (error) {
      outputChannel.appendLine(`[${new Date().toISOString()}] WARN scenario editor failed: ${formatError(error)}`);
    }
    const document = await vscode.workspace.openTextDocument(uri);
    if (document.languageId === 'plaintext') await vscode.languages.setTextDocumentLanguage(document, 'yaml');
    await vscode.window.showTextDocument(document, { preview: true });
  });
}

export async function previewScenario(scenarioId?: string | ScenarioSummary | { scenario?: ScenarioSummary }): Promise<void> {
  await withMcp(async () => {
    const target = resolveScenarioId(scenarioId) ?? (await pickScenarioId());
    if (!target) return;
    const scenario = await McpTools.scenarioGet(target) as ScenarioDetail;
    const panel = vscode.window.createWebviewPanel('pockethiveScenarioPreview', `Scenario: ${target}`, vscode.ViewColumn.Beside, { enableFindWidget: true });
    panel.webview.html = renderScenarioPreviewHtml(scenario);
  });
}

export async function openScenarioFile(target: ScenarioFileTarget): Promise<void> {
  if (!target?.scenarioId) { vscode.window.showErrorMessage('PocketHive: scenario id is required.'); return; }
  if (target.kind === 'scenario') { await openScenarioRaw(target.scenarioId); return; }
  if (!target.path) { vscode.window.showErrorMessage('PocketHive: file path is required.'); return; }
  await withMcp(async () => {
    const result = target.kind === 'schema'
      ? await McpTools.scenarioSchemaRead(target.scenarioId, target.path!)
      : await McpTools.scenarioTemplateRead(target.scenarioId, target.path!);
    await openPreviewDocument(`${target.scenarioId}/${target.path}`, result.content, target.kind === 'schema' ? 'json' : 'yaml');
  });
}

export async function showEntry(entry: unknown, title?: string): Promise<void> {
  await openJsonPreview(title ?? 'Entry', entry);
}

// ── Helpers ───────────────────────────────────────────────────────────────────

async function withMcp<T>(action: () => Promise<T>): Promise<T | undefined> {
  const outputChannel = getOutputChannel();
  try {
    return await action();
  } catch (error) {
    const message = formatError(error);
    outputChannel.appendLine(`[${new Date().toISOString()}] ERROR ${message}`);
    outputChannel.show(true);
    vscode.window.showErrorMessage(`PocketHive: ${message}`);
    return undefined;
  }
}

function resolveScenarioId(arg: unknown): string | undefined {
  if (!arg) return undefined;
  if (typeof arg === 'string') return arg;
  if (typeof arg === 'object') {
    if ('id' in arg && typeof (arg as { id?: unknown }).id === 'string') return (arg as { id: string }).id;
    if ('scenario' in arg) {
      const s = (arg as { scenario?: unknown }).scenario;
      if (s && typeof s === 'object' && 'id' in s) { const id = (s as { id?: unknown }).id; return typeof id === 'string' ? id : undefined; }
    }
  }
  return undefined;
}

function resolveSwarmId(arg: unknown): string | undefined {
  if (!arg) return undefined;
  if (typeof arg === 'string') return arg;
  if (typeof arg === 'object') {
    if ('id' in arg && typeof (arg as { id?: unknown }).id === 'string') return (arg as { id: string }).id;
    if ('swarm' in arg) {
      const s = (arg as { swarm?: unknown }).swarm;
      if (s && typeof s === 'object' && 'id' in s) { const id = (s as { id?: unknown }).id; return typeof id === 'string' ? id : undefined; }
    }
  }
  return undefined;
}
