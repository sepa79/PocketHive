import assert from 'node:assert/strict';
import test from 'node:test';

import { createConnectionProfile } from '../connection/profile';
import { ConnectionContractError } from '../connection/contracts';
import { debugToolCall, DEBUG_ACTIONS } from '../debug/actions';
import { KeyValueStore, McpConnectionProfileRepository } from '../storage/profileRepository';
import { decodeWebviewCommand } from '../webview/messages';

test('profiles persist globally, selection stays workspace-local, and secrets are deleted separately', async () => {
  const global = memory();
  const workspace = memory();
  const secrets = new Map<string, string>([['secret.nft', 'oauth-material']]);
  const repository = new McpConnectionProfileRepository(global, workspace, {
    get: async key => secrets.get(key),
    store: async (key, value) => { secrets.set(key, value); },
    delete: async key => { secrets.delete(key); },
  });
  const profile = createConnectionProfile({
    id: 'nft', displayName: 'NFT Lab', mcpUrl: 'https://nft.example/mcp',
    endpointSecurityMode: 'REMOTE_HTTPS', secretKey: 'secret.nft',
  });

  await repository.save(profile);
  await repository.select(profile.id);

  assert.deepEqual(repository.list(), [profile]);
  assert.equal(repository.activeProfileId(), 'nft');
  assert.doesNotMatch(JSON.stringify(global.values), /oauth-material|Connected|principal/);

  await repository.remove(profile.id);
  assert.deepEqual(repository.list(), []);
  assert.equal(repository.activeProfileId(), undefined);
  assert.equal(secrets.size, 0);
});

test('corrupt profiles and hostile webview messages fail closed', () => {
  const global = memory();
  global.values.set('pockethive.mcpConnectionProfiles', [{
    id: 'bad', displayName: 'Bad', mcpUrl: 'https://example/mcp',
    endpointSecurityMode: 'REMOTE_HTTPS', authenticationMode: 'OAUTH_AUTHORIZATION_CODE_PKCE',
    secretKey: 'secret', connected: true,
  }]);
  const repository = new McpConnectionProfileRepository(global, memory(), {
    get: async () => undefined, store: async () => {}, delete: async () => {},
  });
  assert.throws(() => repository.list(), /PROFILE_STORE_CORRUPT/);
  assert.throws(() => decodeWebviewCommand({ type: 'refresh', injected: true }), /WEBVIEW_MESSAGE_INVALID/);
  assert.throws(() => decodeWebviewCommand({ type: 'executeShell', command: 'rm' }), /WEBVIEW_MESSAGE_UNKNOWN/);
  assert.throws(() => decodeWebviewCommand({ type: 'runDebug', action: 'Logs', tailLines: 0 }), /WEBVIEW_MESSAGE_INVALID/);
  assert.deepEqual(decodeWebviewCommand({ type: 'selectTab', tab: 'Debug' }), { type: 'selectTab', tab: 'Debug' });
  assert.deepEqual(decodeWebviewCommand({ type: 'validateCommittedBundle' }), { type: 'validateCommittedBundle' });
  assert.deepEqual(decodeWebviewCommand({ type: 'discardPendingBundle' }), { type: 'discardPendingBundle' });
  assert.deepEqual(decodeWebviewCommand({ type: 'publishCommittedBundle', mode: 'CREATE' }), {
    type: 'publishCommittedBundle', mode: 'CREATE',
  });
  assert.deepEqual(decodeWebviewCommand({
    type: 'publishCommittedBundle', mode: 'REPLACE', scenarioId: 'db-smoke',
  }), { type: 'publishCommittedBundle', mode: 'REPLACE', scenarioId: 'db-smoke' });
  assert.throws(() => decodeWebviewCommand({
    type: 'publishCommittedBundle', mode: 'REPLACE',
  }), /WEBVIEW_MESSAGE_INVALID/);
  assert.throws(() => decodeWebviewCommand({
    type: 'publishCommittedBundle', mode: 'CREATE', scenarioId: 'inferred-id',
  }), /WEBVIEW_MESSAGE_INVALID/);
});

