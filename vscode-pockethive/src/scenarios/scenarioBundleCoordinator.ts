import { readFile } from 'node:fs/promises';

import {
  ConnectionContractError,
  McpConnectionProfile,
} from '../connection/contracts';
import {
  BundleFileManifestEntry,
  BundleSourceMetadata,
  CommittedBundleReference,
  GitBundlePackager,
  PreparedCommittedBundle,
} from './gitBundlePackager';

export type PublicationMode = 'CREATE' | 'REPLACE';

export interface BundleValidationReceipt {
  readonly receiptId: string;
  readonly archiveDigest: string;
  readonly bundleContentDigest: string;
  readonly scenarioId: string;
  readonly scenarioName: string;
}

export interface PendingBundlePublication {
  readonly profileId: string;
  readonly bundle: PreparedCommittedBundle;
  readonly receipt: BundleValidationReceipt;
}

export interface BundleMcpClient {
  callTool(name: string, args: Record<string, unknown>): Promise<unknown>;
  uploadArchive(uploadUrl: string, archive: Uint8Array, signal?: AbortSignal): Promise<unknown>;
}

interface BundlePackagerPort {
  package(source: string | CommittedBundleReference): Promise<PreparedCommittedBundle>;
}

type ArchiveReader = (path: string) => Promise<Uint8Array>;

export class ScenarioBundleCoordinator {
  constructor(
    private readonly packager: BundlePackagerPort = new GitBundlePackager(),
    private readonly client: BundleMcpClient,
    private readonly archives: ArchiveReader = readFile,
  ) {}

  async validate(
    profile: McpConnectionProfile,
    source: string | CommittedBundleReference,
    signal = new AbortController().signal,
  ): Promise<PendingBundlePublication> {
    const bundle = await this.packager.package(source);
    try {
      const ticket = object(await this.client.callTool('scenario_bundle_direct_validation_prepare', {
        source: bundle.source,
        fileManifest: bundle.fileManifest,
      }), 'BUNDLE_VALIDATION_TICKET_INVALID');
      const uploadUrl = requiredString(ticket, 'uploadUrl', 'BUNDLE_VALIDATION_TICKET_INVALID');
      const outcome = object(await this.client.uploadArchive(
        uploadUrl, await this.archives(bundle.archivePath), signal),
        'BUNDLE_VALIDATION_OUTCOME_INVALID');
      const receipt = validationReceipt(outcome.validationReceipt);
      return Object.freeze({ profileId: profile.id, bundle, receipt });
    } catch (error) {
      await bundle.dispose();
      throw error;
    }
  }

  async publish(
    profile: McpConnectionProfile,
    pending: PendingBundlePublication,
    mode: PublicationMode,
    scenarioId?: string,
    signal = new AbortController().signal,
  ): Promise<Record<string, unknown>> {
    validatePublicationIntent(mode, scenarioId);
    if (pending.profileId !== profile.id) throw contract('BUNDLE_PROFILE_MISMATCH');
    try {
      const ticket = object(await this.client.callTool('scenario_bundle_publication_prepare', {
        validationReceiptId: pending.receipt.receiptId,
        mode,
        ...(mode === 'REPLACE' ? { scenarioId } : {}),
        source: pending.bundle.source,
        fileManifest: pending.bundle.fileManifest,
        archiveDigest: pending.receipt.archiveDigest,
        bundleContentDigest: pending.receipt.bundleContentDigest,
      }), 'BUNDLE_PUBLICATION_TICKET_INVALID');
      const uploadUrl = requiredString(ticket, 'uploadUrl', 'BUNDLE_PUBLICATION_TICKET_INVALID');
      const outcome = object(await this.client.uploadArchive(uploadUrl,
        await this.archives(pending.bundle.archivePath), signal), 'BUNDLE_PUBLICATION_OUTCOME_INVALID');
      return object(outcome.publicationAttempt, 'BUNDLE_PUBLICATION_OUTCOME_INVALID');
    } finally {
      await pending.bundle.dispose();
    }
  }

  async reconcile(
    profile: McpConnectionProfile,
    attemptId: string,
    signal = new AbortController().signal,
  ): Promise<Record<string, unknown>> {
    void profile;
    void signal;
    return object(await this.client.callTool('scenario_bundle_publication_reconcile', { attemptId }),
      'BUNDLE_PUBLICATION_ATTEMPT_INVALID');
  }
}

function validatePublicationIntent(mode: PublicationMode, scenarioId?: string): void {
  if (mode === 'REPLACE' && !scenarioId?.trim()) throw contract('SCENARIO_ID_REQUIRED');
  if (mode === 'CREATE' && scenarioId !== undefined) throw contract('SCENARIO_ID_FORBIDDEN');
}

function validationReceipt(value: unknown): BundleValidationReceipt {
  const receipt = object(value, 'BUNDLE_VALIDATION_OUTCOME_INVALID');
  const result = {
    receiptId: requiredString(receipt, 'receiptId', 'BUNDLE_VALIDATION_OUTCOME_INVALID'),
    archiveDigest: digest(receipt, 'archiveDigest'),
    bundleContentDigest: digest(receipt, 'bundleContentDigest'),
    scenarioId: requiredString(receipt, 'scenarioId', 'BUNDLE_VALIDATION_OUTCOME_INVALID'),
    scenarioName: requiredString(receipt, 'scenarioName', 'BUNDLE_VALIDATION_OUTCOME_INVALID'),
  };
  return Object.freeze(result);
}

function digest(value: Record<string, unknown>, key: string): string {
  const result = requiredString(value, key, 'BUNDLE_VALIDATION_OUTCOME_INVALID');
  if (!/^sha256:[0-9a-f]{64}$/.test(result)) throw contract('BUNDLE_VALIDATION_OUTCOME_INVALID');
  return result;
}

function object(value: unknown, code: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw contract(code);
  return value as Record<string, unknown>;
}

function requiredString(value: Record<string, unknown>, key: string, code: string): string {
  const result = value[key];
  if (typeof result !== 'string' || !result.trim()) throw contract(code);
  return result.trim();
}

function contract(code: string): ConnectionContractError {
  return new ConnectionContractError(code, code);
}

export type { BundleFileManifestEntry, BundleSourceMetadata };
