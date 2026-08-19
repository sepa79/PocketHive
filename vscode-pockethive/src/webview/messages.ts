import { ConnectionContractError, EndpointSecurityMode } from '../connection/contracts';
import { SWARM_OPERATIONS, SwarmOperation } from '../operations/swarmOperations';

export type CompanionTab = 'Hive' | 'Buzz' | 'Journal' | 'Scenarios' | 'Debug';

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
  | { readonly type: 'selectJournalSwarm'; readonly swarmId: string }
  | { readonly type: 'loadSwarmHistory'; readonly swarmId: string }
  | { readonly type: 'openJournalRun'; readonly swarmId: string; readonly runId: string }
  | { readonly type: 'runSwarmOperation'; readonly swarmId: string; readonly action: SwarmOperation }
  | { readonly type: 'openDebugForSwarm'; readonly swarmId: string }
  | { readonly type: 'selectDebugSwarm'; readonly swarmId: string }
  | { readonly type: 'selectDebugWorker'; readonly runtimeId: string }
  | { readonly type: 'runDebug'; readonly action: string; readonly tailLines?: number }
  | { readonly type: 'validateCommittedBundle' }
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
    case 'openDebugForSwarm':
    case 'selectDebugSwarm':
      exact(object, ['type', 'swarmId']);
      return { type, swarmId: string(object, 'swarmId') };
    case 'openJournalRun':
      exact(object, ['type', 'swarmId', 'runId']);
      return { type, swarmId: string(object, 'swarmId'), runId: string(object, 'runId') };
    case 'runSwarmOperation': {
      exact(object, ['type', 'swarmId', 'action']);
      const action = string(object, 'action');
      if (!Object.values(SWARM_OPERATIONS).includes(action as SwarmOperation)) invalid();
      return { type, swarmId: string(object, 'swarmId'), action: action as SwarmOperation };
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
      invalid();
    }
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

function exact(value: Record<string, unknown>, expected: string[]): void {
  if (Object.keys(value).sort().join('|') !== [...expected].sort().join('|')) invalid();
}

function invalid(): never {
  throw new ConnectionContractError('WEBVIEW_MESSAGE_INVALID', 'WEBVIEW_MESSAGE_INVALID');
}