test('webview decoder accepts only exact typed commands and boundary values', () => {
  assert.deepEqual(decodeWebviewCommand({ type: 'ready' }), { type: 'ready' });
  assert.deepEqual(decodeWebviewCommand({ type: 'addEnvironment' }), { type: 'addEnvironment' });
  assert.deepEqual(decodeWebviewCommand({ type: 'backToEnvironments' }), { type: 'backToEnvironments' });
  assert.deepEqual(decodeWebviewCommand({ type: 'saveOpen' }), { type: 'saveOpen' });
  assert.deepEqual(decodeWebviewCommand({ type: 'signInAgain' }), { type: 'signInAgain' });
  assert.deepEqual(decodeWebviewCommand({ type: 'retryTest' }), { type: 'retryTest' });
  assert.deepEqual(decodeWebviewCommand({ type: 'cancelConnection' }), { type: 'cancelConnection' });
  assert.deepEqual(decodeWebviewCommand({
    type: 'connect', displayName: ' NFT Lab ', mcpUrl: ' https://nft.example/mcp ',
    endpointSecurityMode: 'REMOTE_HTTPS',
  }), {
    type: 'connect', displayName: 'NFT Lab', mcpUrl: 'https://nft.example/mcp',
    endpointSecurityMode: 'REMOTE_HTTPS',
  });
  assert.deepEqual(decodeWebviewCommand({
    type: 'connect', displayName: 'Local', mcpUrl: 'http://127.0.0.1:8088/mcp',
    endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP',
  }), {
    type: 'connect', displayName: 'Local', mcpUrl: 'http://127.0.0.1:8088/mcp',
    endpointSecurityMode: 'LOCAL_LOOPBACK_HTTP',
  });
  assert.deepEqual(decodeWebviewCommand({ type: 'selectDebugWorker', runtimeId: ' worker-1 ' }), {
    type: 'selectDebugWorker', runtimeId: 'worker-1',
  });
  assert.deepEqual(decodeWebviewCommand({ type: 'removeEnvironment', profileId: ' nft ' }), {
    type: 'removeEnvironment', profileId: 'nft',
  });
  assert.deepEqual(decodeWebviewCommand({ type: 'openEnvironment', profileId: ' nft ' }), {
    type: 'openEnvironment', profileId: 'nft',
  });
  assert.deepEqual(decodeWebviewCommand({ type: 'selectTab', tab: 'Hive' }), {
    type: 'selectTab', tab: 'Hive',
  });
  assert.deepEqual(decodeWebviewCommand({ type: 'selectTab', tab: 'Journal' }), {
    type: 'selectTab', tab: 'Journal',
  });
  assert.deepEqual(decodeWebviewCommand({ type: 'selectTab', tab: 'Buzz' }), {
    type: 'selectTab', tab: 'Buzz',
  });
  assert.deepEqual(decodeWebviewCommand({ type: 'selectTab', tab: 'Scenarios' }), {
    type: 'selectTab', tab: 'Scenarios',
  });
  assert.deepEqual(decodeWebviewCommand({ type: 'selectDebugSwarm', swarmId: ' swarm-1 ' }), {
    type: 'selectDebugSwarm', swarmId: 'swarm-1',
  });
  assert.deepEqual(decodeWebviewCommand({ type: 'runDebug', action: ' Workers ' }), {
    type: 'runDebug', action: 'Workers',
  });
  assert.deepEqual(decodeWebviewCommand({ type: 'runDebug', action: 'Logs', tailLines: 1000 }), {
    type: 'runDebug', action: 'Logs', tailLines: 1000,
  });
  assert.deepEqual(decodeWebviewCommand({ type: 'runDebug', action: 'Logs', tailLines: 1 }), {
    type: 'runDebug', action: 'Logs', tailLines: 1,
  });

  const invalidMessages: unknown[] = [
    null,
    [],
    'connect',
    Object.assign(() => undefined, { type: 'ready' }),
    { type: 42 },
    { type: 'connect', displayName: 'NFT', mcpUrl: 'https://nft.example/mcp', endpointSecurityMode: 'AUTO' },
    { type: 'connect', displayName: 'NFT', mcpUrl: '', endpointSecurityMode: 'REMOTE_HTTPS' },
    { type: 'connect', displayName: 'NFT', mcpUrl: 'https://nft.example/mcp', endpointSecurityMode: 'REMOTE_HTTPS', extra: true },
    { type: 'selectDebugWorker', runtimeId: '' },
    { type: 'selectDebugWorker', runtimeId: 'worker', extra: true },
    { type: 'selectTab', tab: 'Settings' },
    { type: 'runDebug', action: 'Logs', tailLines: '100' },
    { type: 'runDebug', action: 'Logs', tailLines: 1001 },
    { type: 'runDebug', action: 'Logs', tailLines: 100, injected: true },
    { type: 'openEnvironment', profileId: '   ' },
    { type: 'publishCommittedBundle', mode: 'UPSERT' },
    { type: 'publishCommittedBundle', mode: 'UPSERT', scenarioId: 'scenario' },
  ];
  for (const message of invalidMessages) {
    assert.throws(() => decodeWebviewCommand(message), (error: unknown) =>
      error instanceof ConnectionContractError
      && error.code === 'WEBVIEW_MESSAGE_INVALID'
      && error.message === 'WEBVIEW_MESSAGE_INVALID: WEBVIEW_MESSAGE_INVALID');
  }
  assert.throws(() => decodeWebviewCommand({ type: 'executeShell' }), (error: unknown) =>
    error instanceof ConnectionContractError
    && error.code === 'WEBVIEW_MESSAGE_UNKNOWN'
    && error.message === 'WEBVIEW_MESSAGE_UNKNOWN: WEBVIEW_MESSAGE_UNKNOWN: executeShell');
});

