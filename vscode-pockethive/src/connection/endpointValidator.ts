/**
 * Responsibility: Discover and validate resource metadata for an explicit connection profile.
 * Must not: Authenticate users, persist profiles, or define another transport policy.
 * Contract: RESP-COMPANION-ENDPOINT-DISCOVERY — docs/architecture/runtime-responsibilities.md#resp-companion-endpoint-discovery.
 */
import { validateEndpointTransport } from './endpointSecurityPolicy';
import { lookup } from 'node:dns/promises';

import {
  ConnectionContractError,
  EndpointValidationPort,
  McpConnectionProfile,
  ValidatedEndpoint,
} from './contracts';

type AddressResolver = (hostname: string) => Promise<string[]>;

const MAX_METADATA_CHARACTERS = 65_536;
export const ENDPOINT_DISCOVERY_TIMEOUT_MS = 10_000;

export class PocketHiveEndpointValidator implements EndpointValidationPort {
  constructor(
    private readonly fetcher: typeof fetch = fetch,
    private readonly resolveAddresses: AddressResolver = resolveHost,
    private readonly timeoutMs: number = ENDPOINT_DISCOVERY_TIMEOUT_MS,
  ) {}

  async validate(profile: McpConnectionProfile, signal: AbortSignal): Promise<ValidatedEndpoint> {
    signal.throwIfAborted();
    const deadline = new AbortController();
    const cancel = () => deadline.abort(signal.reason);
    signal.addEventListener('abort', cancel, { once: true });
    const timeout = setTimeout(() => deadline.abort(new ConnectionContractError(
      'MCP_ENDPOINT_DISCOVERY_TIMEOUT', 'Endpoint discovery exceeded its time limit',
    )), this.timeoutMs);
    const boundedSignal = deadline.signal;
    let onAbort!: () => void;
    const aborted = new Promise<never>((_resolve, reject) => {
      onAbort = () => reject(boundedSignal.reason);
      boundedSignal.addEventListener('abort', onAbort, { once: true });
    });
    try {
      return await Promise.race([this.discover(profile, boundedSignal), aborted]);
    } finally {
      clearTimeout(timeout);
      signal.removeEventListener('abort', cancel);
      boundedSignal.removeEventListener('abort', onAbort);
    }
  }

  private async discover(profile: McpConnectionProfile, signal: AbortSignal): Promise<ValidatedEndpoint> {
    const endpoint = new URL(profile.mcpUrl);
    validateEndpointTransport(endpoint, profile.endpointSecurityMode);
    if (profile.endpointSecurityMode === 'LOCAL_LOOPBACK_HTTP') {
      const addresses = await this.resolveAddresses(endpoint.hostname);
      signal.throwIfAborted();
      if (addresses.length === 0 || addresses.some(address => !isLoopback(address))) {
        throw new ConnectionContractError(
          'MCP_ENDPOINT_LOOPBACK_RESOLUTION_FAILED',
          'MCP_ENDPOINT_LOOPBACK_RESOLUTION_FAILED: every resolved address must remain loopback',
        );
      }
    }
    const metadataUrl = new URL('/.well-known/oauth-protected-resource', endpoint).toString();
    const response = await this.fetcher(metadataUrl, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      redirect: 'error',
      signal,
    });
    signal.throwIfAborted();
    if (!response.ok) {
      throw new ConnectionContractError(
        'MCP_RESOURCE_METADATA_UNAVAILABLE',
        `MCP_RESOURCE_METADATA_UNAVAILABLE: HTTP ${response.status}`,
      );
    }
    if (!(response.headers.get('Content-Type') ?? '').startsWith('application/json')) {
      throw new ConnectionContractError(
        'MCP_RESOURCE_METADATA_INVALID',
        'MCP_RESOURCE_METADATA_INVALID: response must be application/json',
      );
    }
    const text = await response.text();
    signal.throwIfAborted();
    if (text.length > MAX_METADATA_CHARACTERS) {
      throw new ConnectionContractError(
        'MCP_RESOURCE_METADATA_INVALID',
        'MCP_RESOURCE_METADATA_INVALID: response exceeded the size limit',
      );
    }
    const metadata = object(text);
    if (metadata.resource !== profile.mcpUrl) {
      throw new ConnectionContractError(
        'MCP_RESOURCE_METADATA_MISMATCH',
        'MCP_RESOURCE_METADATA_MISMATCH: resource must equal the entered MCP URL',
      );
    }
    if (!Array.isArray(metadata.authorization_servers)
        || metadata.authorization_servers.length !== 1
        || typeof metadata.authorization_servers[0] !== 'string') {
      throw new ConnectionContractError(
        'MCP_AUTHORIZATION_SERVER_INVALID',
        'MCP_AUTHORIZATION_SERVER_INVALID: exactly one authorization server is required',
      );
    }
    const authorizationServer = new URL(metadata.authorization_servers[0]);
    validateEndpointTransport(authorizationServer, profile.endpointSecurityMode, true);
    return {
      mcpUrl: profile.mcpUrl,
      resourceMetadataUrl: metadataUrl,
      authorizationServer: authorizationServer.toString().replace(/\/$/, ''),
    };
  }
}

async function resolveHost(hostname: string): Promise<string[]> {
  return (await lookup(hostname === '[::1]' ? '::1' : hostname, { all: true, verbatim: true })).map(result => result.address);
}

function isLoopback(address: string): boolean {
  const normalized = address.toLowerCase();
  return normalized === '::1'
    || normalized.startsWith('127.')
    || normalized.startsWith('::ffff:127.');
}

function object(text: string): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(text);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not an object');
    return parsed as Record<string, unknown>;
  } catch (error) {
    throw new ConnectionContractError(
      'MCP_RESOURCE_METADATA_INVALID',
      `MCP_RESOURCE_METADATA_INVALID: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
}
