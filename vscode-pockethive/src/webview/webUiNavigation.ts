import { ConnectionContractError } from '../connection/contracts';

export const POCKETHIVE_UI_SERVICE_ID = 'pockethive-ui';

export const WEB_UI_DESTINATIONS = Object.freeze({
  BUZZ: 'BUZZ',
  SWARM: 'SWARM',
  JOURNAL_RUN: 'JOURNAL_RUN',
} as const);

export type WebUiDestination =
  | { readonly destination: typeof WEB_UI_DESTINATIONS.BUZZ }
  | { readonly destination: typeof WEB_UI_DESTINATIONS.SWARM; readonly swarmId: string }
  | {
      readonly destination: typeof WEB_UI_DESTINATIONS.JOURNAL_RUN;
      readonly swarmId: string;
      readonly runId: string;
    };

export function resolvePocketHiveWebUiUrl(health: unknown, target: WebUiDestination): string {
  const services = environmentServices(health);
  const matches = services.filter(service => service.id === POCKETHIVE_UI_SERVICE_ID);
  if (matches.length === 0) {
    throw new ConnectionContractError('WEB_UI_ENDPOINT_MISSING', POCKETHIVE_UI_SERVICE_ID);
  }
  if (matches.length !== 1) {
    throw new ConnectionContractError('WEB_UI_ENDPOINT_AMBIGUOUS', POCKETHIVE_UI_SERVICE_ID);
  }
  const base = validatedWebUiBase(matches[0].endpoint);
  switch (target.destination) {
    case WEB_UI_DESTINATIONS.BUZZ:
      return new URL('v2/buzz', base).toString();
    case WEB_UI_DESTINATIONS.SWARM:
      return new URL(`v2/hive/${encoded(target.swarmId, 'WEB_UI_SWARM_REQUIRED')}/view`, base).toString();
    case WEB_UI_DESTINATIONS.JOURNAL_RUN: {
      const url = new URL(`v2/journal/swarms/${encoded(target.swarmId, 'WEB_UI_SWARM_REQUIRED')}`, base);
      url.searchParams.set('runId', required(target.runId, 'WEB_UI_RUN_REQUIRED'));
      return url.toString();
    }
  }
}

function environmentServices(value: unknown): Array<{ id: string; endpoint: string }> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return [];
  const services = (value as Record<string, unknown>).services;
  if (!Array.isArray(services)) return [];
  const result: Array<{ id: string; endpoint: string }> = [];
  for (const item of services.slice(0, 50)) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue;
    const record = item as Record<string, unknown>;
    if (typeof record.id === 'string' && typeof record.endpoint === 'string') {
      result.push({ id: record.id.trim(), endpoint: record.endpoint.trim() });
    }
  }
  return result;
}

function validatedWebUiBase(endpoint: string): URL {
  let result: URL;
  try {
    result = new URL(endpoint);
  } catch {
    throw new ConnectionContractError('WEB_UI_ENDPOINT_INVALID', endpoint);
  }
  if (!['http:', 'https:'].includes(result.protocol)
      || result.username || result.password || result.search || result.hash) {
    throw new ConnectionContractError('WEB_UI_ENDPOINT_INVALID', endpoint);
  }
  if (!result.pathname.endsWith('/')) result.pathname += '/';
  return result;
}

function encoded(value: string, code: string): string {
  return encodeURIComponent(required(value, code));
}

function required(value: string, code: string): string {
  const result = value.trim();
  if (!result) throw new ConnectionContractError(code, code);
  return result;
}
