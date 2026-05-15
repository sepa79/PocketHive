import * as vscode from 'vscode';
import type { Environment } from './mcp/manager';

// ── Read helpers ──────────────────────────────────────────────────────────────

export function getEnvironments(): Environment[] {
  return vscode.workspace.getConfiguration('pockethive').get<Environment[]>('environments') ?? [];
}

export function getActiveEnvironmentName(): string {
  return vscode.workspace.getConfiguration('pockethive').get<string>('activeEnvironment') ?? '';
}

export function getActiveEnvironment(): Environment | undefined {
  const envs = getEnvironments();
  const name = getActiveEnvironmentName();
  return envs.find(e => e.name === name) ?? envs[0];
}

export function getBundlesFolders(): string[] {
  return vscode.workspace.getConfiguration('pockethive').get<string[]>('bundlesFolders') ?? [];
}

export function getActiveBundlesFolder(): string {
  return vscode.workspace.getConfiguration('pockethive').get<string>('activeBundlesFolder') ?? '';
}

export function getPockethiveRoot(): string {
  return vscode.workspace.getConfiguration('pockethive').get<string>('pockethiveRoot') ?? '';
}

// ── Write helpers ─────────────────────────────────────────────────────────────

export async function setActiveEnvironment(name: string): Promise<void> {
  await vscode.workspace.getConfiguration('pockethive').update(
    'activeEnvironment', name, vscode.ConfigurationTarget.Global
  );
}

export async function addEnvironment(env: Environment): Promise<void> {
  const envs = getEnvironments();
  const updated = [...envs.filter(e => e.name !== env.name), env];
  await vscode.workspace.getConfiguration('pockethive').update(
    'environments', updated, vscode.ConfigurationTarget.Global
  );
}

export async function removeEnvironment(name: string): Promise<void> {
  const envs = getEnvironments().filter(e => e.name !== name);
  await vscode.workspace.getConfiguration('pockethive').update(
    'environments', envs, vscode.ConfigurationTarget.Global
  );
}

export async function addBundlesFolder(folderPath: string): Promise<void> {
  const folders = getBundlesFolders();
  if (!folders.includes(folderPath)) {
    await vscode.workspace.getConfiguration('pockethive').update(
      'bundlesFolders', [...folders, folderPath], vscode.ConfigurationTarget.Global
    );
  }
}

export async function setActiveBundlesFolder(folderPath: string): Promise<void> {
  await vscode.workspace.getConfiguration('pockethive').update(
    'activeBundlesFolder', folderPath, vscode.ConfigurationTarget.Global
  );
}

// ── Legacy migration ──────────────────────────────────────────────────────────
// Migrates pockethive.hiveUrls → pockethive.environments on first activation.

export async function migrateSettingsIfNeeded(): Promise<void> {
  const cfg = vscode.workspace.getConfiguration('pockethive');
  const legacyUrls = cfg.get<string[]>('hiveUrls') ?? [];
  const environments = cfg.get<Environment[]>('environments') ?? [];

  if (legacyUrls.length === 0 || environments.length > 0) return;

  const migrated: Environment[] = legacyUrls.map((url, i) => ({
    name: i === 0 ? 'local' : `env-${i}`,
    baseUrl: url,
    rabbitUser: 'guest',
    tcpMockUrl: '',
    wiremockUrl: '',
  }));

  const activeUrl = cfg.get<string>('activeHiveUrl') ?? '';
  const activeIdx = legacyUrls.indexOf(activeUrl);
  const activeName = activeIdx >= 0 ? migrated[activeIdx].name : (migrated[0]?.name ?? '');

  await cfg.update('environments', migrated, vscode.ConfigurationTarget.Global);
  await cfg.update('activeEnvironment', activeName, vscode.ConfigurationTarget.Global);
}

// ── Legacy compat (kept for existing code that still calls these) ─────────────

export function resolveHiveBaseUrl(): { baseUrl: string } | { error: string } {
  const env = getActiveEnvironment();
  if (!env) return { error: 'PocketHive: no environment configured. Add one in Settings.' };
  return { baseUrl: env.baseUrl };
}

export function resolveServiceConfig(key: 'orchestratorUrl' | 'scenarioManagerUrl') {
  const base = resolveHiveBaseUrl();
  if ('error' in base) return base;
  const suffix = key === 'orchestratorUrl' ? '/orchestrator' : '/scenario-manager';
  return { baseUrl: base.baseUrl + suffix, authToken: getAuthToken() };
}

export function getAuthToken(): string | undefined {
  return getActiveEnvironment()?.authToken?.trim() || undefined;
}
