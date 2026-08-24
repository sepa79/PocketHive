import { realpath } from 'node:fs/promises';
import { resolve } from 'node:path';

import { ConnectionContractError } from '../connection/contracts';
import { GitCommand, runGit } from './gitBundlePackager';

const MAX_DISCOVERY_OUTPUT_BYTES = 256 * 1024;
const MAX_CANDIDATES = 100;
const MAX_REPOSITORY_PATH_BYTES = 1_024;
const SCENARIO_DESCRIPTOR = 'scenario.yaml';
const SCENARIOS_ROOT = 'scenarios';

export interface GitScenarioBundleCandidate {
  readonly bundlePath: string;
  readonly directory: string;
  readonly files: readonly string[];
}

export interface GitScenarioRepository {
  readonly repositoryRoot: string;
  readonly commit: string;
  readonly candidates: readonly GitScenarioBundleCandidate[];
}

export interface GitScenarioDiscoveryPort {
  discover(workspaceDirectory: string): Promise<GitScenarioRepository>;
}

export interface WorkspaceScenarioFolder {
  readonly name: string;
  readonly directory: string;
}

export interface WorkspaceScenarioRepository extends GitScenarioRepository {
  readonly workspaceName: string;
}

export interface WorkspaceScenarioFailure {
  readonly workspaceName: string;
  readonly code: string;
}

export interface WorkspaceScenarioDiscovery {
  readonly repositories: readonly WorkspaceScenarioRepository[];
  readonly failures: readonly WorkspaceScenarioFailure[];
}

export class GitScenarioBundleDiscovery implements GitScenarioDiscoveryPort {
  constructor(private readonly git: GitCommand = runGit) {}

  async discover(workspaceDirectory: string): Promise<GitScenarioRepository> {
    const rootOutput = await this.requiredCommand(workspaceDirectory, ['rev-parse', '--show-toplevel'],
      'GIT_REPOSITORY_REQUIRED');
    const repositoryRoot = await realpath(requiredText(rootOutput));
    const commit = requiredText(await this.requiredCommand(repositoryRoot, ['rev-parse', 'HEAD'],
      'GIT_COMMIT_REQUIRED'));
    if (!/^[0-9a-f]{40}$|^[0-9a-f]{64}$/.test(commit)) throw contract('GIT_COMMIT_INVALID');
    const tree = await this.requiredCommand(repositoryRoot,
      ['ls-tree', '-r', '-z', '--name-only', '--full-tree', 'HEAD', '--', SCENARIOS_ROOT],
      'GIT_SCENARIO_DISCOVERY_FAILED', MAX_DISCOVERY_OUTPUT_BYTES + 1);
    if (tree.byteLength > MAX_DISCOVERY_OUTPUT_BYTES) {
      throw contract('GIT_SCENARIO_DISCOVERY_OUTPUT_LIMIT_EXCEEDED');
    }
    const repositoryPaths = scenarioRepositoryPaths(tree);
    const bundlePaths = scenarioBundlePaths(repositoryPaths);
    if (bundlePaths.length > MAX_CANDIDATES) {
      throw contract('GIT_SCENARIO_CANDIDATE_LIMIT_EXCEEDED');
    }
    const filesByBundle = scenarioBundleFiles(repositoryPaths, bundlePaths);
    const candidates = bundlePaths.map(bundlePath => {
      const directory = resolve(repositoryRoot, ...bundlePath.split('/'));
      return Object.freeze({ bundlePath, directory, files: filesByBundle.get(bundlePath)! });
    });
    return Object.freeze({
      repositoryRoot,
      commit,
      candidates: Object.freeze(candidates),
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

export async function discoverWorkspaceScenarioBundles(
  folders: readonly WorkspaceScenarioFolder[],
  discovery: GitScenarioDiscoveryPort,
): Promise<WorkspaceScenarioDiscovery> {
  const repositories: WorkspaceScenarioRepository[] = [];
  const failures: WorkspaceScenarioFailure[] = [];
  const byRoot = new Map<string, WorkspaceScenarioRepository>();
  for (const folder of folders) {
    try {
      const discovered = await discovery.discover(folder.directory);
      const existing = byRoot.get(discovered.repositoryRoot);
      if (existing) {
        if (existing.commit !== discovered.commit) {
          failures.push(Object.freeze({
            workspaceName: folder.name,
            code: 'GIT_REPOSITORY_HEAD_CHANGED',
          }));
        }
        continue;
      }
      const repository = Object.freeze({ workspaceName: folder.name, ...discovered });
      byRoot.set(discovered.repositoryRoot, repository);
      repositories.push(repository);
    } catch (error) {
      failures.push(Object.freeze({
        workspaceName: folder.name,
        code: error instanceof ConnectionContractError
          ? error.code
          : 'GIT_SCENARIO_DISCOVERY_FAILED',
      }));
    }
  }
  return Object.freeze({
    repositories: Object.freeze(repositories),
    failures: Object.freeze(failures),
  });
}

function scenarioRepositoryPaths(output: Buffer): string[] {
  let decoded: string;
  try {
    decoded = new TextDecoder('utf-8', { fatal: true }).decode(output);
  } catch {
    throw contract('GIT_SCENARIO_PATH_INVALID');
  }
  const paths = new Set<string>();
  for (const path of decoded.split('\0').filter(Boolean)) {
    if (Buffer.byteLength(path) > MAX_REPOSITORY_PATH_BYTES) {
      throw contract('GIT_SCENARIO_PATH_LIMIT_EXCEEDED');
    }
    if (!safePath(path)) throw contract('GIT_SCENARIO_PATH_INVALID');
    paths.add(path);
  }
  return [...paths];
}

function scenarioBundlePaths(paths: readonly string[]): string[] {
  const bundles = new Set<string>();
  for (const path of paths) {
    const segments = path.split('/');
    if (segments[0] === SCENARIOS_ROOT && segments.at(-1) === SCENARIO_DESCRIPTOR && segments.length >= 3) {
      bundles.add(segments.slice(0, -1).join('/'));
    }
  }
  return [...bundles].sort((left, right) => left.localeCompare(right, 'en'));
}

function scenarioBundleFiles(
  paths: readonly string[],
  bundlePaths: readonly string[],
): ReadonlyMap<string, readonly string[]> {
  const grouped = new Map(bundlePaths.map(bundlePath => [bundlePath, new Set<string>()]));
  const closestFirst = [...bundlePaths].sort((left, right) => right.length - left.length);
  for (const path of paths) {
    const owner = closestFirst.find(bundlePath => path.startsWith(`${bundlePath}/`));
    if (owner) grouped.get(owner)!.add(path.slice(owner.length + 1));
  }
  return new Map([...grouped].map(([bundlePath, files]) => [
    bundlePath,
    Object.freeze([...files].sort((left, right) => left.localeCompare(right, 'en'))),
  ]));
}

function safePath(value: string): boolean {
  return !value.includes('\\')
    && !value.split('/').some(segment => segment === '' || segment === '.' || segment === '..');
}

function requiredText(value: Buffer): string {
  const result = value.toString('utf8').trim();
  if (!result) throw contract('GIT_COMMAND_OUTPUT_REQUIRED');
  return result;
}

function contract(code: string): ConnectionContractError {
  return new ConnectionContractError(code, code);
}
