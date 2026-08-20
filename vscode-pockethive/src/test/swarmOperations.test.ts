import assert from 'node:assert/strict';
import test from 'node:test';

import {
  lifecycleToolCall,
  MAX_SWARM_ACTIONS,
  MAX_SWARM_ID_LENGTH,
  primaryActionsForSwarms,
  primaryOperationForSwarm,
  primaryOperationForStatus,
  swarmDisplayStatus,
  swarmIdsForOperation,
  SWARM_OPERATIONS,
} from '../operations/swarmOperations';

test('known swarm states expose one context-valid primary operation without fallback', () => {
  assert.equal(primaryOperationForStatus('RUNNING'), SWARM_OPERATIONS.STOP);
  assert.equal(primaryOperationForStatus('READY'), SWARM_OPERATIONS.START);
  assert.equal(primaryOperationForStatus('STOPPED'), SWARM_OPERATIONS.START);
  assert.equal(primaryOperationForStatus('  running  '), SWARM_OPERATIONS.STOP);
  assert.equal(primaryOperationForStatus('FAILED'), undefined);
  assert.equal(primaryOperationForStatus('unexpected'), undefined);
});

test('swarm contract fields derive display status and one valid primary operation', () => {
  assert.equal(swarmDisplayStatus({
    controllerState: 'READY', workloadState: 'STOPPED', runtimeResourceState: 'PRESENT',
  }), 'READY');
  assert.equal(swarmDisplayStatus({
    controllerState: 'READY', workloadState: 'RUNNING', runtimeResourceState: 'PRESENT',
  }), 'RUNNING');
  assert.equal(swarmDisplayStatus({
    controllerState: 'PROVISIONING', workloadState: 'UNKNOWN', runtimeResourceState: 'PRESENT',
  }), 'PROVISIONING');
  assert.equal(swarmDisplayStatus({
    controllerState: 'READY', workloadState: 'STOPPED', runtimeResourceState: 'REMOVING',
  }), 'REMOVING');
  assert.equal(primaryOperationForSwarm({
    controllerState: 'READY', workloadState: 'RUNNING', observationStale: false, runtimeResourceState: 'PRESENT',
  }), SWARM_OPERATIONS.STOP);
  assert.equal(primaryOperationForSwarm({
    controllerState: 'READY', workloadState: 'STOPPED', observationStale: false, runtimeResourceState: 'PRESENT',
  }), SWARM_OPERATIONS.START);
  assert.equal(primaryOperationForSwarm({
    controllerState: 'READY', workloadState: 'STOPPED', observationStale: true, runtimeResourceState: 'PRESENT',
  }), undefined);
  assert.equal(primaryOperationForSwarm({
    controllerState: 'READY',
    workloadState: 'STOPPED',
    observationStale: false,
    runtimeResourceState: 'PRESENT',
    activeOperation: { state: 'ACCEPTED' },
  }), undefined);
});

test('derived actions accept only bounded exact owner records', () => {
  const callableRecord = Object.assign(() => undefined, {
    id: 'callable-swarm',
    controllerState: 'READY',
    workloadState: 'RUNNING',
    observationStale: false,
    runtimeResourceState: 'PRESENT',
  });
  const maximumLengthId = 'm'.repeat(MAX_SWARM_ID_LENGTH);
  assert.deepEqual(primaryActionsForSwarms([
    {
      id: ' swarm-a ',
      controllerState: 'READY',
      workloadState: 'RUNNING',
      observationStale: false,
      runtimeResourceState: 'PRESENT',
    },
    {
      id: 'swarm-b',
      controllerState: 'READY',
      workloadState: 'STOPPED',
      observationStale: false,
      runtimeResourceState: 'PRESENT',
    },
    {
      id: maximumLengthId,
      controllerState: 'READY',
      workloadState: 'STOPPED',
      observationStale: false,
      runtimeResourceState: 'PRESENT',
    },
    {
      id: 'swarm-c',
      controllerState: 'FAILED',
      workloadState: 'STOPPED',
      observationStale: false,
      runtimeResourceState: 'PRESENT',
    },
    { id: '', controllerState: 'READY', workloadState: 'RUNNING', observationStale: false, runtimeResourceState: 'PRESENT' },
    { id: 'x'.repeat(MAX_SWARM_ID_LENGTH + 1), controllerState: 'READY', workloadState: 'RUNNING', observationStale: false, runtimeResourceState: 'PRESENT' },
    { id: 42, controllerState: 'READY', workloadState: 'RUNNING', observationStale: false, runtimeResourceState: 'PRESENT' },
    { id: 'swarm-d', controllerState: 'READY', workloadState: 42, observationStale: false, runtimeResourceState: 'PRESENT' },
    callableRecord,
    null,
    [],
  ]), {
    'swarm-a': SWARM_OPERATIONS.STOP,
    'swarm-b': SWARM_OPERATIONS.START,
    [maximumLengthId]: SWARM_OPERATIONS.START,
  });
  assert.deepEqual(primaryActionsForSwarms({ items: [] }), {});

  const oversized = Array.from({ length: MAX_SWARM_ACTIONS + 1 }, (_, index) => ({
    id: `swarm-${index}`,
    controllerState: 'READY',
    workloadState: 'STOPPED',
    observationStale: false,
    runtimeResourceState: 'PRESENT',
  }));
  const bounded = primaryActionsForSwarms(oversized);
  assert.equal(Object.keys(bounded).length, MAX_SWARM_ACTIONS);
  assert.equal(bounded[`swarm-${MAX_SWARM_ACTIONS - 1}`], SWARM_OPERATIONS.START);
  assert.equal(bounded[`swarm-${MAX_SWARM_ACTIONS}`], undefined);
});