test('debug actions map exactly to owner tools and never expose cleanup execute', () => {
  assert.equal(DEBUG_ACTIONS.length, 10);
  assert.equal(DEBUG_ACTIONS.some(action => String(action.tool) === 'runtime_cleanup_execute'), false);
  assert.deepEqual(debugToolCall('Workers', 'swarm-1', undefined), {
    name: 'runtime_list_workers', arguments: { swarmId: 'swarm-1' },
  });
  assert.deepEqual(debugToolCall('Logs', 'swarm-1', 'worker-2', 200), {
    name: 'runtime_tail_worker_logs',
    arguments: { swarmId: 'swarm-1', runtimeId: 'worker-2', tailLines: 200 },
  });
  assert.deepEqual(debugToolCall('Logs', ' swarm-1 ', ' worker-2 ', 1), {
    name: 'runtime_tail_worker_logs',
    arguments: { swarmId: 'swarm-1', runtimeId: 'worker-2', tailLines: 1 },
  });
  assert.throws(() => debugToolCall('Logs', 'swarm-1', undefined, 200), /DEBUG_WORKER_REQUIRED/);
  assert.throws(() => debugToolCall('Logs', 'swarm-1', '   ', 200), /DEBUG_WORKER_REQUIRED/);
  assert.throws(() => debugToolCall('Logs', 'swarm-1', 'worker-2', 0), /DEBUG_TAIL_LINES_INVALID/);
  assert.throws(() => debugToolCall('Logs', 'swarm-1', 'worker-2', 1.5), /DEBUG_TAIL_LINES_INVALID/);
  assert.deepEqual(debugToolCall('Logs', 'swarm-1', 'worker-2', 1000), {
    name: 'runtime_tail_worker_logs',
    arguments: { swarmId: 'swarm-1', runtimeId: 'worker-2', tailLines: 1000 },
  });
  assert.throws(() => debugToolCall('Logs', 'swarm-1', 'worker-2', 1001), (error: unknown) =>
    error instanceof ConnectionContractError && error.code === 'DEBUG_TAIL_LINES_INVALID');
  assert.throws(() => debugToolCall('Unknown', 'swarm-1', undefined), (error: unknown) =>
    error instanceof ConnectionContractError && error.code === 'DEBUG_ACTION_UNKNOWN');
  assert.throws(() => debugToolCall('Workers', '   ', undefined), (error: unknown) =>
    error instanceof ConnectionContractError && error.code === 'DEBUG_SWARM_REQUIRED');
  assert.throws(() => debugToolCall('Workers', undefined, undefined), (error: unknown) =>
    error instanceof ConnectionContractError && error.code === 'DEBUG_SWARM_REQUIRED');
});

function memory(): KeyValueStore & { values: Map<string, unknown> } {
  const values = new Map<string, unknown>();
  return {
    values,
    get: <T>(key: string) => values.get(key) as T | undefined,
    update: async (key: string, value: unknown) => {
      if (value === undefined) values.delete(key); else values.set(key, value);
    },
  };
}
