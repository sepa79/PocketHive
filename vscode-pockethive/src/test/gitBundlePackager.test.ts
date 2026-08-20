import assert from 'node:assert/strict';
import { execFile as execFileCallback } from 'node:child_process';
import { mkdtemp, mkdir, readFile, rmdir, rm, stat, unlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { promisify } from 'node:util';
import test from 'node:test';

import { ConnectionContractError } from '../connection/contracts';
import {
  ArchiveFileOperations,
  GitBundleLimits,
  GitBundlePackager,
  GitCommand,
  persistArchive,
} from '../scenarios/gitBundlePackager';

const execFile = promisify(execFileCallback);

test('packages every regular file from the selected committed Git tree with exact bytes', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pockethive-git-bundle-test-'));
  const bundle = join(root, 'scenarios', 'bundles', 'mixed-smoke');
  try {
    await mkdir(bundle, { recursive: true });
    await writeFile(join(bundle, 'scenario.yaml'), 'name: mixed-smoke\n', 'utf8');
    await writeFile(join(bundle, 'query.sql'), Buffer.from([0x53, 0x45, 0x4c, 0x45, 0x43, 0x54, 0x20, 0x31, 0x3b, 0x0a]));
    await writeFile(join(bundle, 'run.sh'), '#!/bin/sh\nprintf "ok\\n"\n', { mode: 0o755 });
    await git(root, 'init');
    await git(root, 'config', 'user.email', 'test@example.invalid');
    await git(root, 'config', 'user.name', 'PocketHive Test');
    await git(root, 'remote', 'add', 'origin', 'https://example.invalid/qa/pockethive-tests.git');
    await git(root, 'add', '.');
    await git(root, 'commit', '-m', 'fixture');
    await writeFile(join(bundle, 'query.sql'), 'uncommitted bytes must not be packaged\n', 'utf8');

    const prepared = await new GitBundlePackager().package(bundle);
    try {
      assert.equal(prepared.source.repository, 'https://example.invalid/qa/pockethive-tests.git');
      assert.match(prepared.source.commit, /^[0-9a-f]{40}$/);
      assert.equal(prepared.source.bundlePath, 'scenarios/bundles/mixed-smoke');
      assert.equal(prepared.source.verification, 'CLIENT_ASSERTED');
      assert.deepEqual(prepared.fileManifest.map(file => [file.path, file.byteCount]), [
        ['query.sql', 10],
        ['run.sh', 24],
        ['scenario.yaml', 18],
      ]);
      assert.ok(prepared.fileManifest.every(file => /^sha256:[0-9a-f]{64}$/.test(file.sha256)));
      assert.ok((await readFile(prepared.archivePath)).byteLength > 0);
      assert.equal(prepared.archivePath.startsWith(root), false);
      assert.equal((await stat(dirname(prepared.archivePath))).mode & 0o777, 0o700);
      assert.equal((await stat(prepared.archivePath)).mode & 0o777, 0o600);
    } finally {
      await prepared.dispose();
    }
    await assert.rejects(readFile(prepared.archivePath), /ENOENT/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('fails explicitly when source identity is absent or selected content is not a committed directory', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pockethive-git-bundle-test-'));
  const bundle = join(root, 'bundle');
  try {
    await mkdir(bundle);
    await writeFile(join(bundle, 'scenario.yaml'), 'name: sample\n', 'utf8');
    await git(root, 'init');
    await git(root, 'config', 'user.email', 'test@example.invalid');
    await git(root, 'config', 'user.name', 'PocketHive Test');
    await git(root, 'add', '.');
    await git(root, 'commit', '-m', 'fixture');

    await assert.rejects(new GitBundlePackager().package(bundle), /GIT_REMOTE_ORIGIN_REQUIRED/);
    await git(root, 'remote', 'add', 'origin', 'https://example.invalid/qa/tests.git');
    await mkdir(join(root, 'uncommitted'));
    await assert.rejects(new GitBundlePackager().package(join(root, 'uncommitted')), /GIT_BUNDLE_TREE_REQUIRED/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('maps Git identity and tree failures to stable contract codes', async () => {
  await withSelectedDirectory(async ({ root, selected }) => {
    await assertCode(new GitBundlePackager(fakeGit(root, { fail: 'rev-parse --show-toplevel' })).package(selected),
      'GIT_REPOSITORY_REQUIRED');
    await assertCode(new GitBundlePackager(fakeGit(root, { fail: 'remote get-url origin' })).package(selected),
      'GIT_REMOTE_ORIGIN_REQUIRED');
    await assertCode(new GitBundlePackager(fakeGit(root, { fail: 'rev-parse HEAD' })).package(selected),
      'GIT_COMMIT_REQUIRED');
    await assertCode(new GitBundlePackager(fakeGit(root, { commit: 'not-a-commit' })).package(selected),
      'GIT_COMMIT_INVALID');
    await assertCode(new GitBundlePackager(fakeGit(root, { commit: `prefix${'a'.repeat(64)}` })).package(selected),
      'GIT_COMMIT_INVALID');
    await assertCode(new GitBundlePackager(fakeGit(root, { commit: `${'a'.repeat(40)}suffix` })).package(selected),
      'GIT_COMMIT_INVALID');
    await assertCode(new GitBundlePackager(fakeGit(root, { commit: `${'a'.repeat(64)}suffix` })).package(selected),
      'GIT_COMMIT_INVALID');
    await assertCode(new GitBundlePackager(fakeGit(root, { fail: 'ls-tree -r -z --full-tree HEAD:bundle' }))
      .package(selected), 'GIT_BUNDLE_TREE_REQUIRED');
    await assertCode(new GitBundlePackager(fakeGit(root, { tree: '' })).package(selected),
      'GIT_BUNDLE_FILES_REQUIRED');
    await assertCode(new GitBundlePackager(fakeGit(root, { repository: '   ' })).package(selected),
      'GIT_COMMAND_OUTPUT_REQUIRED');
    const nestedRoot = join(selected, 'nested-repository');
    await mkdir(nestedRoot);
    await assertCode(new GitBundlePackager(fakeGit(root, { repositoryRoot: nestedRoot })).package(selected),
      'GIT_BUNDLE_PATH_INVALID');
    const siblingRoot = join(root, 'sibling-repository');
    await mkdir(siblingRoot);
    await assertCode(new GitBundlePackager(fakeGit(root, { repositoryRoot: siblingRoot })).package(selected),
      'GIT_BUNDLE_PATH_INVALID');
    await assertCode(new GitBundlePackager(fakeGit(root, { repositoryRoot: selected })).package(selected),
      'GIT_BUNDLE_PATH_INVALID');
  });
});

test('accepts only regular committed blobs with safe relative paths and valid object ids', async () => {
  await withSelectedDirectory(async ({ root, selected }) => {
    const invalidRecords = [
      `120000 blob ${'a'.repeat(40)}\tlink`,
      `100644 tree ${'a'.repeat(40)}\tdirectory`,
      '100644 blob invalid\tfile.yaml',
      `100644 blob ${'a'.repeat(40)}\t/absolute.yaml`,
      `100644 blob ${'a'.repeat(40)}\tdir\\file.yaml`,
      `100644 blob ${'a'.repeat(40)}\tdir//file.yaml`,
      `100644 blob ${'a'.repeat(40)}\tdir/./file.yaml`,
      `100644 blob ${'a'.repeat(40)}\tdir/../file.yaml`,
      `100644 blob ${'a'.repeat(40)}suffix\tfile.yaml`,
      `100644 blob prefix${'a'.repeat(40)}\tfile.yaml`,
      `100644 blob prefix${'a'.repeat(64)}\tfile.yaml`,
      `100644 blob ${'a'.repeat(64)}suffix\tfile.yaml`,
      '100644 blob\tfile.yaml',
      `100644 blob ${'a'.repeat(40)} file-without-tab`,
    ];
    for (const tree of invalidRecords) {
      await assertCode(new GitBundlePackager(fakeGit(root, { tree })).package(selected),
        'GIT_BUNDLE_ENTRY_INVALID');
    }

    const objectId = 'b'.repeat(64);
    const prepared = await new GitBundlePackager(fakeGit(root, {
      commit: 'c'.repeat(64), tree: `100755 blob ${objectId}\tdir/run.sh`,
    })).package(selected);
    try {
      assert.equal(prepared.source.commit, 'c'.repeat(64));
      assert.deepEqual(prepared.fileManifest.map(entry => entry.path), ['dir/run.sh']);
    } finally {
      await prepared.dispose();
    }
  });
});

test('enforces explicit file, expanded-byte, archive-byte, and command-buffer limits', async () => {
  await withSelectedDirectory(async ({ root, selected }) => {
    const objectA = 'a'.repeat(40);
    const objectB = 'b'.repeat(40);
    const twoFiles = `100644 blob ${objectA}\ta.yaml\0` + `100644 blob ${objectB}\tb.yaml`;
    await assertCode(new GitBundlePackager(fakeGit(root, { tree: twoFiles }), limits({ files: 1 }))
      .package(selected), 'GIT_BUNDLE_FILE_LIMIT_EXCEEDED');
    await assertCode(new GitBundlePackager(fakeGit(root, {
      tree: twoFiles, blobs: { [objectA]: Buffer.alloc(3), [objectB]: Buffer.alloc(3) },
    }), limits({ expandedBytes: 5 })).package(selected), 'GIT_BUNDLE_EXPANDED_LIMIT_EXCEEDED');
    const exactFileLimit = await new GitBundlePackager(fakeGit(root, {}), limits({ files: 1 }))
      .package(selected);
    await exactFileLimit.dispose();
    await assertCode(new GitBundlePackager(fakeGit(root, { fail: `cat-file blob ${objectA}` }))
      .package(selected), 'GIT_BUNDLE_READ_FAILED');
    await assertCode(new GitBundlePackager(fakeGit(root, { fail: 'archive --format=zip HEAD:bundle' }))
      .package(selected), 'GIT_BUNDLE_ARCHIVE_FAILED');
    const exactLimit = await new GitBundlePackager(fakeGit(root, {
      blobs: { [objectA]: Buffer.alloc(5) },
    }), limits({ expandedBytes: 5 })).package(selected);
    await exactLimit.dispose();
    await assertCode(new GitBundlePackager(fakeGit(root, { archive: Buffer.alloc(5) }),
      limits({ archiveBytes: 4 })).package(selected), 'GIT_BUNDLE_ARCHIVE_LIMIT_EXCEEDED');

    let observedArchiveBuffer = 0;
    let observedBlobBuffer = 0;
    const checkingGit: GitCommand = async (cwd, args, maxBytes) => {
      if (args[0] === 'archive') observedArchiveBuffer = maxBytes ?? 0;
      if (args[0] === 'cat-file' && args[1] === 'blob') observedBlobBuffer = maxBytes ?? 0;
      return fakeGit(root, {})(cwd, args, maxBytes);
    };
    const prepared = await new GitBundlePackager(checkingGit, limits({ archiveBytes: 4 })).package(selected);
    try {
      assert.equal(observedArchiveBuffer, 5);
      assert.equal(observedBlobBuffer, 129);
    } finally {
      await prepared.dispose();
    }

    const reversedTree = `100644 blob ${objectA}\tz.yaml\0` + `100644 blob ${objectB}\ta.yaml`;
    const sorted = await new GitBundlePackager(fakeGit(root, { tree: reversedTree })).package(selected);
    try {
      assert.deepEqual(sorted.fileManifest.map(entry => entry.path), ['a.yaml', 'z.yaml']);
    } finally {
      await sorted.dispose();
    }
  });
});

test('dispose tolerates independently removed owned files and directories and remains idempotent', async () => {
  await withSelectedDirectory(async ({ root, selected }) => {
    const first = await new GitBundlePackager(fakeGit(root, {})).package(selected);
    await unlink(first.archivePath);
    await first.dispose();
    await first.dispose();

    const second = await new GitBundlePackager(fakeGit(root, {})).package(selected);
    const temporaryDirectory = dirname(second.archivePath);
    await unlink(second.archivePath);
    await rmdir(temporaryDirectory);
    await second.dispose();

    const third = await new GitBundlePackager(fakeGit(root, {})).package(selected);
    const blockingFile = join(dirname(third.archivePath), 'still-present.txt');
    await writeFile(blockingFile, 'block removal');
    await assert.rejects(third.dispose(), (error: unknown) =>
      (error as NodeJS.ErrnoException).code === 'ENOTEMPTY');
    await unlink(blockingFile);
    await third.dispose();

    const fourth = await new GitBundlePackager(fakeGit(root, {})).package(selected);
    const fourthDirectory = dirname(fourth.archivePath);
    await unlink(fourth.archivePath);
    await mkdir(fourth.archivePath);
    await assert.rejects(fourth.dispose(), (error: unknown) =>
      (error as NodeJS.ErrnoException).code === 'EISDIR');
    await rmdir(fourth.archivePath);
    await rmdir(fourthDirectory);
  });
});

test('archive persistence cleans partial state when secure creation fails', async () => {
  const calls: string[] = [];
  const failure = new Error('simulated archive write failure');
  const ownedDirectory = join('owned-temp', 'owned-test-directory');
  const archivePath = join(ownedDirectory, 'bundle.zip');
  const files: ArchiveFileOperations = {
    createTemporaryDirectory: async prefix => {
      calls.push(`create:${prefix}`);
      return ownedDirectory;
    },
    write: async (path, _bytes, mode) => {
      calls.push(`write:${path}:${mode.toString(8)}`);
      throw failure;
    },
    unlink: async path => { calls.push(`unlink:${path}`); },
    rmdir: async path => { calls.push(`rmdir:${path}`); },
  };

  await assert.rejects(persistArchive(Buffer.from('archive'), files), error => error === failure);
  assert.deepEqual(calls.slice(1), [
    `write:${archivePath}:600`,
    `unlink:${archivePath}`,
    `rmdir:${ownedDirectory}`,
  ]);
  assert.match(calls[0], /^create:.*pockethive-bundle-$/);
});

interface FakeGitOptions {
  readonly fail?: string;
  readonly repositoryRoot?: string;
  readonly repository?: string;
  readonly commit?: string;
  readonly tree?: string;
  readonly blobs?: Readonly<Record<string, Buffer>>;
  readonly archive?: Buffer;
}

function fakeGit(root: string, options: FakeGitOptions): GitCommand {
  const objectId = 'a'.repeat(40);
  return async (_cwd, args) => {
    const command = args.join(' ');
    if (command === options.fail) throw new Error('simulated Git failure');
    if (command === 'rev-parse --show-toplevel') return Buffer.from(options.repositoryRoot ?? root);
    if (command === 'cat-file -e HEAD:bundle') return Buffer.alloc(0);
    if (command === 'remote get-url origin') {
      return Buffer.from(options.repository ?? 'https://example.invalid/qa/tests.git');
    }
    if (command === 'rev-parse HEAD') return Buffer.from(options.commit ?? '1'.repeat(40));
    if (command === 'ls-tree -r -z --full-tree HEAD:bundle') {
      return Buffer.from(`${options.tree ?? `100644 blob ${objectId}\tscenario.yaml`}\0`);
    }
    if (args[0] === 'cat-file' && args[1] === 'blob') {
      return options.blobs?.[args[2]] ?? Buffer.from('name: test\n');
    }
    if (args[0] === 'archive') return options.archive ?? Buffer.from([0x50, 0x4b, 0x03, 0x04]);
    throw new Error(`Unexpected Git command: ${command}`);
  };
}

function limits(overrides: Partial<GitBundleLimits>): GitBundleLimits {
  return { archiveBytes: 16, expandedBytes: 128, files: 10, ...overrides };
}

async function withSelectedDirectory(
  action: (paths: { root: string; selected: string }) => Promise<void>,
): Promise<void> {
  const root = await mkdtemp(join(tmpdir(), 'pockethive-git-bundle-fake-'));
  const selected = join(root, 'bundle');
  try {
    await mkdir(selected);
    await action({ root, selected });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

async function assertCode(promise: Promise<unknown>, code: string): Promise<void> {
  await assert.rejects(promise, (error: unknown) =>
    error instanceof ConnectionContractError && error.code === code);
}

async function git(cwd: string, ...args: string[]): Promise<void> {
  await execFile('git', args, { cwd, encoding: 'utf8' });
}
