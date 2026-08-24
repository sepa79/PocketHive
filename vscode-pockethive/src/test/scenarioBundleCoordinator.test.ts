import assert from 'node:assert/strict';
import { join } from 'node:path';
import test from 'node:test';

import {
  ConnectionContractError,
  McpConnectionProfile,
} from '../connection/contracts';
import {
  BundleMcpClient,
  PendingBundlePublication,
  ScenarioBundleCoordinator,
} from '../scenarios/scenarioBundleCoordinator';

const profile: McpConnectionProfile = Object.freeze({
  id: 'nft', displayName: 'NFT Lab', mcpUrl: 'https://nft.example/mcp',
  endpointSecurityMode: 'REMOTE_HTTPS', authenticationMode: 'OAUTH_AUTHORIZATION_CODE_PKCE',
  secretKey: 'secret.nft',
});
const retainedArchivePath = join('owned-temp', 'pockethive-test-bundle.zip');

test('validates then explicitly publishes the exact retained committed archive through the active MCP session', async () => {
  const calls: Array<{ name: string; args: Record<string, unknown> }> = [];
  const uploads: Uint8Array[] = [];
  let disposed = 0;
  const tickets = [
    {
      ticketId: 'uv-1', uploadUrl: 'https://nft.example/mcp/uploads/uv-123e4567-e89b-12d3-a456-426614174000',
    },
    {
      ticketId: 'up-1', uploadUrl: 'https://nft.example/mcp/uploads/up-123e4567-e89b-12d3-a456-426614174000',
    },
  ];
  const outcomes = [
    { validationReceipt: {
      receiptId: 'vr-1', archiveDigest: `sha256:${'a'.repeat(64)}`,
      bundleContentDigest: `sha256:${'b'.repeat(64)}`, scenarioId: 'mixed-smoke', scenarioName: 'Mixed smoke',
    } },
    { publicationAttempt: { attemptId: 'pa-1', state: 'SUCCEEDED', scenarioId: 'mixed-smoke' } },
  ];
  const coordinator = new ScenarioBundleCoordinator(
    { package: async () => ({
      source: {
        repository: 'https://example.invalid/qa/tests.git', commit: '1'.repeat(40),
        bundlePath: 'scenarios/bundles/mixed-smoke', verification: 'CLIENT_ASSERTED',
      },
      fileManifest: [{ path: 'scenario.yaml', byteCount: 18, sha256: `sha256:${'c'.repeat(64)}` }],
      archivePath: retainedArchivePath,
      dispose: async () => { disposed += 1; },
    }) },
    client(calls, uploads, () => tickets.shift(), () => outcomes.shift(), () => {}),
    async () => new Uint8Array([1, 2, 3]),
  );

  const pending = await coordinator.validate(profile, '/workspace/scenarios/bundles/mixed-smoke');
  assert.equal(disposed, 0);
  assert.equal(pending.receipt.receiptId, 'vr-1');
  assert.deepEqual(calls[0], {
    name: 'scenario_bundle_direct_validation_prepare',
    args: { source: pending.bundle.source, fileManifest: pending.bundle.fileManifest },
  });

  const result = await coordinator.publish(profile, pending, 'REPLACE', 'mixed-smoke');
  assert.deepEqual(result, { attemptId: 'pa-1', state: 'SUCCEEDED', scenarioId: 'mixed-smoke' });
  assert.deepEqual(calls[1], {
    name: 'scenario_bundle_publication_prepare',
    args: {
      validationReceiptId: 'vr-1', mode: 'REPLACE', scenarioId: 'mixed-smoke',
      source: pending.bundle.source, fileManifest: pending.bundle.fileManifest,
      archiveDigest: `sha256:${'a'.repeat(64)}`,
      bundleContentDigest: `sha256:${'b'.repeat(64)}`,
    },
  });
  assert.deepEqual(uploads.map(bytes => [...bytes]), [[1, 2, 3], [1, 2, 3]]);
  assert.equal(disposed, 1);
});

