import assert from 'node:assert/strict';
import test from 'node:test';

import { ConnectionContractError } from '../connection/contracts';
import {
  planRepositoryDeployment,
  suggestedRepositoryIdentity,
} from '../scenarios/repositoryDeployment';

test('deployment uses exact owner-validated identity and creates only when the catalogue has no exact id', () => {
  assert.deepEqual(planRepositoryDeployment([
    { id: 'other', name: 'Other scenario' },
  ], { scenarioId: 'checkout-smoke', scenarioName: 'Checkout smoke' }), {
    kind: 'CREATE',
    scenarioId: 'checkout-smoke',
    scenarioName: 'Checkout smoke',
  });
});

test('an exact deployed id produces an explicit replace-or-rename conflict with fixed suffix suggestions', () => {
  assert.deepEqual(planRepositoryDeployment([
    { id: 'checkout-smoke', name: 'Currently deployed name' },
    { id: 'checkout-smoke-01', name: 'Existing renamed scenario' },
  ], { scenarioId: 'checkout-smoke', scenarioName: 'Checkout smoke' }), {
    kind: 'CONFLICT',
    scenarioId: 'checkout-smoke',
    scenarioName: 'Checkout smoke',
    suggestedScenarioId: 'checkout-smoke-01',
    suggestedScenarioName: 'Checkout smoke-01',
  });
  assert.deepEqual(suggestedRepositoryIdentity(' id ', ' Name '), {
    scenarioId: 'id-01', scenarioName: 'Name-01',
  });
});

test('deployment refuses missing catalogue or owner identity instead of inferring', () => {
  for (const catalogue of [undefined, {}, { templates: [] }]) {
    assert.throws(() => planRepositoryDeployment(catalogue, {
      scenarioId: 'checkout-smoke', scenarioName: 'Checkout smoke',
    }), code('DEPLOYED_SCENARIO_CATALOGUE_INVALID'));
  }
  assert.throws(() => planRepositoryDeployment([], {
    scenarioId: ' ', scenarioName: 'Checkout smoke',
  }), code('REPOSITORY_SCENARIO_IDENTITY_INVALID'));
  assert.throws(() => planRepositoryDeployment([], {
    scenarioId: 'checkout-smoke', scenarioName: ' ',
  }), code('REPOSITORY_SCENARIO_IDENTITY_INVALID'));
  assert.throws(() => suggestedRepositoryIdentity('', 'Name'),
    code('REPOSITORY_SCENARIO_IDENTITY_INVALID'));
});

test('deployment ignores malformed catalogue rows and matches only a normalized string id', () => {
  const identity = { scenarioId: 'checkout-smoke', scenarioName: 'Checkout smoke' };
  assert.deepEqual(planRepositoryDeployment([
    null, 42, 'checkout-smoke', [], { id: 42 }, { id: null },
  ], identity), {
    kind: 'CREATE',
    ...identity,
  });
  assert.equal(planRepositoryDeployment([{ id: ' checkout-smoke ' }], identity).kind, 'CONFLICT');
});

function code(expected: string): (error: unknown) => boolean {
  return error => error instanceof ConnectionContractError && error.code === expected;
}
