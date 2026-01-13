import * as vscode from 'vscode';
import { randomUUID } from 'crypto';

import { requestJson, requestText } from './api';
import {
  getActiveHiveUrl,
  getHiveUrls,
  normalizeHiveUrl,
  resolveHiveBaseUrl,
  resolveServiceConfig,
  updateActiveHiveUrl,
  updateHiveUrls
} from './config';
import { formatError } from './format';
import { scenarioUri } from './fs/scenarioFileSystemProvider';
import { getOutputChannel } from './output';
import { pickScenarioId, pickSwarmId } from './pickers';
import { openJsonPreview, openPreviewDocument } from './preview';
import { renderScenarioPreviewHtml } from './scenarioPreview';
import { ScenarioDetail, ScenarioSummary, SwarmSummary } from './types';

type ScenarioFileTarget = {
  scenarioId: string;
  kind: 'scenario' | 'schema' | 'http-template';
  path?: string;
};

export async function addHiveUrl(): Promise<void> {
  const next = await vscode.window.showInputBox({
    prompt: 'Hive base URL (example: http://localhost:8088)',
    value: getActiveHiveUrl() ?? undefined,
    ignoreFocusOut: true,
    validateInput: (value) => (value.trim().length === 0 ? 'Hive URL is required.' : null)
  });

  if (!next) {
    return;
  }

  const normalized = normalizeHiveUrl(next);
  if (!normalized) {
    vscode.window.showErrorMessage('PocketHive: Hive URL is invalid.');
    return;
  }

  const existing = getHiveUrls();
  const updated = [normalized, ...existing.filter((url) => url !== normalized)];
  await updateHiveUrls(updated);
  await updateActiveHiveUrl(normalized);
  vscode.window.showInformationMessage(`PocketHive: Hive URL added (${normalized}).`);
}

export async function setActiveHiveUrl(target: unknown): Promise<void> {
  const url = resolveHiveUrl(target);
  if (!url) {
    vscode.window.showErrorMessage('PocketHive: Hive URL is required.');
    return;
  }
  await updateActiveHiveUrl(url);
  vscode.window.showInformationMessage(`PocketHive: active Hive URL set to ${url}.`);
}

export async function removeHiveUrl(target: unknown): Promise<void> {
  const url = resolveHiveUrl(target);
  if (!url) {
    vscode.window.showErrorMessage('PocketHive: Hive URL is required.');
    return;
  }
  const choice = await vscode.window.showWarningMessage(
    `Remove Hive URL '${url}'?`,
    { modal: true },
    'Remove'
  );
  if (choice !== 'Remove') {
    return;
  }
  const existing = getHiveUrls();
  const updated = existing.filter((entry) => entry !== url);
  await updateHiveUrls(updated);
  const active = getActiveHiveUrl();
  if (active === url) {
    const next = updated[0] ?? null;
    await updateActiveHiveUrl(next);
    if (next) {
      vscode.window.showInformationMessage(`PocketHive: active Hive URL set to ${next}.`);
    }
  }
}

export async function listSwarms(): Promise<void> {
  await withService('orchestratorUrl', async (baseUrl, authToken) => {
    const swarms = await requestJson<SwarmSummary[]>(baseUrl, authToken, 'GET', '/api/swarms');
    const outputChannel = getOutputChannel();
    outputChannel.appendLine(`[${new Date().toISOString()}] GET /api/swarms`);
    outputChannel.appendLine(JSON.stringify(swarms, null, 2));
    outputChannel.show(true);
    vscode.window.showInformationMessage(`PocketHive: ${swarms.length} swarms listed.`);
  });
}

export async function runSwarmCommand(action: 'start' | 'stop' | 'remove', swarmTarget?: unknown): Promise<void> {
  await withService('orchestratorUrl', async (baseUrl, authToken) => {
    const target = resolveSwarmId(swarmTarget) ?? (await pickSwarmId(baseUrl, authToken));
    if (!target) {
      return;
    }

    if (action === 'remove') {
      const choice = await vscode.window.showWarningMessage(
        `Remove swarm '${target}'? This will delete queues and stop the controller.`,
        { modal: true },
        'Remove'
      );
      if (choice !== 'Remove') {
        return;
      }
    }

    const body = { idempotencyKey: randomUUID() };
    const response = await requestJson<Record<string, unknown>>(
      baseUrl,
      authToken,
      'POST',
      `/api/swarms/${encodeURIComponent(target)}/${action}`,
      body
    );

    const outputChannel = getOutputChannel();
    outputChannel.appendLine(`[${new Date().toISOString()}] POST /api/swarms/${target}/${action}`);
    outputChannel.appendLine(JSON.stringify(response, null, 2));
    outputChannel.show(true);
    vscode.window.showInformationMessage(`PocketHive: ${action} accepted for swarm '${target}'.`);
  });
}

