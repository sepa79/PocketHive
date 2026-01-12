import * as vscode from 'vscode';
import { randomUUID } from 'crypto';

import { requestJson } from './api';
import { getConfigValue, resolveServiceConfig } from './config';
import { formatError } from './format';
import { scenarioUri } from './fs/scenarioFileSystemProvider';
import { getOutputChannel } from './output';
import { pickScenarioId, pickSwarmId } from './pickers';
import { renderScenarioPreviewHtml } from './scenarioPreview';
import { ScenarioDetail, ScenarioSummary, SwarmSummary } from './types';
import { openJsonDocument } from './ui';

export async function configureOrchestratorUrl(): Promise<void> {
  const current = getConfigValue('orchestratorUrl');
  const next = await vscode.window.showInputBox({
    prompt: 'Orchestrator base URL (example: http://localhost:8088/orchestrator)',
    value: current,
    ignoreFocusOut: true,
    validateInput: (value) => (value.trim().length === 0 ? 'Orchestrator URL is required.' : null)
  });

  if (!next) {
    return;
  }

  await vscode.workspace.getConfiguration('pockethive').update('orchestratorUrl', next.trim(), true);
  vscode.window.showInformationMessage('PocketHive: Orchestrator URL updated.');
}

export async function configureScenarioManagerUrl(): Promise<void> {
  const current = getConfigValue('scenarioManagerUrl');
  const next = await vscode.window.showInputBox({
    prompt: 'Scenario Manager base URL (example: http://localhost:8088/scenario-manager)',
    value: current,
    ignoreFocusOut: true,
    validateInput: (value) => (value.trim().length === 0 ? 'Scenario Manager URL is required.' : null)
  });

  if (!next) {
    return;
  }

  await vscode.workspace.getConfiguration('pockethive').update('scenarioManagerUrl', next.trim(), true);
  vscode.window.showInformationMessage('PocketHive: Scenario Manager URL updated.');
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

export async function runSwarmCommand(action: 'start' | 'stop' | 'remove', swarmId?: string): Promise<void> {
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

    const outputChannel = getOutputChannel();
    outputChannel.appendLine(`[${new Date().toISOString()}] POST /api/swarms/${target}/${action}`);
    outputChannel.appendLine(JSON.stringify(response, null, 2));
    outputChannel.show(true);
    vscode.window.showInformationMessage(`PocketHive: ${action} accepted for swarm '${target}'.`);
  });
}

export async function openOrchestrator(): Promise<void> {
  await withService('orchestratorUrl', async (baseUrl) => {
    await vscode.env.openExternal(vscode.Uri.parse(baseUrl));
  });
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

    await openJsonDocument(`Swarm ${target}`, swarm);
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
    const document = await vscode.workspace.openTextDocument(uri);
    if (document.languageId === 'plaintext') {
      await vscode.languages.setTextDocumentLanguage(document, 'yaml');
    }
    await vscode.window.showTextDocument(document, { preview: false });
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

export async function showEntry(entry: unknown, title?: string): Promise<void> {
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
