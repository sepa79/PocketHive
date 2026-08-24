import assert from 'node:assert/strict';
import test from 'node:test';

import { ConnectionContractError } from '../connection/contracts';
import { GitScenarioDiscoveryPort } from '../scenarios/gitScenarioBundleDiscovery';
import {
  assertRepositoryScenarioFileWritable,
  RepositoryScenarioCandidateRegistry,
  resolveRepositoryScenarioCandidate,
  resolveRepositoryScenarioFile,
  scanRepositoryScenarios,
} from '../webview/repositoryScenarios';

test('repository editing requires an explicitly writable worktree file', async () => {
  const calls: Array<{ path: string; mode: number }> = [];
  const writable = await assertRepositoryScenarioFileWritable('/workspace/scenarios/smoke/scenario.yaml',
    async (path, mode) => { calls.push({ path, mode }); });
  assert.equal(writable, '/workspace/scenarios/smoke/scenario.yaml');
  assert.deepEqual(calls, [{ path: '/workspace/scenarios/smoke/scenario.yaml', mode: 2 }]);

  await assert.rejects(assertRepositoryScenarioFileWritable(
    '/workspace/scenarios/smoke/scenario.yaml',
    async () => { throw new Error('EACCES with sensitive host detail'); },
  ), (error: unknown) => error instanceof ConnectionContractError
    && error.code === 'REPOSITORY_SCENARIO_FILE_NOT_WRITABLE'
    && error.message.includes('The selected worktree file is not writable; repair its host ownership or permissions')
    && !error.message.includes('/workspace'));
});

test('repository scan exposes explicit no-workspace and untrusted states without invoking Git', async () => {
  let calls = 0;
  const discovery: GitScenarioDiscoveryPort = {
    discover: async () => {
      calls += 1;
      throw new Error('must not run');
    },
  };
  const registry = new RepositoryScenarioCandidateRegistry(sequenceIds());

  assert.deepEqual(await scanRepositoryScenarios(false, [], discovery, registry), {
    state: 'NO_WORKSPACE', repositories: [], failures: [],
  });
  assert.deepEqual(await scanRepositoryScenarios(false, [
    { name: 'project', directory: '/workspace/project' },
  ], discovery, registry), {
    state: 'UNTRUSTED', repositories: [], failures: [],
  });
  assert.equal(calls, 0);
});

test('repository scan projects opaque candidate ids and resolves them only in the extension host', async () => {
  const discovery: GitScenarioDiscoveryPort = {
    discover: async () => ({
      repositoryRoot: '/workspace/project',
      commit: 'a'.repeat(40),
      candidates: [{
        bundlePath: 'scenarios/bundles/mixed-smoke',
        directory: '/workspace/project/scenarios/bundles/mixed-smoke',
        files: ['scenario.yaml', 'templates/http/request.yaml'],
      }],
    }),
  };
  const registry = new RepositoryScenarioCandidateRegistry(sequenceIds());
  const view = await scanRepositoryScenarios(true, [
    { name: 'project', directory: '/workspace/project' },
  ], discovery, registry);

  assert.deepEqual(view, {
    state: 'SCANNED',
    repositories: [{
      workspaceName: 'project',
      commit: 'a'.repeat(40),
      candidates: [{
        candidateId: 'candidate-1',
        bundlePath: 'scenarios/bundles/mixed-smoke',
        files: ['scenario.yaml', 'templates/http/request.yaml'],
      }],
    }],
    failures: [],
  });
  assert.doesNotMatch(JSON.stringify(view), /\/workspace\/project/);
  assert.deepEqual(registry.resolve('candidate-1'), {
    repositoryRoot: '/workspace/project',
    bundlePath: 'scenarios/bundles/mixed-smoke',
    commit: 'a'.repeat(40),
  });
  assert.deepEqual(registry.resolve(' candidate-1 '), {
    repositoryRoot: '/workspace/project',
    bundlePath: 'scenarios/bundles/mixed-smoke',
    commit: 'a'.repeat(40),
  });
  assert.equal(resolveRepositoryScenarioFile(true, 'candidate-1', 'scenario.yaml', registry),
    '/workspace/project/scenarios/bundles/mixed-smoke/scenario.yaml');
  assert.equal(resolveRepositoryScenarioFile(true, 'candidate-1', ' templates/http/request.yaml ', registry),
    '/workspace/project/scenarios/bundles/mixed-smoke/templates/http/request.yaml');
  assert.equal(resolveRepositoryScenarioFile(true, ' candidate-1 ', 'scenario.yaml', registry),
    '/workspace/project/scenarios/bundles/mixed-smoke/scenario.yaml');
  assert.throws(() => resolveRepositoryScenarioFile(true, 'candidate-1', '../secret', registry),
    (error: unknown) => error instanceof ConnectionContractError
      && error.code === 'REPOSITORY_SCENARIO_FILE_UNKNOWN'
      && error.message.includes('Refresh Repository scenarios and select an exact committed file'));
  assert.throws(() => resolveRepositoryScenarioFile(true, 'candidate-1', 'not-listed.yaml', registry),
    /REPOSITORY_SCENARIO_FILE_UNKNOWN/);
});

