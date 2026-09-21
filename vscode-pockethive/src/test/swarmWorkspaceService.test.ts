import assert from 'node:assert/strict';
import test from 'node:test';

import { ConnectionContractError } from '../connection/contracts';
import { SWARM_OPERATIONS } from '../operations/swarmOperations';
import { SwarmWorkspaceService } from '../webview/swarmWorkspaceService';

class RecordingToolPort {
  readonly calls: Array<{ name: string; arguments: Record<string, unknown> }> = [];
  readonly responses = new Map<string, unknown>();
  readonly failures = new Map<string, Error>();

  async callTool(name: string, arguments_: Record<string, unknown> = {}): Promise<unknown> {
    this.calls.push({ name, arguments: arguments_ });
    const key = `${name}:${String(arguments_.swarmId ?? '')}`;
    const failure = this.failures.get(key);
    if (failure) throw failure;
    return this.responses.get(name);
  }
}

test('create form derives its exact default selection and SUT choices from MCP owner data', async () => {
  const tools = new RecordingToolPort();
  tools.responses.set('scenario_templates_catalog', [
    { id: 'retired', defunct: true },
    { id: 'checkout', defunct: false },
  ]);
  tools.responses.set('scenario_suts_list', ['payments', 'ledger']);

  const result = await new SwarmWorkspaceService(tools, () => 'key').createForm();

  assert.deepEqual(result, {
    templates: [{ id: 'retired', defunct: true }, { id: 'checkout', defunct: false }],
    selectedTemplateId: 'checkout',
    selectedScenarioId: 'checkout',
    sutIds: ['payments', 'ledger'],
    autoPullImages: true,
    networkMode: 'DIRECT',
  });
  assert.deepEqual(tools.calls.map(call => call.name), [
    'scenario_templates_catalog', 'scenario_suts_list',
  ]);
});

test('create forwards every explicit owner contract field without hidden fallback values', async () => {
  const tools = new RecordingToolPort();
  tools.responses.set('swarm_create', { accepted: true });
  tools.responses.set('swarm_list', [{ id: 'seed-01' }]);
  const service = new SwarmWorkspaceService(tools, () => 'stable-key');
  const form = {
    templates: [{ id: 'seed-template', defunct: false }],
    selectedTemplateId: 'seed-template',
    selectedScenarioId: 'seed-template',
    sutIds: ['cards'],
    autoPullImages: true,
    networkMode: 'DIRECT' as const,
  };

  const result = await service.create(form, {
    swarmId: 'seed-01',
    templateId: 'seed-template',
    scenarioId: 'seed-template',
    autoPullImages: false,
    sutId: null,
    variablesProfileId: 'ten-thousand',
    networkMode: 'PROXIED',
    networkProfileId: 'latency',
  });

  assert.deepEqual(tools.calls[0], {
    name: 'swarm_create',
    arguments: {
      swarmId: 'seed-01',
      templateId: 'seed-template',
      idempotencyKey: 'stable-key',
      autoPullImages: false,
      sutId: null,
      variablesProfileId: 'ten-thousand',
      networkMode: 'PROXIED',
      networkProfileId: 'latency',
    },
  });
  assert.deepEqual(result, {
    operationResult: { accepted: true },
    swarms: [{ id: 'seed-01' }],
  });
});

test('create rejects a template and scenario pair not present in the owner catalogue', async () => {
  const tools = new RecordingToolPort();
  const service = new SwarmWorkspaceService(tools, () => 'key');
  const form = {
    templates: [{ id: 'known', defunct: false }],
    selectedTemplateId: 'known',
    selectedScenarioId: 'known',
    sutIds: [],
    autoPullImages: true,
    networkMode: 'DIRECT' as const,
  };

  await assert.rejects(
    service.create(form, {
      swarmId: 'swarm', templateId: 'unknown', scenarioId: 'unknown',
      autoPullImages: true, sutId: null, variablesProfileId: null,
      networkMode: 'DIRECT', networkProfileId: null,
    }),
    (error: unknown) => error instanceof ConnectionContractError
      && error.code === 'CREATE_SWARM_TEMPLATE_INVALID',
  );
  assert.equal(tools.calls.length, 0);
});

