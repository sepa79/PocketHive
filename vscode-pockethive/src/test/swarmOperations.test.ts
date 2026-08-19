import assert from 'node:assert/strict';
import test from 'node:test';

import {
  lifecycleToolCall,
  MAX_SWARM_ACTIONS,
  MAX_SWARM_ID_LENGTH,
  primaryActionsForSwarms,
  primaryOperationForStatus,
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

test('derived actions accept only bounded exact owner records', () => {
  const callableRecord = Object.assign(() => undefined, { id: 'callable-swarm', status: 'RUNNING' });
  const maximumLengthId = 'm'.repeat(MAX_SWARM_ID_LENGTH);
  assert.deepEqual(primaryActionsForSwarms([
    { id: ' swarm-a ', status: 'running' },
    { id: 'swarm-b', status: 'READY' },
    { id: maximumLengthId, status: 'STOPPED' },
    { id: 'swarm-c', status: 'FAILED' },
    { id: '', status: 'RUNNING' },
    { id: 'x'.repeat(MAX_SWARM_ID_LENGTH + 1), status: 'RUNNING' },
    { id: 42, status: 'RUNNING' },
    { id: 'swarm-d', status: 42 },
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
    id: `swarm-${index}`, status: 'READY',
  }));
  const bounded = primaryActionsForSwarms(oversized);
  assert.equal(Object.keys(bounded).length, MAX_SWARM_ACTIONS);
  assert.equal(bounded[`swarm-${MAX_SWARM_ACTIONS - 1}`], SWARM_OPERATIONS.START);
  assert.equal(bounded[`swarm-${MAX_SWARM_ACTIONS}`], undefined);
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
