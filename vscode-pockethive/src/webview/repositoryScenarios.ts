import { randomUUID } from 'node:crypto';
import { constants } from 'node:fs';
import { access } from 'node:fs/promises';
import { resolve as resolvePath } from 'node:path';

import { ConnectionContractError } from '../connection/contracts';
import { CommittedBundleReference } from '../scenarios/gitBundlePackager';
import {
  discoverWorkspaceScenarioBundles,
  GitScenarioDiscoveryPort,
  WorkspaceScenarioFolder,
  WorkspaceScenarioRepository,
} from '../scenarios/gitScenarioBundleDiscovery';

export type RepositoryScenarioState = 'NO_WORKSPACE' | 'UNTRUSTED' | 'SCANNED';

export interface RepositoryScenarioCandidateView {
  readonly candidateId: string;
  readonly bundlePath: string;
  readonly files: readonly string[];
}

export interface RepositoryScenarioView {
  readonly state: RepositoryScenarioState;
  readonly repositories: readonly {
    readonly workspaceName: string;
    readonly commit: string;
    readonly candidates: readonly RepositoryScenarioCandidateView[];
  }[];
  readonly failures: readonly { readonly workspaceName: string; readonly code: string }[];
}

export class RepositoryScenarioCandidateRegistry {
  private readonly candidates = new Map<string, {
    readonly reference: CommittedBundleReference;
    readonly files: ReadonlySet<string>;
  }>();

  constructor(private readonly newId: () => string = randomUUID) {}

  rebuild(repositories: readonly WorkspaceScenarioRepository[]): RepositoryScenarioView['repositories'] {
    this.candidates.clear();
    return Object.freeze(repositories.map(repository => Object.freeze({
      workspaceName: repository.workspaceName,
      commit: repository.commit,
      candidates: Object.freeze(repository.candidates.map(candidate => {
        const candidateId = this.newId();
        const reference = Object.freeze({
          repositoryRoot: repository.repositoryRoot,
          bundlePath: candidate.bundlePath,
          commit: repository.commit,
        });
        this.candidates.set(candidateId, Object.freeze({
          reference,
          files: new Set(candidate.files),
        }));
        return Object.freeze({
          candidateId,
          bundlePath: candidate.bundlePath,
          files: Object.freeze([...candidate.files]),
        });
      })),
    })));
  }

  clear(): void {
    this.candidates.clear();
  }

  resolve(candidateId: string): CommittedBundleReference {
    const candidate = this.candidates.get(candidateId.trim());
    if (!candidate) {
      throw new ConnectionContractError(
        'REPOSITORY_SCENARIO_CANDIDATE_UNKNOWN',
        'Refresh Repository scenarios and select an exact candidate',
      );
    }
    return candidate.reference;
  }

  resolveFile(candidateId: string, path: string): string {
    const candidate = this.candidates.get(candidateId.trim());
    const relativePath = path.trim();
    if (!candidate || !candidate.files.has(relativePath)) {
      throw new ConnectionContractError(
        'REPOSITORY_SCENARIO_FILE_UNKNOWN',
        'Refresh Repository scenarios and select an exact committed file',
      );
    }
    return resolvePath(candidate.reference.repositoryRoot, candidate.reference.bundlePath, relativePath);
  }
}

export function resolveRepositoryScenarioCandidate(
  trusted: boolean,
  candidateId: string,
  registry: RepositoryScenarioCandidateRegistry,
): CommittedBundleReference {
  if (!trusted) {
    registry.clear();
    throw new ConnectionContractError('WORKSPACE_TRUST_REQUIRED', 'Trust this workspace before validating Git content');
  }
  return registry.resolve(candidateId);
}

export function resolveRepositoryScenarioFile(
  trusted: boolean,
  candidateId: string,
  path: string,
  registry: RepositoryScenarioCandidateRegistry,
): string {
  if (!trusted) {
    registry.clear();
    throw new ConnectionContractError('WORKSPACE_TRUST_REQUIRED', 'Trust this workspace before editing Git content');
  }
  return registry.resolveFile(candidateId, path);
}

type RepositoryFileAccess = (path: string, mode: number) => Promise<void>;

export async function assertRepositoryScenarioFileWritable(
  file: string,
  checkAccess: RepositoryFileAccess = access,
): Promise<string> {
  try {
    await checkAccess(file, constants.W_OK);
  } catch {
    throw new ConnectionContractError(
      'REPOSITORY_SCENARIO_FILE_NOT_WRITABLE',
      'The selected worktree file is not writable; repair its host ownership or permissions',
    );
  }
  return file;
}

export async function scanRepositoryScenarios(
  trusted: boolean,
  folders: readonly WorkspaceScenarioFolder[],
  discovery: GitScenarioDiscoveryPort,
  registry: RepositoryScenarioCandidateRegistry,
): Promise<RepositoryScenarioView> {
  registry.clear();
  if (folders.length === 0) return empty('NO_WORKSPACE');
  if (!trusted) return empty('UNTRUSTED');
  const discovered = await discoverWorkspaceScenarioBundles(folders, discovery);
  return Object.freeze({
    state: 'SCANNED' as const,
    repositories: registry.rebuild(discovered.repositories),
    failures: discovered.failures,
  });
}

function empty(state: Exclude<RepositoryScenarioState, 'SCANNED'>): RepositoryScenarioView {
  return Object.freeze({ state, repositories: Object.freeze([]), failures: Object.freeze([]) });
}