test('fails closed, cleans owned bytes, and never infers publication intent', async () => {
  let disposed = 0;
  const coordinator = new ScenarioBundleCoordinator(
    { package: async () => ({
      source: {
        repository: 'https://example.invalid/tests.git', commit: '1'.repeat(40),
        bundlePath: 'bundle', verification: 'CLIENT_ASSERTED',
      },
      fileManifest: [], archivePath: retainedArchivePath,
      dispose: async () => { disposed += 1; },
    }) },
    {
      callTool: async () => { throw new Error('access denied'); },
      uploadArchive: async () => { throw new Error('must not upload'); },
    },
    async () => new Uint8Array([1]),
  );

  await assert.rejects(coordinator.validate(profile, '/workspace/bundle'), /access denied/);
  assert.equal(disposed, 1);
  const pending = {} as PendingBundlePublication;
  await assert.rejects(coordinator.publish(profile, pending, 'REPLACE', undefined), /SCENARIO_ID_REQUIRED/);
  await assertContractCode(coordinator.publish(profile, pending, 'REPLACE', '   '), 'SCENARIO_ID_REQUIRED');
  await assert.rejects(coordinator.publish(profile, pending, 'CREATE', 'inferred'), /SCENARIO_ID_FORBIDDEN/);
});

test('CREATE publication remains explicit and never invents a scenario id', async () => {
  const calls: Array<{ name: string; args: Record<string, unknown> }> = [];
  let disposed = 0;
  const pending = pendingBundle(() => { disposed += 1; });
  const coordinator = coordinatorWith(client(calls, [],
    () => ({ ticketId: 'up-1', uploadUrl: 'https://nft.example/mcp/uploads/up-123e4567-e89b-12d3-a456-426614174000' }),
    () => ({ publicationAttempt: { attemptId: 'pa-create', state: 'SUCCEEDED', scenarioId: 'server-owned-id' } }),
    () => {}));

  const result = await coordinator.publish(profile, pending, 'CREATE');

  assert.deepEqual(result, { attemptId: 'pa-create', state: 'SUCCEEDED', scenarioId: 'server-owned-id' });
  assert.equal(Object.hasOwn(calls[0].args, 'scenarioId'), false);
  assert.equal(disposed, 1);
});

test('malformed validation tickets, outcomes, and digests fail closed with exact codes', async () => {
  const malformed: Array<{ ticket: unknown; outcome: unknown; code: string }> = [
    { ticket: 'not-an-object', outcome: {}, code: 'BUNDLE_VALIDATION_TICKET_INVALID' },
    { ticket: { uploadUrl: '   ' }, outcome: {}, code: 'BUNDLE_VALIDATION_TICKET_INVALID' },
    { ticket: { uploadUrl: 'https://nft.example/mcp/uploads/uv-1' }, outcome: 'not-an-object',
      code: 'BUNDLE_VALIDATION_OUTCOME_INVALID' },
    { ticket: { uploadUrl: 'https://nft.example/mcp/uploads/uv-1' },
      outcome: { validationReceipt: 'not-an-object' }, code: 'BUNDLE_VALIDATION_OUTCOME_INVALID' },
    { ticket: { uploadUrl: 'https://nft.example/mcp/uploads/uv-1' }, outcome: { validationReceipt: {
      receiptId: 'vr-1', archiveDigest: 'sha256:not-a-digest',
      bundleContentDigest: `sha256:${'b'.repeat(64)}`, scenarioId: 'test', scenarioName: 'Test scenario',
    } }, code: 'BUNDLE_VALIDATION_OUTCOME_INVALID' },
    { ticket: { uploadUrl: 'https://nft.example/mcp/uploads/uv-1' }, outcome: { validationReceipt: {
      receiptId: 'vr-1', archiveDigest: `prefix-sha256:${'a'.repeat(64)}`,
      bundleContentDigest: `sha256:${'b'.repeat(64)}`, scenarioId: 'test', scenarioName: 'Test scenario',
    } }, code: 'BUNDLE_VALIDATION_OUTCOME_INVALID' },
    { ticket: { uploadUrl: 'https://nft.example/mcp/uploads/uv-1' }, outcome: { validationReceipt: {
      receiptId: 'vr-1', archiveDigest: `sha256:${'a'.repeat(64)}-suffix`,
      bundleContentDigest: `sha256:${'b'.repeat(64)}`, scenarioId: 'test', scenarioName: 'Test scenario',
    } }, code: 'BUNDLE_VALIDATION_OUTCOME_INVALID' },
    { ticket: { uploadUrl: 'https://nft.example/mcp/uploads/uv-1' }, outcome: { validationReceipt: {
      receiptId: 'vr-1', archiveDigest: `sha256:${'a'.repeat(64)}`,
      bundleContentDigest: `sha256:${'b'.repeat(64)}`, scenarioId: '   ', scenarioName: 'Test scenario',
    } }, code: 'BUNDLE_VALIDATION_OUTCOME_INVALID' },
    { ticket: { uploadUrl: 'https://nft.example/mcp/uploads/uv-1' }, outcome: { validationReceipt: {
      receiptId: '   ', archiveDigest: `sha256:${'a'.repeat(64)}`,
      bundleContentDigest: `sha256:${'b'.repeat(64)}`, scenarioId: 'test', scenarioName: '   ',
    } }, code: 'BUNDLE_VALIDATION_OUTCOME_INVALID' },
    { ticket: { uploadUrl: 'https://nft.example/mcp/uploads/uv-1' }, outcome: { validationReceipt: {
      receiptId: 42, archiveDigest: `sha256:${'a'.repeat(64)}`,
      bundleContentDigest: `sha256:${'b'.repeat(64)}`, scenarioId: 'test', scenarioName: 'Test scenario',
    } }, code: 'BUNDLE_VALIDATION_OUTCOME_INVALID' },
    { ticket: { uploadUrl: 'https://nft.example/mcp/uploads/uv-1' }, outcome: { validationReceipt: {
      receiptId: 'vr-1', archiveDigest: '   ',
      bundleContentDigest: `sha256:${'b'.repeat(64)}`, scenarioId: 'test', scenarioName: 'Test scenario',
    } }, code: 'BUNDLE_VALIDATION_OUTCOME_INVALID' },
    { ticket: { uploadUrl: 'https://nft.example/mcp/uploads/uv-1' }, outcome: { validationReceipt: {
      receiptId: 'vr-1', archiveDigest: `sha256:${'a'.repeat(64)}`,
      bundleContentDigest: `sha256:${'b'.repeat(64)}`, scenarioId: 'test',
    } }, code: 'BUNDLE_VALIDATION_OUTCOME_INVALID' },
    { ticket: { uploadUrl: 'https://nft.example/mcp/uploads/uv-1' }, outcome: { validationReceipt: {
      receiptId: 'vr-1', archiveDigest: `sha256:${'a'.repeat(64)}`,
      bundleContentDigest: `sha256:${'b'.repeat(64)}`, scenarioId: 'test', scenarioName: 42,
    } }, code: 'BUNDLE_VALIDATION_OUTCOME_INVALID' },
  ];
  for (const sample of malformed) {
    let disposed = 0;
    const coordinator = coordinatorWith(client([], [], () => sample.ticket, () => sample.outcome, () => {}),
      () => { disposed += 1; });
    await assertContractCode(coordinator.validate(profile, '/workspace/bundle'), sample.code);
    assert.equal(disposed, 1);
  }
});

