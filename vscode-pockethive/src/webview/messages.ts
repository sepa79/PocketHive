/**
 * Responsibility: Define and strictly decode the companion webview-to-extension command contract.
 * Must not: Execute commands, own UI state, or infer missing command fields.
 * Contract: vscode-pockethive/README.md and docs/mcp/README.md.
 */
import { ConnectionContractError, EndpointSecurityMode } from '../connection/contracts';
import { SWARM_OPERATIONS, SwarmOperation } from '../operations/swarmOperations';
import { WEB_UI_DESTINATIONS, WebUiDestination } from './webUiNavigation';

export type CompanionTab = 'Hive' | 'Buzz' | 'Journal' | 'Scenarios' | 'Debug';
export type ScenarioSection = 'OVERVIEW' | 'FILES' | 'INPUTS';
export type WorkerDebugAction = 'Logs' | 'Inspect';
export type SwarmNetworkMode = 'DIRECT' | 'PROXIED';

export type WebviewCommand =
  | { readonly type: 'ready' }
  | { readonly type: 'addEnvironment' }
  | { readonly type: 'connect'; readonly displayName: string; readonly mcpUrl: string; readonly endpointSecurityMode: EndpointSecurityMode }
  | { readonly type: 'retryTest' }
  | { readonly type: 'signInAgain' }
  | { readonly type: 'cancelConnection' }
  | { readonly type: 'saveOpen' }
  | { readonly type: 'openEnvironment'; readonly profileId: string }
  | { readonly type: 'removeEnvironment'; readonly profileId: string }
  | { readonly type: 'backToEnvironments' }
  | { readonly type: 'reauthorizeEnvironment' }
  | { readonly type: 'signOut' }
  | { readonly type: 'selectTab'; readonly tab: CompanionTab }
  | { readonly type: 'refresh' }
  | { readonly type: 'openCreateSwarm' }
  | { readonly type: 'cancelCreateSwarm' }
  | { readonly type: 'selectCreateSwarmTemplate'; readonly templateId: string; readonly scenarioId: string }
  | {
      readonly type: 'submitCreateSwarm';
      readonly swarmId: string;
      readonly templateId: string;
      readonly scenarioId: string;
      readonly autoPullImages: boolean;
      readonly sutId: string | null;
      readonly variablesProfileId: string | null;
      readonly networkMode: SwarmNetworkMode;
      readonly networkProfileId: string | null;
    }
  | { readonly type: 'selectJournalSwarm'; readonly swarmId: string }
  | { readonly type: 'loadSwarmHistory'; readonly swarmId: string }
  | { readonly type: 'openSwarmDetails'; readonly swarmId: string }
  | ({ readonly type: 'openWebUi' } & WebUiDestination)
  | { readonly type: 'openJournalRun'; readonly swarmId: string; readonly runId: string }
  | { readonly type: 'openEventDetails'; readonly detailId: string }
  | { readonly type: 'runSwarmOperation'; readonly swarmId: string; readonly action: SwarmOperation }
  | { readonly type: 'runSwarmBatchOperation'; readonly action: Exclude<SwarmOperation, 'REMOVE'> }
  | { readonly type: 'openDebugForSwarm'; readonly swarmId: string }
  | { readonly type: 'openDebugForWorker'; readonly swarmId: string; readonly instance: string; readonly action: WorkerDebugAction }
  | { readonly type: 'selectDebugSwarm'; readonly swarmId: string }
  | { readonly type: 'selectDebugWorker'; readonly runtimeId: string }
  | { readonly type: 'runDebug'; readonly action: string; readonly tailLines?: number }
  | { readonly type: 'openScenarioDetails'; readonly scenarioId: string }
  | { readonly type: 'openScenarioRaw'; readonly scenarioId: string }
  | { readonly type: 'openScenarioSchema'; readonly scenarioId: string }
  | { readonly type: 'openScenarioTemplate'; readonly scenarioId: string }
  | { readonly type: 'selectScenarioSection'; readonly scenarioId: string; readonly bundleKey: string; readonly section: ScenarioSection }
  | { readonly type: 'openScenarioBundleFile'; readonly bundleKey: string; readonly path: string }
  | { readonly type: 'validateCommittedBundle' }
  | { readonly type: 'validateRepositoryBundle'; readonly candidateId: string }
  | { readonly type: 'openRepositoryBundleFile'; readonly candidateId: string; readonly path: string }
  | { readonly type: 'deployRepositoryBundle'; readonly candidateId: string }
  | { readonly type: 'replaceRepositoryBundle'; readonly candidateId: string }
  | {
      readonly type: 'openRepositoryRename';
      readonly candidateId: string;
      readonly scenarioId: string;
      readonly scenarioName: string;
    }
  | { readonly type: 'discardPendingBundle' }
  | { readonly type: 'publishCommittedBundle'; readonly mode: 'CREATE' | 'REPLACE'; readonly scenarioId?: string }
  | { readonly type: 'reconcilePublicationAttempt'; readonly attemptId: string };

