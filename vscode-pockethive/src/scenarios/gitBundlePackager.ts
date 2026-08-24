import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { mkdtemp, realpath, rmdir, unlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { isAbsolute, join, relative, sep } from 'node:path';

import { ConnectionContractError } from '../connection/contracts';

const MAX_ARCHIVE_BYTES = 16 * 1024 * 1024;
const MAX_EXPANDED_BYTES = 134_217_728;
const MAX_FILES = 1_000;

export interface BundleSourceMetadata {
  readonly repository: string;
  readonly commit: string;
  readonly bundlePath: string;
  readonly verification: 'CLIENT_ASSERTED';
}

export interface BundleFileManifestEntry {
  readonly path: string;
  readonly byteCount: number;
  readonly sha256: string;
}

export interface PreparedCommittedBundle {
  readonly source: BundleSourceMetadata;
  readonly fileManifest: readonly BundleFileManifestEntry[];
  readonly archivePath: string;
  dispose(): Promise<void>;
}

export interface CommittedBundleReference {
  readonly repositoryRoot: string;
  readonly bundlePath: string;
  readonly commit: string;
}

export type GitCommand = (cwd: string, args: readonly string[], maxBytes?: number) => Promise<Buffer>;

export interface GitBundleLimits {
  readonly archiveBytes: number;
  readonly expandedBytes: number;
  readonly files: number;
}

export interface ArchiveFileOperations {
  createTemporaryDirectory(prefix: string): Promise<string>;
  write(path: string, bytes: Buffer, mode: number): Promise<void>;
  unlink(path: string): Promise<void>;
  rmdir(path: string): Promise<void>;
}

export interface OwnedArchive {
  readonly archivePath: string;
  dispose(): Promise<void>;
}

const DEFAULT_LIMITS: GitBundleLimits = Object.freeze({
  archiveBytes: MAX_ARCHIVE_BYTES,
  expandedBytes: MAX_EXPANDED_BYTES,
  files: MAX_FILES,
});

export class GitBundlePackager {
  constructor(
    private readonly git: GitCommand = runGit,
    private readonly limits: GitBundleLimits = DEFAULT_LIMITS,
  ) {}

  async package(source: string | CommittedBundleReference): Promise<PreparedCommittedBundle> {
    let root: string;
    let bundlePath: string;
    if (typeof source === 'string') {
      const selected = await realpath(source);
      const repositoryRoot = await this.requiredCommand(selected, ['rev-parse', '--show-toplevel'],
        'GIT_REPOSITORY_REQUIRED');
      root = await realpath(text(repositoryRoot));
      const relativePath = relative(root, selected);
      bundlePath = requiredBundlePath(relativePath.split(sep).join('/'));
    } else {
      bundlePath = requiredBundlePath(source.bundlePath);
      requiredCommit(source.commit);
      try {
        root = await realpath(source.repositoryRoot);
      } catch {
        throw contract('GIT_REPOSITORY_REQUIRED');
      }
    }
    const commit = requiredCommit(text(await this.requiredCommand(root, ['rev-parse', 'HEAD'],
      'GIT_COMMIT_REQUIRED')));
    if (typeof source !== 'string' && source.commit !== commit) {
      throw contract('GIT_REPOSITORY_HEAD_CHANGED');
    }
    await this.requiredCommand(root, ['cat-file', '-e', `${commit}:${bundlePath}`],
      'GIT_BUNDLE_TREE_REQUIRED');
    const repository = text(await this.requiredCommand(root, ['remote', 'get-url', 'origin'],
      'GIT_REMOTE_ORIGIN_REQUIRED'));
    const tree = await this.requiredCommand(root,
      ['ls-tree', '-r', '-z', '--full-tree', `${commit}:${bundlePath}`], 'GIT_BUNDLE_TREE_REQUIRED');
    const entries = parseTree(tree);
    if (entries.length === 0) throw contract('GIT_BUNDLE_FILES_REQUIRED');
    if (entries.length > this.limits.files) throw contract('GIT_BUNDLE_FILE_LIMIT_EXCEEDED');

    let expandedBytes = 0;
    const fileManifest: BundleFileManifestEntry[] = [];
    for (const entry of entries) {
      const bytes = await this.requiredCommand(root, ['cat-file', 'blob', entry.objectId],
        'GIT_BUNDLE_READ_FAILED', this.limits.expandedBytes + 1);
      expandedBytes += bytes.byteLength;
      if (expandedBytes > this.limits.expandedBytes) throw contract('GIT_BUNDLE_EXPANDED_LIMIT_EXCEEDED');
      fileManifest.push({
        path: entry.path,
        byteCount: bytes.byteLength,
        sha256: `sha256:${createHash('sha256').update(bytes).digest('hex')}`,
      });
    }
    fileManifest.sort((left, right) => left.path.localeCompare(right.path, 'en'));

    const archive = await this.requiredCommand(root,
      ['archive', '--format=zip', `${commit}:${bundlePath}`], 'GIT_BUNDLE_ARCHIVE_FAILED',
      this.limits.archiveBytes + 1);
    if (archive.byteLength > this.limits.archiveBytes) throw contract('GIT_BUNDLE_ARCHIVE_LIMIT_EXCEEDED');
    const ownedArchive = await persistArchive(archive);
    return Object.freeze({
      source: Object.freeze({ repository, commit, bundlePath, verification: 'CLIENT_ASSERTED' as const }),
      fileManifest: Object.freeze(fileManifest.map(file => Object.freeze(file))),
      archivePath: ownedArchive.archivePath,
      dispose: ownedArchive.dispose,
    });
  }

  private async requiredCommand(
    cwd: string,
    args: readonly string[],
    code: string,
    maxBytes = 1_048_576,
  ): Promise<Buffer> {
    try {
      return await this.git(cwd, args, maxBytes);
    } catch {
      throw contract(code);
    }
  }
}

const NODE_ARCHIVE_FILES: ArchiveFileOperations = Object.freeze({
  createTemporaryDirectory: (prefix: string) => mkdtemp(prefix),
  write: async (path: string, bytes: Buffer, mode: number) => writeFile(path, bytes, { mode }),
  unlink: (path: string) => unlink(path),
  rmdir: (path: string) => rmdir(path),
});

export async function persistArchive(
  archive: Buffer,
  files: ArchiveFileOperations = NODE_ARCHIVE_FILES,
): Promise<OwnedArchive> {
  const temporaryDirectory = await files.createTemporaryDirectory(join(tmpdir(), 'pockethive-bundle-'));
  const archivePath = join(temporaryDirectory, 'bundle.zip');
  try {
    await files.write(archivePath, archive, 0o600);
  } catch (error) {
    await files.unlink(archivePath).catch(() => undefined);
    await files.rmdir(temporaryDirectory).catch(() => undefined);
    throw error;
  }
  return Object.freeze({
    archivePath,
    dispose: async () => {
      await files.unlink(archivePath).catch(error => {
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
      });
      await files.rmdir(temporaryDirectory).catch(error => {
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
      });
    },
  });
}

interface TreeEntry {
  readonly objectId: string;
  readonly path: string;
}

function parseTree(output: Buffer): TreeEntry[] {
  return output.toString('utf8').split('\0').filter(Boolean).map(record => {
    const tab = record.indexOf('\t');
    if (tab === -1) throw contract('GIT_BUNDLE_ENTRY_INVALID');
    const metadata = record.slice(0, tab).split(' ');
    const path = record.slice(tab + 1);
    const [mode, type, objectId] = metadata;
    if ((mode !== '100644' && mode !== '100755') || type !== 'blob'
        || !/^[0-9a-f]{40}$|^[0-9a-f]{64}$/.test(String(objectId))
        || !safePath(path)) {
      throw contract('GIT_BUNDLE_ENTRY_INVALID');
    }
    return { objectId, path };
  });
}

function safePath(value: string): boolean {
  return Boolean(value) && !value.includes('\\')
    && !value.split('/').some(segment => segment === '' || segment === '.' || segment === '..');
}

function requiredBundlePath(value: string): string {
  if (value.trim() !== value || isAbsolute(value) || !safePath(value)) {
    throw contract('GIT_BUNDLE_PATH_INVALID');
  }
  return value;
}

function requiredCommit(value: string): string {
  if (!/^[0-9a-f]{40}$|^[0-9a-f]{64}$/.test(value)) throw contract('GIT_COMMIT_INVALID');
  return value;
}

function text(value: Buffer): string {
  const result = value.toString('utf8').trim();
  if (!result) throw contract('GIT_COMMAND_OUTPUT_REQUIRED');
  return result;
}

function contract(code: string): ConnectionContractError {
  return new ConnectionContractError(code, code);
}

export function runGit(cwd: string, args: readonly string[], maxBytes = 1_048_576): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    execFile('git', [...args], {
      cwd,
      // Stryker disable next-line StringLiteral: Buffer overload selection is compile-time adapter wiring.
      encoding: 'buffer',
      maxBuffer: maxBytes,
      // Stryker disable next-line BooleanLiteral: Windows UI suppression is not observable on Linux CI.
      windowsHide: true,
    }, (error, stdout) => error ? reject(error) : resolve(stdout));
  });
}