test('validation normalizes surrounding whitespace at the MCP boundary', async () => {
  const coordinator = coordinatorWith(client([], [],
    () => ({ uploadUrl: ' https://nft.example/mcp/uploads/uv-1 ' }),
    () => ({ validationReceipt: {
      receiptId: ' vr-1 ', archiveDigest: ` sha256:${'a'.repeat(64)} `,
      bundleContentDigest: ` sha256:${'b'.repeat(64)} `, scenarioId: ' test ', scenarioName: ' Test scenario ',
    } }),
    () => {}));
  const pending = await coordinator.validate(profile, '/workspace/bundle');
  assert.deepEqual(pending.receipt, {
    receiptId: 'vr-1', archiveDigest: `sha256:${'a'.repeat(64)}`,
    bundleContentDigest: `sha256:${'b'.repeat(64)}`, scenarioId: 'test', scenarioName: 'Test scenario',
  });
  await pending.bundle.dispose();
});

test('malformed publication tickets and outcomes fail closed with exact codes', async () => {
  const malformed: Array<{ ticket: unknown; outcome: unknown; code: string }> = [
    { ticket: 'not-an-object', outcome: {}, code: 'BUNDLE_PUBLICATION_TICKET_INVALID' },
    { ticket: { uploadUrl: '' }, outcome: {}, code: 'BUNDLE_PUBLICATION_TICKET_INVALID' },
    { ticket: { uploadUrl: 'https://nft.example/mcp/uploads/up-1' }, outcome: 'not-an-object',
      code: 'BUNDLE_PUBLICATION_OUTCOME_INVALID' },
    { ticket: { uploadUrl: 'https://nft.example/mcp/uploads/up-1' }, outcome: { publicationAttempt: 'not-an-object' },
      code: 'BUNDLE_PUBLICATION_OUTCOME_INVALID' },
  ];
  for (const sample of malformed) {
    let disposed = 0;
    const coordinator = coordinatorWith(client([], [], () => sample.ticket, () => sample.outcome, () => {}));
    await assertContractCode(coordinator.publish(profile, pendingBundle(() => { disposed += 1; }), 'CREATE'), sample.code);
    assert.equal(disposed, 1);
  }
});

