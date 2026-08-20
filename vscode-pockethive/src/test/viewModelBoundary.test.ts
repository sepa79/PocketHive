import assert from 'node:assert/strict';
import test from 'node:test';

import {
  boundCompanionViewModel,
  VIEW_FIELD_BYTE_LIMIT,
} from '../webview/viewModelBoundary';
import {
  SIDEBAR_EVENT_LIMIT,
  workspaceToolCall,
} from '../webview/workspaceTool';

test('companion view-model bounding preserves its root contract when owner data is oversized', () => {
  const profiles = [{ id: 'local', displayName: 'Local MCP' }];
  const model = boundCompanionViewModel({
    page: 'workspace',
    profiles,
    activeTab: 'Buzz',
    workspaceData: { items: [{ payload: 'x'.repeat(VIEW_FIELD_BYTE_LIMIT + 1) }] },
    busy: false,
  });

  assert.equal(model.page, 'workspace');
  assert.equal(model.activeTab, 'Buzz');
  assert.equal(model.busy, false);
  assert.equal(model.profiles, profiles);
  assert.deepEqual(model.workspaceData, {
    error: {
      code: 'COMPANION_VIEW_DATA_TOO_LARGE',
      message: 'PocketHive data exceeded the VS Code companion field limit.',
    },
    truncated: true,
  });
  assert.doesNotMatch(JSON.stringify(model.workspaceData), /content|xxxx/);
});

test('each untrusted companion field is bounded independently without replacing navigation state', () => {
  const fields = [
    'workspaceData', 'swarmPrimaryActions', 'createSwarmForm', 'journalResult', 'swarmHistoryResult', 'swarmOperationResult',
    'debugResult', 'scenarioFocusTree', 'scenarioFocusInputs', 'pendingBundle', 'bundleResult',
  ] as const;
  for (const field of fields) {
    const model = boundCompanionViewModel({
      page: 'workspace',
      profiles: [],
      activeTab: 'Debug',
      busy: true,
      [field]: { value: 'x'.repeat(VIEW_FIELD_BYTE_LIMIT + 1) },
    });
    assert.equal(model.page, 'workspace');
    assert.equal(model.activeTab, 'Debug');
    assert.deepEqual(model[field], {
      error: {
        code: 'COMPANION_VIEW_DATA_TOO_LARGE',
        message: 'PocketHive data exceeded the VS Code companion field limit.',
      },
      truncated: true,
    });
  }
});

test('companion field bounding preserves small data and redacts sensitive values', () => {
  const model = boundCompanionViewModel({
    page: 'workspace',
    workspaceData: {
      items: [{ kind: 'signal', authorization: 'Bearer secret', nested: { password: 'hidden' } }],
    },
  });

  assert.deepEqual(model.workspaceData, {
    items: [{ kind: 'signal', authorization: '[REDACTED]', nested: { password: '[REDACTED]' } }],
  });
});

test('the exact byte limit is accepted and larger UTF-8 data fails explicitly', () => {
  const exact = 'x'.repeat(VIEW_FIELD_BYTE_LIMIT - 2);
  assert.equal(boundCompanionViewModel({ workspaceData: exact }).workspaceData, exact);

  const oversizedUnicode = '£'.repeat(VIEW_FIELD_BYTE_LIMIT);
  assert.deepEqual(boundCompanionViewModel({ workspaceData: oversizedUnicode }).workspaceData, {
    error: {
      code: 'COMPANION_VIEW_DATA_TOO_LARGE',
      message: 'PocketHive data exceeded the VS Code companion field limit.',
    },
    truncated: true,
  });
});

test('redaction bounds collection breadth before measuring the field', () => {
  const array = Array.from({ length: 1001 }, (_, index) => index);
  const boundedArray = boundCompanionViewModel({ workspaceData: array }).workspaceData as number[];
  assert.equal(boundedArray.length, 1000);
  assert.equal(boundedArray[999], 999);

  const object = Object.fromEntries(Array.from({ length: 1001 }, (_, index) => [`key${index}`, index]));
  const boundedObject = boundCompanionViewModel({ workspaceData: object }).workspaceData as Record<string, number>;
  assert.equal(Object.keys(boundedObject).length, 1000);
  assert.equal(boundedObject.key999, 999);
  assert.equal(boundedObject.key1000, undefined);
});

test('workspace tool calls keep narrow event views bounded and every tab explicit', () => {
  assert.equal(SIDEBAR_EVENT_LIMIT, 10);
  assert.deepEqual(workspaceToolCall('Hive'), { name: 'swarm_list', arguments: {} });
  assert.deepEqual(workspaceToolCall('Buzz'), {
    name: 'debug_hive_journal', arguments: { limit: SIDEBAR_EVENT_LIMIT },
  });
  assert.deepEqual(workspaceToolCall('Journal'), { name: 'swarm_list', arguments: {} });
  assert.deepEqual(workspaceToolCall('Scenarios'), { name: 'scenario_templates_catalog', arguments: {} });
  assert.deepEqual(workspaceToolCall('Debug'), { name: 'swarm_list', arguments: {} });
});
