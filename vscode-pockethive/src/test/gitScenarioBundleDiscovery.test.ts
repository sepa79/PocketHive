import assert from 'node:assert/strict';
import { execFile as execFileCallback } from 'node:child_process';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import test from 'node:test';

import { ConnectionContractError } from '../connection/contracts';
import { GitCommand } from '../scenarios/gitBundlePackager';
import {
  discoverWorkspaceScenarioBundles,
  GitScenarioBundleDiscovery,
  GitScenarioDiscoveryPort,
  WorkspaceScenarioFolder,
} from '../scenarios/gitScenarioBundleDiscovery';

const execFile = promisify(execFileCallback);

test('discovers only canonical Scenario Bundle directories committed at HEAD', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pockethive-git-scenario-discovery-'));
  try {
    await scenario(root, 'scenarios/bundles/mixed-smoke');
    await scenario(root, 'scenarios/db-query-postgres-smoke');
    await scenario(root, 'examples/not-a-bundle');
    await writeFile(join(root, 'scenarios', 'README.md'), 'not a bundle\n');
    await git(root, 'init');
    await git(root, 'config', 'user.email', 'test@example.invalid');
    await git(root, 'config', 'user.name', 'PocketHive Test');
    await git(root, 'add', '.');
    await git(root, 'commit', '-m', 'committed scenarios');
    await scenario(root, 'scenarios/bundles/uncommitted');

    const result = await new GitScenarioBundleDiscovery().discover(root);

    assert.equal(result.repositoryRoot, root);
    assert.match(result.commit, /^[0-9a-f]{40}$/);
    assert.deepEqual(result.candidates.map(candidate => candidate.bundlePath), [
      'scenarios/bundles/mixed-smoke',
      'scenarios/db-query-postgres-smoke',
    ]);
    assert.deepEqual(result.candidates.map(candidate => candidate.directory), [
      join(root, 'scenarios', 'bundles', 'mixed-smoke'),
      join(root, 'scenarios', 'db-query-postgres-smoke'),
    ]);
    assert.deepEqual(result.candidates.map(candidate => candidate.files), [
      ['scenario.yaml'],
      ['scenario.yaml'],
    ]);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('discovery uses one bounded Git contract and rejects invalid identity and paths', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pockethive-git-scenario-discovery-'));
  try {
    const calls: Array<{ args: readonly string[]; maxBytes?: number }> = [];
    const command: GitCommand = async (_cwd, args, maxBytes) => {
      calls.push({ args, maxBytes });
      if (args.join(' ') === 'rev-parse --show-toplevel') return Buffer.from(root);
      if (args.join(' ') === 'rev-parse HEAD') return Buffer.from('a'.repeat(40));
      if (args[0] === 'ls-tree') {
        return Buffer.from('scenarios/bundles/smoke/scenario.yaml\0scenarios/bundles/smoke/query.sql\0');
      }
      throw new Error('unexpected command');
    };
    const result = await new GitScenarioBundleDiscovery(command).discover(root);
    assert.deepEqual(result.candidates.map(candidate => candidate.bundlePath), ['scenarios/bundles/smoke']);
    assert.deepEqual(result.candidates[0].files, ['query.sql', 'scenario.yaml']);
    assert.deepEqual(calls.map(call => call.args), [
      ['rev-parse', '--show-toplevel'],
      ['rev-parse', 'HEAD'],
      ['ls-tree', '-r', '-z', '--name-only', '--full-tree', 'HEAD', '--', 'scenarios'],
    ]);
    assert.equal(calls[2].maxBytes, 262_145);

    for (const commit of [
      'not-a-sha', `prefix${'a'.repeat(40)}`, `${'a'.repeat(40)}suffix`,
      `prefix${'a'.repeat(64)}`, `${'a'.repeat(64)}suffix`,
    ]) {
      await assertCode(discoverWith(root, { commit }), 'GIT_COMMIT_INVALID');
    }
    assert.equal((await discoverWith(root, { commit: 'b'.repeat(64) })).commit, 'b'.repeat(64));
    for (const tree of [
      'scenarios/bundles/../escape/scenario.yaml\0',
      'scenarios\\bundles\\smoke\\scenario.yaml\0',
      '/scenarios/bundles/smoke/scenario.yaml\0',
      'scenarios//smoke/scenario.yaml\0',
      'scenarios/./smoke/scenario.yaml\0',
    ]) {
      await assertCode(discoverWith(root, { tree }), 'GIT_SCENARIO_PATH_INVALID');
    }
    await assertCode(discoverWith(root, { tree: Buffer.from([0xff, 0]) }), 'GIT_SCENARIO_PATH_INVALID');
    const exactPath = `scenarios/${'x'.repeat(1_000)}/scenario.yaml`;
    assert.equal(Buffer.byteLength(exactPath), 1_024);
    assert.equal((await discoverWith(root, { tree: `${exactPath}\0` })).candidates.length, 1);
    await assertCode(discoverWith(root, {
      tree: `scenarios/${'x'.repeat(1_001)}/scenario.yaml\0`,
    }), 'GIT_SCENARIO_PATH_LIMIT_EXCEEDED');
    assert.equal((await discoverWith(root, { tree: Buffer.alloc(256 * 1_024) })).candidates.length, 0);
    await assertCode(discoverWith(root, { tree: Buffer.alloc((256 * 1_024) + 1) }),
      'GIT_SCENARIO_DISCOVERY_OUTPUT_LIMIT_EXCEEDED');
    await assertCode(discoverWith(root, { rootOutput: '   ' }), 'GIT_COMMAND_OUTPUT_REQUIRED');
    await assertCode(discoverWith(root, { commit: '   ' }), 'GIT_COMMAND_OUTPUT_REQUIRED');
    await assertCode(discoverWith(root, { fail: 'rev-parse --show-toplevel' }), 'GIT_REPOSITORY_REQUIRED');
    await assertCode(discoverWith(root, { fail: 'rev-parse HEAD' }), 'GIT_COMMIT_REQUIRED');
    await assertCode(discoverWith(root, {
      fail: 'ls-tree -r -z --name-only --full-tree HEAD -- scenarios',
    }), 'GIT_SCENARIO_DISCOVERY_FAILED');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('discovery ignores non-canonical descriptors, de-duplicates bundle paths, and sorts candidates', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pockethive-git-scenario-discovery-'));
  try {
    const tree = [
      'scenarios/z-last/scenario.yaml',
      'other/bundles/ignored/scenario.yaml',
      'scenarios/bundles/smoke/not-scenario.yml',
      'scenarios/scenario.yaml',
      'scenarios/a-first/scenario.yaml',
      'scenarios/z-last/scenario.yaml',
    ].join('\0') + '\0';
    const result = await discoverWith(root, { tree });
    assert.deepEqual(result.candidates.map(candidate => candidate.bundlePath), [
      'scenarios/a-first', 'scenarios/z-last',
    ]);
    assert.deepEqual(result.candidates.map(candidate => candidate.files), [
      ['scenario.yaml'], ['scenario.yaml'],
    ]);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('committed files belong to the nearest canonical bundle and remain relative', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pockethive-git-scenario-discovery-'));
  try {
    const result = await discoverWith(root, { tree: [
      'scenarios/parent/scenario.yaml',
      'scenarios/parent/datasets/root.csv',
      'scenarios/parent/nested/scenario.yaml',
      'scenarios/parent/nested/templates/request.yaml',
    ].join('\0') + '\0' });

    assert.deepEqual(result.candidates.map(candidate => ({
      bundlePath: candidate.bundlePath,
      files: candidate.files,
    })), [{
      bundlePath: 'scenarios/parent',
      files: ['datasets/root.csv', 'scenario.yaml'],
    }, {
      bundlePath: 'scenarios/parent/nested',
      files: ['scenario.yaml', 'templates/request.yaml'],
    }]);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('candidate limit is exact and never returns a silently truncated repository', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pockethive-git-scenario-discovery-'));
  try {
    const exact = Array.from({ length: 100 }, (_, index) =>
      `scenarios/bundles/scenario-${index}/scenario.yaml`).join('\0') + '\0';
    assert.equal((await discoverWith(root, { tree: exact })).candidates.length, 100);

    const over = `${exact}scenarios/bundles/scenario-100/scenario.yaml\0`;
    await assertCode(discoverWith(root, { tree: over }), 'GIT_SCENARIO_CANDIDATE_LIMIT_EXCEEDED');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('multi-root discovery de-duplicates repositories and preserves explicit per-folder failures', async () => {
  const folders: readonly WorkspaceScenarioFolder[] = [
    { name: 'root', directory: '/workspace/repository' },
    { name: 'nested', directory: '/workspace/repository/scenarios' },
    { name: 'notes', directory: '/workspace/notes' },
  ];
  const adapter: GitScenarioDiscoveryPort = {
    discover: async directory => {
      if (directory === '/workspace/notes') {
        throw new ConnectionContractError('GIT_REPOSITORY_REQUIRED', 'GIT_REPOSITORY_REQUIRED');
      }
      return {
        repositoryRoot: '/workspace/repository',
        commit: 'a'.repeat(40),
        candidates: [{
          bundlePath: 'scenarios/bundles/smoke',
          directory: '/workspace/repository/scenarios/bundles/smoke',
          files: ['scenario.yaml'],
        }],
      };
    },
  };

  const result = await discoverWorkspaceScenarioBundles(folders, adapter);

  assert.deepEqual(result.repositories, [{
    workspaceName: 'root',
    repositoryRoot: '/workspace/repository',
    commit: 'a'.repeat(40),
    candidates: [{
      bundlePath: 'scenarios/bundles/smoke',
      directory: '/workspace/repository/scenarios/bundles/smoke',
      files: ['scenario.yaml'],
    }],
  }]);
  assert.deepEqual(result.failures, [{ workspaceName: 'notes', code: 'GIT_REPOSITORY_REQUIRED' }]);
});

test('multi-root discovery maps unknown adapter failures and rejects inconsistent duplicate HEADs', async () => {
  const folders: readonly WorkspaceScenarioFolder[] = [
    { name: 'first', directory: '/workspace/repository' },
    { name: 'second', directory: '/workspace/repository/nested' },
    { name: 'broken', directory: '/workspace/broken' },
  ];
  const adapter: GitScenarioDiscoveryPort = {
    discover: async directory => {
      if (directory.endsWith('broken')) throw new Error('sensitive detail');
      return {
        repositoryRoot: '/workspace/repository',
        commit: directory.endsWith('nested') ? 'b'.repeat(40) : 'a'.repeat(40),
        candidates: [],
      };
    },
  };

  const result = await discoverWorkspaceScenarioBundles(folders, adapter);
  assert.deepEqual(result.repositories, [{
    workspaceName: 'first', repositoryRoot: '/workspace/repository', commit: 'a'.repeat(40), candidates: [],
  }]);
  assert.deepEqual(result.failures, [
    { workspaceName: 'second', code: 'GIT_REPOSITORY_HEAD_CHANGED' },
    { workspaceName: 'broken', code: 'GIT_SCENARIO_DISCOVERY_FAILED' },
  ]);
  assert.doesNotMatch(JSON.stringify(result), /sensitive detail/);
});

interface DiscoveryGitOptions {
  readonly commit?: string;
  readonly tree?: string | Buffer;
  readonly fail?: string;
  readonly rootOutput?: string;
}

function discoverWith(root: string, options: DiscoveryGitOptions) {
  const command: GitCommand = async (_cwd, args) => {
    const exact = args.join(' ');
    if (exact === options.fail) throw new Error('simulated failure');
    if (exact === 'rev-parse --show-toplevel') return Buffer.from(options.rootOutput ?? root);
    if (exact === 'rev-parse HEAD') return Buffer.from(options.commit ?? 'a'.repeat(40));
    if (exact === 'ls-tree -r -z --name-only --full-tree HEAD -- scenarios') {
      return Buffer.from(options.tree ?? 'scenarios/bundles/smoke/scenario.yaml\0');
    }
    throw new Error(`Unexpected Git command: ${exact}`);
  };
  return new GitScenarioBundleDiscovery(command).discover(root);
}

async function scenario(root: string, path: string): Promise<void> {
  const directory = join(root, ...path.split('/'));
  await mkdir(directory, { recursive: true });
  await writeFile(join(directory, 'scenario.yaml'), `id: ${path.split('/').at(-1)}\n`, 'utf8');
}

async function assertCode(promise: Promise<unknown>, code: string): Promise<void> {
  await assert.rejects(promise, (error: unknown) =>
    error instanceof ConnectionContractError && error.code === code);
}

async function git(cwd: string, ...args: string[]): Promise<void> {
  await execFile('git', args, { cwd, encoding: 'utf8' });
}
