import assert from 'node:assert/strict';
import test from 'node:test';

import { ConnectionContractError } from '../connection/contracts';
import {
  previewLanguageForPath,
  scenarioReadText,
  scenarioReadToolCall,
  SCENARIO_ASSETS,
} from '../scenarios/scenarioReads';

test('scenario read operations map to one exact MCP tool with no fallback path inference', () => {
  assert.deepEqual(scenarioReadToolCall(SCENARIO_ASSETS.RAW, ' demo '), {
    name: 'scenario_raw_read',
    arguments: { scenarioId: 'demo' },
  });
  assert.deepEqual(scenarioReadToolCall(SCENARIO_ASSETS.SCHEMA, 'demo', ' schemas/body.schema.json '), {
    name: 'scenario_schema_read',
    arguments: { scenarioId: 'demo', path: 'schemas/body.schema.json' },
  });
  assert.deepEqual(scenarioReadToolCall(SCENARIO_ASSETS.TEMPLATE, 'demo', ' templates/http/request.yaml '), {
    name: 'scenario_template_read',
    arguments: { scenarioId: 'demo', path: 'templates/http/request.yaml' },
  });
});

test('scenario read operations reject blank exact identifiers and asset paths', () => {
  assert.throws(() => scenarioReadToolCall(SCENARIO_ASSETS.RAW, '   '), (error: unknown) =>
    error instanceof ConnectionContractError && error.code === 'SCENARIO_ID_REQUIRED');
  assert.throws(() => scenarioReadToolCall(SCENARIO_ASSETS.SCHEMA, 'demo'), (error: unknown) =>
    error instanceof ConnectionContractError && error.code === 'SCENARIO_ASSET_PATH_REQUIRED');
  assert.throws(() => scenarioReadToolCall(SCENARIO_ASSETS.SCHEMA, 'demo', '   '), (error: unknown) =>
    error instanceof ConnectionContractError && error.code === 'SCENARIO_ASSET_PATH_REQUIRED');
  assert.throws(() => scenarioReadToolCall(SCENARIO_ASSETS.RAW, 'demo', 'schemas/body.json'), (error: unknown) =>
    error instanceof ConnectionContractError && error.code === 'SCENARIO_ASSET_PATH_FORBIDDEN');
});

test('scenario preview text accepts exact string and MCP text content results only', () => {
  assert.equal(scenarioReadText('name: demo\n'), 'name: demo\n');
  assert.equal(scenarioReadText([{ type: 'text', text: 'line 1' }, { type: 'text', text: 'line 2' }]), 'line 1\n\nline 2');
  assert.throws(() => scenarioReadText([{ type: 'json', text: '{}' }]), (error: unknown) =>
    error instanceof ConnectionContractError && error.code === 'SCENARIO_PREVIEW_TEXT_INVALID');
  assert.throws(() => scenarioReadText({ text: 'nope' }), (error: unknown) =>
    error instanceof ConnectionContractError && error.code === 'SCENARIO_PREVIEW_TEXT_INVALID');
});

test('scenario preview language follows the exact selected file path', () => {
  assert.equal(previewLanguageForPath(undefined), 'yaml');
  assert.equal(previewLanguageForPath('scenario.yaml'), 'yaml');
  assert.equal(previewLanguageForPath('schemas/body.schema.json'), 'json');
  assert.equal(previewLanguageForPath('seed/init.sql'), 'sql');
  assert.equal(previewLanguageForPath('scripts/setup.sh'), 'shellscript');
  assert.equal(previewLanguageForPath('README.md'), 'markdown');
  assert.equal(previewLanguageForPath('notes.txt'), 'plaintext');
});