export async function runAllSwarms(action: 'start' | 'stop'): Promise<void> {
  await withService('orchestratorUrl', async (baseUrl, authToken) => {
    const swarms = await requestJson<SwarmSummary[]>(baseUrl, authToken, 'GET', '/api/swarms');
    if (!swarms.length) {
      vscode.window.showInformationMessage('PocketHive: No swarms found.');
      return;
    }
    const outputChannel = getOutputChannel();
    outputChannel.appendLine(`[${new Date().toISOString()}] ${action.toUpperCase()} all swarms (${swarms.length})`);
    const failures: string[] = [];
    for (const swarm of swarms) {
      try {
        await requestJson<Record<string, unknown>>(
          baseUrl,
          authToken,
          'POST',
          `/api/swarms/${encodeURIComponent(swarm.id)}/${action}`,
          { idempotencyKey: randomUUID() }
        );
      } catch (error) {
        failures.push(`${swarm.id}: ${formatError(error)}`);
      }
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
  if ('error' in base) {
    vscode.window.showErrorMessage(base.error);
    return;
  }
  await vscode.env.openExternal(vscode.Uri.parse(base.baseUrl));
}

export async function openSwarmDetails(swarmId?: string): Promise<void> {
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

    await openJsonPreview(`Swarm ${target}`, swarm);
  });
}

export async function openScenarioRaw(scenarioId?: string | ScenarioSummary | { scenario?: ScenarioSummary }): Promise<void> {
  await withService('scenarioManagerUrl', async (baseUrl, authToken) => {
    const target = resolveScenarioId(scenarioId) ?? (await pickScenarioId(baseUrl, authToken));
    if (!target) {
      return;
    }

    const outputChannel = getOutputChannel();
    outputChannel.appendLine(`[${new Date().toISOString()}] OPEN scenario ${target} via ${baseUrl}/scenarios/{id}/raw`);
    const uri = scenarioUri(target);
    try {
      await vscode.commands.executeCommand('vscode.openWith', uri, 'pockethive.scenarioEditor');
      return;
    } catch (error) {
      outputChannel.appendLine(`[${new Date().toISOString()}] WARN scenario editor failed: ${formatError(error)}`);
    }

    const document = await vscode.workspace.openTextDocument(uri);
    if (document.languageId === 'plaintext') {
      await vscode.languages.setTextDocumentLanguage(document, 'yaml');
    }
    await vscode.window.showTextDocument(document, { preview: true });
  });
}

export async function previewScenario(scenarioId?: string | ScenarioSummary | { scenario?: ScenarioSummary }): Promise<void> {
  await withService('scenarioManagerUrl', async (baseUrl, authToken) => {
    const target = resolveScenarioId(scenarioId) ?? (await pickScenarioId(baseUrl, authToken));
    if (!target) {
      return;
    }

    const outputChannel = getOutputChannel();
    outputChannel.appendLine(`[${new Date().toISOString()}] PREVIEW scenario ${target} via ${baseUrl}/scenarios/{id}`);
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

export async function openScenarioFile(target: ScenarioFileTarget): Promise<void> {
  if (!target?.scenarioId) {
    vscode.window.showErrorMessage('PocketHive: scenario id is required.');
    return;
  }

  if (target.kind === 'scenario') {
    await openScenarioRaw(target.scenarioId);
    return;
  }

  if (!target.path) {
    vscode.window.showErrorMessage('PocketHive: file path is required.');
    return;
  }
  const filePath = target.path;

  await withService('scenarioManagerUrl', async (baseUrl, authToken) => {
    const outputChannel = getOutputChannel();
    const endpoint =
      target.kind === 'schema'
        ? `/scenarios/${encodeURIComponent(target.scenarioId)}/schema?path=${encodeURIComponent(filePath)}`
        : `/scenarios/${encodeURIComponent(target.scenarioId)}/http-template?path=${encodeURIComponent(filePath)}`;
    outputChannel.appendLine(`[${new Date().toISOString()}] OPEN scenario file ${endpoint}`);
    const text = await requestText(baseUrl, authToken, 'GET', endpoint);
    const title = `${target.scenarioId}/${filePath}`;
    const language = target.kind === 'schema' ? 'json' : 'yaml';
    await openPreviewDocument(title, text, language);
  });
}

export async function showEntry(entry: unknown, title?: string): Promise<void> {
  await openJsonPreview(title ?? 'Entry', entry);
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

  const outputChannel = getOutputChannel();
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

function resolveSwarmId(arg: unknown): string | undefined {
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
    if ('swarm' in arg) {
      const swarm = (arg as { swarm?: unknown }).swarm;
      if (swarm && typeof swarm === 'object' && 'id' in swarm) {
        const id = (swarm as { id?: unknown }).id;
        return typeof id === 'string' ? id : undefined;
      }
    }
  }
  return undefined;
}

function resolveHiveUrl(arg: unknown): string | undefined {
  if (!arg) {
    return undefined;
  }
  if (typeof arg === 'string') {
    return arg;
  }
  if (typeof arg === 'object') {
    if ('url' in arg && typeof (arg as { url?: unknown }).url === 'string') {
      return (arg as { url: string }).url;
    }
  }
  return undefined;
}
