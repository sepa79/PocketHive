import { ConnectionContractError } from '../connection/contracts';

export const SCENARIO_ASSETS = Object.freeze({
  RAW: 'RAW',
  SCHEMA: 'SCHEMA',
  TEMPLATE: 'TEMPLATE',
} as const);

export type ScenarioAsset = typeof SCENARIO_ASSETS[keyof typeof SCENARIO_ASSETS];

const TOOL_BY_ASSET: Readonly<Record<ScenarioAsset, string>> = Object.freeze({
  [SCENARIO_ASSETS.RAW]: 'scenario_raw_read',
  [SCENARIO_ASSETS.SCHEMA]: 'scenario_schema_read',
  [SCENARIO_ASSETS.TEMPLATE]: 'scenario_template_read',
});

export function scenarioReadToolCall(
  asset: ScenarioAsset,
  scenarioId: string,
  path?: string,
): { readonly name: string; readonly arguments: Record<string, unknown> } {
  const exactScenarioId = required(scenarioId, 'SCENARIO_ID_REQUIRED');
  if (asset === SCENARIO_ASSETS.RAW) {
    if (path !== undefined) throw contract('SCENARIO_ASSET_PATH_FORBIDDEN');
    return { name: TOOL_BY_ASSET[asset], arguments: { scenarioId: exactScenarioId } };
  }
  return {
    name: TOOL_BY_ASSET[asset],
    arguments: { scenarioId: exactScenarioId, path: required(path, 'SCENARIO_ASSET_PATH_REQUIRED') },
  };
}

export function scenarioReadText(value: unknown): string {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) {
    const texts = value.flatMap(item => {
      if (!item || typeof item !== 'object' || Array.isArray(item)) return [];
      const record = item as Record<string, unknown>;
      return record.type === 'text' && typeof record.text === 'string' ? [record.text] : [];
    });
    if (texts.length > 0) return texts.join('\n\n');
  }
  throw contract('SCENARIO_PREVIEW_TEXT_INVALID');
}

export function previewLanguageForPath(path?: string): string {
  const target = (path ?? 'scenario.yaml').trim().toLocaleLowerCase();
  if (target.endsWith('.yaml') || target.endsWith('.yml')) return 'yaml';
  if (target.endsWith('.json')) return 'json';
  if (target.endsWith('.sql')) return 'sql';
  if (target.endsWith('.sh')) return 'shellscript';
  if (target.endsWith('.md')) return 'markdown';
  return 'plaintext';
}

function required(value: string | undefined, code: string): string {
  const result = value?.trim();
  if (!result) throw contract(code);
  return result;
}

function contract(code: string): ConnectionContractError {
  return new ConnectionContractError(code, code);
}
