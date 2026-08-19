import { readFile } from 'node:fs/promises';

import {
  ConnectionContractError,
  ConnectionEvidence,
  EndpointValidationPort,
  McpConnectionProfile,
  POCKETHIVE_MCP_SCOPES,
  ScopedAuthenticationPort,
} from '../connection/contracts';
import {
  BundleFileManifestEntry,
  BundleSourceMetadata,
  GitBundlePackager,
  PreparedCommittedBundle,
} from './gitBundlePackager';

export type PublicationMode = 'CREATE' | 'REPLACE';

export interface BundleValidationReceipt {
  readonly receiptId: string;
  readonly archiveDigest: string;
  readonly bundleContentDigest: string;
  readonly scenarioId: string;
}

export interface PendingBundlePublication {
  readonly profileId: string;
  readonly bundle: PreparedCommittedBundle;
  readonly receipt: BundleValidationReceipt;
}

export interface BundleMcpClient {
  connect(endpoint: string, accessToken: string, signal?: AbortSignal): Promise<ConnectionEvidence>;
  callTool(name: string, args: Record<string, unknown>): Promise<unknown>;
  uploadArchive(uploadUrl: string, archive: Uint8Array, signal?: AbortSignal): Promise<unknown>;
  close(): Promise<void>;
}

interface BundlePackagerPort {
  package(selectedDirectory: string): Promise<PreparedCommittedBundle>;
}

type BundleMcpClientFactory = () => BundleMcpClient;
type ArchiveReader = (path: string) => Promise<Uint8Array>;

export class ScenarioBundleCoordinator {
  constructor(
    private readonly packager: BundlePackagerPort = new GitBundlePackager(),
    private readonly endpoints: EndpointValidationPort,
    private readonly authentication: ScopedAuthenticationPort,
    private readonly clients: BundleMcpClientFactory,
    private readonly archives: ArchiveReader = readFile,
  ) {}

  async validate(
    profile: McpConnectionProfile,
    selectedDirectory: string,
    signal = new AbortController().signal,
  ): Promise<PendingBundlePublication> {
    const bundle = await this.packager.package(selectedDirectory);
    let client: BundleMcpClient | undefined;
    try {
      const endpoint = await this.endpoints.validate(profile);
      const session = await this.authentication.authenticateForScopes(profile, endpoint, [
        POCKETHIVE_MCP_SCOPES.DISCOVER,
        POCKETHIVE_MCP_SCOPES.READ,
        POCKETHIVE_MCP_SCOPES.AUTHOR,
      ], signal);
      client = this.clients();
      await client.connect(profile.mcpUrl, session.accessToken, signal);
      const ticket = object(await client.callTool('scenario_bundle_direct_validation_prepare', {
        source: bundle.source,
        fileManifest: bundle.fileManifest,
      }), 'BUNDLE_VALIDATION_TICKET_INVALID');
      const uploadUrl = requiredString(ticket, 'uploadUrl', 'BUNDLE_VALIDATION_TICKET_INVALID');
      const outcome = object(await client.uploadArchive(uploadUrl, await this.archives(bundle.archivePath), signal),
        'BUNDLE_VALIDATION_OUTCOME_INVALID');
      const receipt = validationReceipt(outcome.validationReceipt);
      return Object.freeze({ profileId: profile.id, bundle, receipt });
    } catch (error) {
      await bundle.dispose();
      throw error;
    } finally {
      if (client) await client.close();
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
    let client: BundleMcpClient | undefined;
    try {
      const endpoint = await this.endpoints.validate(profile);
      const session = await this.authentication.authenticateForScopes(profile, endpoint, [
        POCKETHIVE_MCP_SCOPES.DISCOVER,
        POCKETHIVE_MCP_SCOPES.READ,
        POCKETHIVE_MCP_SCOPES.PUBLISH,
      ], signal);
      client = this.clients();
      await client.connect(profile.mcpUrl, session.accessToken, signal);
      const ticket = object(await client.callTool('scenario_bundle_publication_prepare', {
        validationReceiptId: pending.receipt.receiptId,
        mode,
        ...(mode === 'REPLACE' ? { scenarioId } : {}),
        source: pending.bundle.source,
        fileManifest: pending.bundle.fileManifest,
        archiveDigest: pending.receipt.archiveDigest,
        bundleContentDigest: pending.receipt.bundleContentDigest,
      }), 'BUNDLE_PUBLICATION_TICKET_INVALID');
      const uploadUrl = requiredString(ticket, 'uploadUrl', 'BUNDLE_PUBLICATION_TICKET_INVALID');
      const outcome = object(await client.uploadArchive(uploadUrl,
        await this.archives(pending.bundle.archivePath), signal), 'BUNDLE_PUBLICATION_OUTCOME_INVALID');
      return object(outcome.publicationAttempt, 'BUNDLE_PUBLICATION_OUTCOME_INVALID');
    } finally {
      try {
        if (client) await client.close();
      } finally {
        await pending.bundle.dispose();
      }
    }
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
