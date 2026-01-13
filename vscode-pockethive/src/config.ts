import * as vscode from 'vscode';

type ServiceKey = 'orchestratorUrl' | 'scenarioManagerUrl';

type ServiceConfig = { baseUrl: string; authToken?: string };

type ServiceConfigError = { error: string };

const HIVE_URLS_KEY = 'hiveUrls';
const ACTIVE_HIVE_URL_KEY = 'activeHiveUrl';
const AUTH_TOKEN_KEY = 'authToken';

export function resolveServiceConfig(key: ServiceKey): ServiceConfig | ServiceConfigError {
  const base = resolveHiveBaseUrl();
  if ('error' in base) {
    return base;
  }
  const authToken = getAuthToken();
  const suffix = key === 'orchestratorUrl' ? '/orchestrator' : '/scenario-manager';
  return { baseUrl: base.baseUrl + suffix, authToken };
}

export function resolveHiveBaseUrl(): { baseUrl: string } | ServiceConfigError {
  const active = getActiveHiveUrl();
  if (!active) {
    return { error: 'PocketHive: no Hive URL configured. Add one in Settings.' };
  }
  const normalized = normalizeHiveUrl(active);
  if (!normalized) {
    return { error: 'PocketHive: active Hive URL is invalid.' };
  }
  return { baseUrl: normalized };
}

export function normalizeHiveUrl(value: string): string | null {
  if (!value) {
    return null;
  }
  const trimmed = value.trim().replace(/\/+$/, '');
  if (!trimmed) {
    return null;
  }
  if (trimmed.endsWith('/orchestrator')) {
    return trimmed.slice(0, -'/orchestrator'.length) || null;
  }
  if (trimmed.endsWith('/scenario-manager')) {
    return trimmed.slice(0, -'/scenario-manager'.length) || null;
  }
  return trimmed;
}

export function getHiveUrls(): string[] {
  const config = vscode.workspace.getConfiguration('pockethive');
  const urls = config.get<string[]>(HIVE_URLS_KEY);
  return Array.isArray(urls) ? urls : [];
}

export function getActiveHiveUrl(): string | null {
  const config = vscode.workspace.getConfiguration('pockethive');
  const value = config.get<string>(ACTIVE_HIVE_URL_KEY);
  if (!value || value.trim().length === 0) {
    return null;
  }
  return value.trim();
}

export async function updateHiveUrls(urls: string[]): Promise<void> {
  const config = vscode.workspace.getConfiguration('pockethive');
  await config.update(HIVE_URLS_KEY, urls, true);
}

export async function updateActiveHiveUrl(url: string | null): Promise<void> {
  const config = vscode.workspace.getConfiguration('pockethive');
  await config.update(ACTIVE_HIVE_URL_KEY, url, true);
}

export function getAuthToken(): string | undefined {
  const config = vscode.workspace.getConfiguration('pockethive');
  const value = config.get<string>(AUTH_TOKEN_KEY);
  return value && value.trim().length > 0 ? value.trim() : undefined;
}
