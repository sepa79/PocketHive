import * as vscode from 'vscode';

import { DEFAULT_ORCHESTRATOR_URL, DEFAULT_SCENARIO_MANAGER_URL } from './constants';

type ConfigKey = 'orchestratorUrl' | 'scenarioManagerUrl' | 'authToken';

type ServiceKey = 'orchestratorUrl' | 'scenarioManagerUrl';

type ServiceConfig = { baseUrl: string; authToken?: string };

type ServiceConfigError = { error: string };

export function resolveServiceConfig(key: ServiceKey): ServiceConfig | ServiceConfigError {
  const raw = getConfigValue(key);
  if (!raw || raw.trim().length === 0) {
    return { error: `PocketHive: pockethive.${key} is not set.` };
  }

  const baseUrl = raw.trim().replace(/\/+$/, '');
  const authToken = getConfigValue('authToken')?.trim();
  return { baseUrl, authToken: authToken && authToken.length > 0 ? authToken : undefined };
}

export function getConfigValue(key: ConfigKey): string | undefined {
  const config = vscode.workspace.getConfiguration('pockethive');
  if (key === 'orchestratorUrl') {
    return config.get<string>(key, DEFAULT_ORCHESTRATOR_URL);
  }
  if (key === 'scenarioManagerUrl') {
    return config.get<string>(key, DEFAULT_SCENARIO_MANAGER_URL);
  }
  return config.get<string>(key);
}