export function decodeWebviewCommand(value: unknown): WebviewCommand {
  const object = record(value);
  const type = string(object, 'type');
  switch (type) {
    case 'ready':
    case 'addEnvironment':
    case 'retryTest':
    case 'signInAgain':
    case 'cancelConnection':
    case 'saveOpen':
    case 'backToEnvironments':
    case 'reauthorizeEnvironment':
    case 'signOut':
    case 'refresh':
    case 'openCreateSwarm':
    case 'cancelCreateSwarm':
    case 'validateCommittedBundle':
    case 'discardPendingBundle':
      exact(object, ['type']);
      return { type };
    case 'reconcilePublicationAttempt':
      exact(object, ['type', 'attemptId']);
      return { type, attemptId: string(object, 'attemptId') };
    case 'connect': {
      exact(object, ['type', 'displayName', 'mcpUrl', 'endpointSecurityMode']);
      const mode = string(object, 'endpointSecurityMode');
      if (mode !== 'REMOTE_HTTPS' && mode !== 'LOCAL_LOOPBACK_HTTP') invalid();
      return {
        type,
        displayName: string(object, 'displayName'),
        mcpUrl: string(object, 'mcpUrl'),
        endpointSecurityMode: mode,
      };
    }
    case 'openEnvironment':
    case 'removeEnvironment':
      exact(object, ['type', 'profileId']);
      return { type, profileId: string(object, 'profileId') };
    case 'selectTab': {
      exact(object, ['type', 'tab']);
      const tab = string(object, 'tab');
      if (!['Hive', 'Buzz', 'Journal', 'Scenarios', 'Debug'].includes(tab)) invalid();
      return { type, tab: tab as CompanionTab };
    }
    case 'selectJournalSwarm':
    case 'loadSwarmHistory':
    case 'openSwarmDetails':
    case 'openDebugForSwarm':
    case 'selectDebugSwarm':
      exact(object, ['type', 'swarmId']);
      return { type, swarmId: string(object, 'swarmId') };
    case 'selectCreateSwarmTemplate':
      exact(object, ['type', 'templateId', 'scenarioId']);
      return {
        type,
        templateId: string(object, 'templateId'),
        scenarioId: string(object, 'scenarioId'),
      };
    case 'submitCreateSwarm': {
      exact(object, [
        'type', 'swarmId', 'templateId', 'scenarioId', 'autoPullImages', 'sutId',
        'variablesProfileId', 'networkMode', 'networkProfileId',
      ]);
      const networkMode = string(object, 'networkMode');
      if (networkMode !== 'DIRECT' && networkMode !== 'PROXIED') invalid();
      return {
        type,
        swarmId: string(object, 'swarmId'),
        templateId: string(object, 'templateId'),
        scenarioId: string(object, 'scenarioId'),
        autoPullImages: boolean(object, 'autoPullImages'),
        sutId: nullableString(object, 'sutId'),
        variablesProfileId: nullableString(object, 'variablesProfileId'),
        networkMode,
        networkProfileId: nullableString(object, 'networkProfileId'),
      };
    }
    case 'openJournalRun':
      exact(object, ['type', 'swarmId', 'runId']);
      return { type, swarmId: string(object, 'swarmId'), runId: string(object, 'runId') };
    case 'openEventDetails':
      exact(object, ['type', 'detailId']);
      return { type, detailId: string(object, 'detailId') };
    case 'openDebugForWorker': {
      exact(object, ['type', 'swarmId', 'instance', 'action']);
      const action = string(object, 'action');
      if (action !== 'Logs' && action !== 'Inspect') invalid();
      return {
        type,
        swarmId: string(object, 'swarmId'),
        instance: string(object, 'instance'),
        action,
      };
    }
    case 'openWebUi': {
      const destination = string(object, 'destination');
      if (destination === WEB_UI_DESTINATIONS.BUZZ) {
        exact(object, ['type', 'destination']);
        return { type, destination };
      }
      if (destination === WEB_UI_DESTINATIONS.SWARM) {
        exact(object, ['type', 'destination', 'swarmId']);
        return { type, destination, swarmId: string(object, 'swarmId') };
      }
      if (destination === WEB_UI_DESTINATIONS.JOURNAL_RUN) {
        exact(object, ['type', 'destination', 'swarmId', 'runId']);
        return {
          type,
          destination,
          swarmId: string(object, 'swarmId'),
          runId: string(object, 'runId'),
        };
      }
      return invalid();
    }
    case 'runSwarmOperation': {
      exact(object, ['type', 'swarmId', 'action']);
      const action = string(object, 'action');
      if (!Object.values(SWARM_OPERATIONS).includes(action as SwarmOperation)) invalid();
      return { type, swarmId: string(object, 'swarmId'), action: action as SwarmOperation };
    }
    case 'runSwarmBatchOperation': {
      exact(object, ['type', 'action']);
      const action = string(object, 'action');
      if (action !== SWARM_OPERATIONS.START && action !== SWARM_OPERATIONS.STOP) invalid();
      return { type, action: action as Exclude<SwarmOperation, 'REMOVE'> };
    }
    case 'selectDebugWorker':
      exact(object, ['type', 'runtimeId']);
      return { type, runtimeId: string(object, 'runtimeId') };
    case 'runDebug': {
      const keys = object.tailLines === undefined ? ['type', 'action'] : ['type', 'action', 'tailLines'];
      exact(object, keys);
      const tailLines = object.tailLines;
      if (tailLines !== undefined
          && (!Number.isInteger(tailLines) || (tailLines as number) < 1 || (tailLines as number) > 1000)) {
        invalid();
      }
      const normalizedTailLines = tailLines as number | undefined;
      return {
        type,
        action: string(object, 'action'),
        ...(normalizedTailLines === undefined ? {} : { tailLines: normalizedTailLines }),
      };
    }
    case 'publishCommittedBundle': {
      const mode = string(object, 'mode');
      if (mode === 'CREATE') {
        exact(object, ['type', 'mode']);
        return { type, mode };
      }
      if (mode === 'REPLACE') {
        exact(object, ['type', 'mode', 'scenarioId']);
        return { type, mode, scenarioId: string(object, 'scenarioId') };
      }
      return invalid();
    }
    case 'openScenarioDetails':
    case 'openScenarioRaw':
    case 'openScenarioSchema':
    case 'openScenarioTemplate':
      exact(object, ['type', 'scenarioId']);
      return { type, scenarioId: string(object, 'scenarioId') };
    case 'selectScenarioSection': {
      exact(object, ['type', 'scenarioId', 'bundleKey', 'section']);
      const section = string(object, 'section');
      if (!['OVERVIEW', 'FILES', 'INPUTS'].includes(section)) invalid();
      return {
        type,
        scenarioId: string(object, 'scenarioId'),
        bundleKey: string(object, 'bundleKey'),
        section: section as ScenarioSection,
      };
    }
    case 'openScenarioBundleFile':
      exact(object, ['type', 'bundleKey', 'path']);
      return { type, bundleKey: string(object, 'bundleKey'), path: string(object, 'path') };
    case 'validateRepositoryBundle':
    case 'deployRepositoryBundle':
    case 'replaceRepositoryBundle':
      exact(object, ['type', 'candidateId']);
      return { type, candidateId: string(object, 'candidateId') };
    case 'openRepositoryBundleFile':
      exact(object, ['type', 'candidateId', 'path']);
      return { type, candidateId: string(object, 'candidateId'), path: string(object, 'path') };
    case 'openRepositoryRename':
      exact(object, ['type', 'candidateId', 'scenarioId', 'scenarioName']);
      return {
        type,
        candidateId: string(object, 'candidateId'),
        scenarioId: string(object, 'scenarioId'),
        scenarioName: string(object, 'scenarioName'),
      };
    default:
      throw new ConnectionContractError('WEBVIEW_MESSAGE_UNKNOWN', `WEBVIEW_MESSAGE_UNKNOWN: ${type}`);
  }
}

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid();
  return value as Record<string, unknown>;
}

function string(value: Record<string, unknown>, field: string): string {
  const result = value[field];
  if (typeof result !== 'string' || !result.trim()) invalid();
  return result.trim();
}

function nullableString(value: Record<string, unknown>, field: string): string | null {
  const result = value[field];
  if (result === null) return null;
  if (typeof result !== 'string' || !result.trim()) invalid();
  return result.trim();
}

function boolean(value: Record<string, unknown>, field: string): boolean {
  const result = value[field];
  if (typeof result !== 'boolean') invalid();
  return result;
}

function exact(value: Record<string, unknown>, expected: string[]): void {
  if (Object.keys(value).sort().join('|') !== [...expected].sort().join('|')) invalid();
}

function invalid(): never {
  throw new ConnectionContractError('WEBVIEW_MESSAGE_INVALID', 'WEBVIEW_MESSAGE_INVALID');
}