test('candidate validation rechecks workspace trust and clears retained references on failure', async () => {
  const registry = new RepositoryScenarioCandidateRegistry(sequenceIds());
  registry.rebuild([{
    workspaceName: 'project', repositoryRoot: '/workspace/project', commit: 'a'.repeat(40),
    candidates: [{
      bundlePath: 'scenarios/smoke', directory: '/workspace/project/scenarios/smoke', files: ['scenario.yaml'],
    }],
  }]);

  assert.throws(() => resolveRepositoryScenarioCandidate(false, 'candidate-1', registry),
    (error: unknown) => error instanceof ConnectionContractError
      && error.code === 'WORKSPACE_TRUST_REQUIRED'
      && error.message.includes('Trust this workspace before validating Git content'));
  assert.throws(() => resolveRepositoryScenarioCandidate(true, 'candidate-1', registry),
    /REPOSITORY_SCENARIO_CANDIDATE_UNKNOWN/);
});

test('local file editing rechecks workspace trust and clears retained paths on failure', () => {
  const registry = new RepositoryScenarioCandidateRegistry(sequenceIds());
  registry.rebuild([{
    workspaceName: 'project', repositoryRoot: '/workspace/project', commit: 'a'.repeat(40),
    candidates: [{
      bundlePath: 'scenarios/smoke', directory: '/workspace/project/scenarios/smoke', files: ['scenario.yaml'],
    }],
  }]);

  assert.throws(() => resolveRepositoryScenarioFile(false, 'candidate-1', 'scenario.yaml', registry),
    (error: unknown) => error instanceof ConnectionContractError
      && error.code === 'WORKSPACE_TRUST_REQUIRED'
      && error.message.includes('Trust this workspace before editing Git content'));
  assert.throws(() => registry.resolve('candidate-1'), (error: unknown) =>
    error instanceof ConnectionContractError && error.code === 'REPOSITORY_SCENARIO_CANDIDATE_UNKNOWN');
});

test('a new scan invalidates prior candidate ids and unknown ids fail closed', async () => {
  const registry = new RepositoryScenarioCandidateRegistry(sequenceIds());
  const discovery: GitScenarioDiscoveryPort = {
    discover: async () => ({
      repositoryRoot: '/workspace/project', commit: 'a'.repeat(40),
      candidates: [{
        bundlePath: 'scenarios/smoke', directory: '/workspace/project/scenarios/smoke', files: ['scenario.yaml'],
      }],
    }),
  };
  await scanRepositoryScenarios(true, [{ name: 'project', directory: '/workspace/project' }], discovery, registry);
  await scanRepositoryScenarios(true, [], discovery, registry);

  assert.throws(() => registry.resolve('candidate-1'), (error: unknown) =>
    error instanceof ConnectionContractError
      && error.code === 'REPOSITORY_SCENARIO_CANDIDATE_UNKNOWN'
      && error.message.includes('Refresh Repository scenarios and select an exact candidate'));
  assert.throws(() => registry.resolve('   '), (error: unknown) =>
    error instanceof ConnectionContractError && error.code === 'REPOSITORY_SCENARIO_CANDIDATE_UNKNOWN');
});

test('rebuilding the candidate registry invalidates the previous projection', () => {
  const registry = new RepositoryScenarioCandidateRegistry(sequenceIds());
  const first = registry.rebuild([{
    workspaceName: 'first', repositoryRoot: '/workspace/first', commit: 'a'.repeat(40),
    candidates: [{
      bundlePath: 'scenarios/first', directory: '/workspace/first/scenarios/first', files: ['scenario.yaml'],
    }],
  }]);
  const second = registry.rebuild([{
    workspaceName: 'second', repositoryRoot: '/workspace/second', commit: 'b'.repeat(40),
    candidates: [{
      bundlePath: 'scenarios/second', directory: '/workspace/second/scenarios/second', files: ['scenario.yaml'],
    }],
  }]);

  assert.equal(first[0].candidates[0].candidateId, 'candidate-1');
  assert.equal(second[0].candidates[0].candidateId, 'candidate-2');
  assert.throws(() => registry.resolve('candidate-1'), /REPOSITORY_SCENARIO_CANDIDATE_UNKNOWN/);
  assert.deepEqual(registry.resolve('candidate-2'), {
    repositoryRoot: '/workspace/second', bundlePath: 'scenarios/second', commit: 'b'.repeat(40),
  });
});

function sequenceIds(): () => string {
  let sequence = 0;
  return () => `candidate-${++sequence}`;
}