test('publication is bound to the environment that produced validation evidence', async () => {
  const coordinator = coordinatorWith(client([], [], () => ({}), () => ({}), () => {}));
  const otherProfile = { ...profile, id: 'other-environment' };

  await assertContractCode(coordinator.publish(otherProfile, pendingBundle(() => {}), 'CREATE'),
    'BUNDLE_PROFILE_MISMATCH');
});

test('publication MCP failure preserves the original error and disposes retained bytes', async () => {
  const accessDenied = new Error('publication access denied');
  let disposed = 0;
  const coordinator = new ScenarioBundleCoordinator(
    { package: async () => { throw new Error('must not package'); } },
    {
      callTool: async () => { throw accessDenied; },
      uploadArchive: async () => { throw new Error('must not upload'); },
    },
    async () => new Uint8Array([1]),
  );
  await assert.rejects(coordinator.publish(profile, pendingBundle(() => { disposed += 1; }), 'CREATE'),
    error => error === accessDenied);
  assert.equal(disposed, 1);
});

test('reconcile uses the exact attempt id through the active session and does not require retained bundle bytes', async () => {
  const calls: Array<{ name: string; args: Record<string, unknown> }> = [];
  const coordinator = new ScenarioBundleCoordinator(
    { package: async () => { throw new Error('must not package'); } },
    {
      callTool: async (name, args) => {
        calls.push({ name, args });
        return { attemptId: 'pa-ambiguous', state: 'SUCCEEDED', scenarioId: 'mixed-smoke' };
      },
      uploadArchive: async () => { throw new Error('must not upload'); },
    },
    async () => { throw new Error('must not read archive'); },
  );

  assert.deepEqual(await coordinator.reconcile(profile, 'pa-ambiguous'), {
    attemptId: 'pa-ambiguous', state: 'SUCCEEDED', scenarioId: 'mixed-smoke',
  });
  assert.deepEqual(calls, [{
    name: 'scenario_bundle_publication_reconcile',
    args: { attemptId: 'pa-ambiguous' },
  }]);
});

test('reconcile rejects a malformed owner response from the active client', async () => {
  const coordinator = new ScenarioBundleCoordinator(
    { package: async () => { throw new Error('must not package'); } },
    {
      callTool: async () => null,
      uploadArchive: async () => { throw new Error('must not upload'); },
    },
    async () => { throw new Error('must not read archive'); },
  );

  await assertContractCode(coordinator.reconcile(profile, 'pa-malformed'), 'BUNDLE_PUBLICATION_ATTEMPT_INVALID');
});

test('reconcile preserves an active MCP failure', async () => {
  const accessDenied = new Error('reconcile access denied');
  const coordinator = new ScenarioBundleCoordinator(
    { package: async () => { throw new Error('must not package'); } },
    {
      callTool: async () => { throw accessDenied; },
      uploadArchive: async () => { throw new Error('must not upload'); },
    },
    async () => { throw new Error('must not read archive'); },
  );

  await assert.rejects(coordinator.reconcile(profile, 'pa-auth-failure'), error => error === accessDenied);
});

function client(
  calls: Array<{ name: string; args: Record<string, unknown> }>,
  uploads: Uint8Array[],
  ticket: () => unknown,
  upload: () => unknown,
  _close: () => void,
): BundleMcpClient {
  return {
    callTool: async (name, args) => { calls.push({ name, args }); return ticket(); },
    uploadArchive: async (_url, archive) => { uploads.push(archive); return upload(); },
  };
}

function coordinatorWith(bundleClient: BundleMcpClient, dispose: () => void = () => {}): ScenarioBundleCoordinator {
  return new ScenarioBundleCoordinator(
    { package: async () => pendingBundle(dispose).bundle },
    bundleClient,
    async () => new Uint8Array([1]),
  );
}

function pendingBundle(dispose: () => void): PendingBundlePublication {
  return {
    profileId: profile.id,
    bundle: {
      source: {
        repository: 'https://example.invalid/tests.git', commit: '1'.repeat(40),
        bundlePath: 'bundle', verification: 'CLIENT_ASSERTED',
      },
      fileManifest: [], archivePath: retainedArchivePath,
      dispose: async () => { dispose(); },
    },
    receipt: {
      receiptId: 'vr-1', archiveDigest: `sha256:${'a'.repeat(64)}`,
      bundleContentDigest: `sha256:${'b'.repeat(64)}`, scenarioId: 'test', scenarioName: 'Test scenario',
    },
  };
}

async function assertContractCode(promise: Promise<unknown>, code: string): Promise<void> {
  await assert.rejects(promise, (error: unknown) =>
    error instanceof ConnectionContractError && error.code === code);
}