test('single and batch lifecycle operations use unique stable keys and refresh once', async () => {
  const tools = new RecordingToolPort();
  tools.responses.set('swarm_start', { accepted: true });
  tools.responses.set('swarm_stop', { accepted: true });
  tools.responses.set('swarm_list', []);
  const keys = ['key-1', 'key-2', 'key-3'];
  const service = new SwarmWorkspaceService(tools, () => keys.shift() ?? 'unexpected');

  await service.operate(SWARM_OPERATIONS.START, 'swarm-a');
  const batch = await service.operateBatch(SWARM_OPERATIONS.STOP, [
    { id: 'swarm-b', controllerState: 'READY', workloadState: 'RUNNING', observationStale: false, runtimeResourceState: 'PRESENT' },
    { id: 'swarm-c', controllerState: 'READY', workloadState: 'RUNNING', observationStale: false, runtimeResourceState: 'PRESENT' },
  ]);

  assert.deepEqual(tools.calls.map(call => call.arguments.idempotencyKey).filter(Boolean), [
    'key-1', 'key-2', 'key-3',
  ]);
  assert.deepEqual(batch.operationResult, {
    batchOperation: 'STOP', requested: 2, succeeded: ['swarm-b', 'swarm-c'], failed: [],
  });
  assert.equal(tools.calls.filter(call => call.name === 'swarm_list').length, 2);
});

test('batch lifecycle records per-swarm failures and rejects an empty exact target set', async () => {
  const tools = new RecordingToolPort();
  tools.failures.set('swarm_start:swarm-b', new Error('owner unavailable'));
  tools.responses.set('swarm_list', []);
  const service = new SwarmWorkspaceService(tools, () => 'key');
  const swarms = [
    { id: 'swarm-a', controllerState: 'READY', workloadState: 'STOPPED', observationStale: false, runtimeResourceState: 'PRESENT' },
    { id: 'swarm-b', controllerState: 'READY', workloadState: 'STOPPED', observationStale: false, runtimeResourceState: 'PRESENT' },
  ];

  const result = await service.operateBatch(SWARM_OPERATIONS.START, swarms);
  assert.deepEqual(result.operationResult, {
    batchOperation: 'START', requested: 2, succeeded: ['swarm-a'],
    failed: [{ swarmId: 'swarm-b', error: { code: 'Error', message: 'owner unavailable' } }],
  });
  await assert.rejects(
    service.operateBatch(SWARM_OPERATIONS.STOP, swarms),
    (error: unknown) => error instanceof ConnectionContractError
      && error.code === 'SWARM_BATCH_TARGETS_MISSING'
      && error.message === 'SWARM_BATCH_TARGETS_MISSING: No swarms are eligible for stop',
  );
});

test('history and details use the exact read tools and preserve owner results', async () => {
  const tools = new RecordingToolPort();
  tools.responses.set('debug_journal_runs', { runs: ['run-1'] });
  tools.responses.set('swarm_get', { id: 'swarm-a', controllerState: 'READY' });
  const service = new SwarmWorkspaceService(tools, () => 'key');

  assert.deepEqual(await service.history('swarm-a'), { runs: ['run-1'] });
  assert.deepEqual(await service.details('swarm-a'), { id: 'swarm-a', controllerState: 'READY' });
  assert.deepEqual(tools.calls, [
    { name: 'debug_journal_runs', arguments: { swarmId: 'swarm-a' } },
    { name: 'swarm_get', arguments: { swarmId: 'swarm-a' } },
  ]);
});

test('create form remains explicit when Scenario Manager has no creatable templates', async () => {
  const tools = new RecordingToolPort();
  const templates = [
    null, [], 'not-a-template', { id: 42 }, { id: '   ' }, { id: 'retired', defunct: true },
  ];
  tools.responses.set('scenario_templates_catalog', templates);

  const result = await new SwarmWorkspaceService(tools, () => 'key').createForm();

  assert.deepEqual(result, {
    templates,
    selectedTemplateId: undefined,
    selectedScenarioId: undefined,
    sutIds: [],
    autoPullImages: true,
    networkMode: 'DIRECT',
  });
  assert.deepEqual(tools.calls, [
    { name: 'scenario_templates_catalog', arguments: {} },
  ]);

  const malformedTools = new RecordingToolPort();
  malformedTools.responses.set('scenario_templates_catalog', {});
  assert.deepEqual(await new SwarmWorkspaceService(malformedTools, () => 'key').createForm(), {
    templates: {},
    selectedTemplateId: undefined,
    selectedScenarioId: undefined,
    sutIds: [],
    autoPullImages: true,
    networkMode: 'DIRECT',
  });
  assert.deepEqual(malformedTools.calls, [
    { name: 'scenario_templates_catalog', arguments: {} },
  ]);
});