test('batch lifecycle targets use only exact eligible swarms for the requested action', () => {
  assert.deepEqual(swarmIdsForOperation([
    { id: ' swarm-a ', controllerState: 'READY', workloadState: 'RUNNING', observationStale: false, runtimeResourceState: 'PRESENT' },
    { id: 'swarm-b', controllerState: 'READY', workloadState: 'STOPPED', observationStale: false, runtimeResourceState: 'PRESENT' },
    { id: 'swarm-c', controllerState: 'READY', workloadState: 'STOPPED', observationStale: false, runtimeResourceState: 'PRESENT' },
    { id: 'swarm-d', controllerState: 'FAILED', workloadState: 'STOPPED', observationStale: false, runtimeResourceState: 'PRESENT' },
  ], SWARM_OPERATIONS.START), ['swarm-b', 'swarm-c']);
  assert.deepEqual(swarmIdsForOperation([
    { id: ' swarm-a ', controllerState: 'READY', workloadState: 'RUNNING', observationStale: false, runtimeResourceState: 'PRESENT' },
    { id: 'swarm-b', controllerState: 'READY', workloadState: 'STOPPED', observationStale: false, runtimeResourceState: 'PRESENT' },
    { id: 'swarm-c', controllerState: 'READY', workloadState: 'STOPPED', observationStale: false, runtimeResourceState: 'PRESENT' },
    { id: 'swarm-d', controllerState: 'FAILED', workloadState: 'STOPPED', observationStale: false, runtimeResourceState: 'PRESENT' },
  ], SWARM_OPERATIONS.STOP), ['swarm-a']);
  assert.deepEqual(swarmIdsForOperation({ items: [] }, SWARM_OPERATIONS.START), []);
  assert.deepEqual(swarmIdsForOperation([], SWARM_OPERATIONS.REMOVE), []);
});

test('lifecycle operations map to one exact MCP tool and caller-stable idempotency key', () => {
  assert.deepEqual(lifecycleToolCall(SWARM_OPERATIONS.START, ' swarm/a ', ' idem-a '), {
    name: 'swarm_start', arguments: { swarmId: 'swarm/a', idempotencyKey: 'idem-a' },
  });
  assert.deepEqual(lifecycleToolCall(SWARM_OPERATIONS.STOP, 'swarm/a', 'idem-a'), {
    name: 'swarm_stop', arguments: { swarmId: 'swarm/a', idempotencyKey: 'idem-a' },
  });
  assert.deepEqual(lifecycleToolCall(SWARM_OPERATIONS.REMOVE, 'swarm/a', 'idem-a'), {
    name: 'swarm_remove', arguments: { swarmId: 'swarm/a', idempotencyKey: 'idem-a' },
  });
});

test('lifecycle operations reject blank exact targets and keys', () => {
  assert.throws(() => lifecycleToolCall(SWARM_OPERATIONS.START, ' ', 'idem'), /SWARM_ID_REQUIRED/);
  assert.throws(() => lifecycleToolCall(SWARM_OPERATIONS.START, 'swarm-a', ' '), /IDEMPOTENCY_KEY_REQUIRED/);
});