test('create form rejects malformed owner SUT data and trims canonical identifiers', async () => {
  for (const invalid of [undefined, {}, ['valid', 42], ['valid', '   ']]) {
    const tools = new RecordingToolPort();
    tools.responses.set('scenario_templates_catalog', [{ id: ' checkout ', defunct: false }]);
    tools.responses.set('scenario_suts_list', invalid);

    await assert.rejects(
      new SwarmWorkspaceService(tools, () => 'key').createForm(),
      (error: unknown) => error instanceof ConnectionContractError
        && error.code === 'SCENARIO_SUTS_INVALID'
        && error.message === 'SCENARIO_SUTS_INVALID: Scenario Manager returned invalid SUT identifiers for checkout',
    );
    assert.deepEqual(tools.calls[1], {
      name: 'scenario_suts_list', arguments: { scenarioId: 'checkout' },
    });
  }

  const tools = new RecordingToolPort();
  tools.responses.set('scenario_templates_catalog', [{ id: ' checkout ', defunct: false }]);
  tools.responses.set('scenario_suts_list', [' payments ', 'ledger']);
  const result = await new SwarmWorkspaceService(tools, () => 'key').createForm();
  assert.deepEqual(result.sutIds, ['payments', 'ledger']);
  assert.equal(result.selectedTemplateId, 'checkout');
});

test('template selection requires one exact non-defunct catalogue pair and returns updated SUT data', async () => {
  const tools = new RecordingToolPort();
  tools.responses.set('scenario_suts_list', [' cards ']);
  const service = new SwarmWorkspaceService(tools, () => 'key');
  const current = {
    templates: [
      { id: 'first', defunct: false },
      { id: 'second', defunct: false },
      { id: 'retired', defunct: true },
    ],
    selectedTemplateId: 'first', selectedScenarioId: 'first', sutIds: [],
    autoPullImages: false, networkMode: 'PROXIED' as const,
  };

  assert.deepEqual(await service.selectTemplate(current, 'second', 'second'), {
    ...current, selectedTemplateId: 'second', selectedScenarioId: 'second', sutIds: ['cards'],
  });
  assert.deepEqual(tools.calls, [
    { name: 'scenario_suts_list', arguments: { scenarioId: 'second' } },
  ]);

  for (const [templateId, scenarioId] of [
    ['first', 'second'], ['second', 'first'], ['retired', 'retired'], ['unknown', 'unknown'],
  ]) {
    await assert.rejects(
      service.selectTemplate(current, templateId, scenarioId),
      (error: unknown) => error instanceof ConnectionContractError
        && error.code === 'CREATE_SWARM_TEMPLATE_INVALID'
        && error.message === 'CREATE_SWARM_TEMPLATE_INVALID: Select one exact non-defunct Scenario Manager template',
    );
  }
  assert.equal(tools.calls.length, 1);
});

test('non-array template catalogues fail selection without inference', async () => {
  const tools = new RecordingToolPort();
  const service = new SwarmWorkspaceService(tools, () => 'key');
  await assert.rejects(
    service.selectTemplate({
      templates: {}, autoPullImages: true, networkMode: 'DIRECT',
    }, 'template', 'template'),
    (error: unknown) => error instanceof ConnectionContractError
      && error.code === 'CREATE_SWARM_TEMPLATE_INVALID',
  );
  assert.equal(tools.calls.length, 0);

  const forgedFunction = Object.assign(() => undefined, { id: 'forged-template' });
  await assert.rejects(
    service.selectTemplate({
      templates: [forgedFunction], autoPullImages: true, networkMode: 'DIRECT',
    }, 'forged-template', 'forged-template'),
    (error: unknown) => error instanceof ConnectionContractError
      && error.code === 'CREATE_SWARM_TEMPLATE_INVALID',
  );
});

test('batch lifecycle preserves typed and non-Error failures without aborting refresh', async () => {
  class MixedFailurePort extends RecordingToolPort {
    override async callTool(name: string, arguments_: Record<string, unknown> = {}): Promise<unknown> {
      this.calls.push({ name, arguments: arguments_ });
      if (arguments_.swarmId === 'typed') {
        throw new ConnectionContractError('OWNER_REJECTED', 'Owner rejected request');
      }
      if (arguments_.swarmId === 'opaque') throw 'opaque failure';
      return this.responses.get(name);
    }
  }
  const tools = new MixedFailurePort();
  tools.responses.set('swarm_list', [{ id: 'after-refresh' }]);
  const service = new SwarmWorkspaceService(tools, () => 'key');
  const stopped = (id: string) => ({
    id, controllerState: 'READY', workloadState: 'STOPPED',
    observationStale: false, runtimeResourceState: 'PRESENT',
  });

  const result = await service.operateBatch(SWARM_OPERATIONS.START, [stopped('typed'), stopped('opaque')]);

  assert.deepEqual(result, {
    operationResult: {
      batchOperation: 'START', requested: 2, succeeded: [],
      failed: [
        { swarmId: 'typed', error: { code: 'OWNER_REJECTED', message: 'OWNER_REJECTED: Owner rejected request' } },
        { swarmId: 'opaque', error: { code: 'COMPANION_ERROR', message: 'opaque failure' } },
      ],
    },
    swarms: [{ id: 'after-refresh' }],
  });
});
