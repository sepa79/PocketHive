import { lookup } from 'node:dns/promises';

import {
  ConnectionContractError,
  EndpointValidationPort,
  McpConnectionProfile,
  ValidatedEndpoint,
} from './contracts';

type AddressResolver = (hostname: string) => Promise<string[]>;

const MAX_METADATA_CHARACTERS = 65_536;

export class PocketHiveEndpointValidator implements EndpointValidationPort {
  constructor(
    private readonly fetcher: typeof fetch = fetch,
    private readonly resolveAddresses: AddressResolver = resolveHost,
  ) {}

  async validate(profile: McpConnectionProfile): Promise<ValidatedEndpoint> {
    const endpoint = new URL(profile.mcpUrl);
    if (profile.endpointSecurityMode === 'LOCAL_LOOPBACK_HTTP') {
      const addresses = await this.resolveAddresses(endpoint.hostname);
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
    });
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
    validateAuthorizationServer(authorizationServer, profile.endpointSecurityMode);
    return {
      mcpUrl: profile.mcpUrl,
      resourceMetadataUrl: metadataUrl,
      authorizationServer: authorizationServer.toString().replace(/\/$/, ''),
    };
  }
}

async function resolveHost(hostname: string): Promise<string[]> {
  return (await lookup(hostname, { all: true, verbatim: true })).map(result => result.address);
}

function isLoopback(address: string): boolean {
  const normalized = address.toLowerCase();
  return normalized === '::1'
    || normalized.startsWith('127.')
    || normalized.startsWith('::ffff:127.');
}

function validateAuthorizationServer(url: URL, mode: McpConnectionProfile['endpointSecurityMode']): void {
  if (url.username || url.password || url.search || url.hash) {
    throw new ConnectionContractError(
      'MCP_AUTHORIZATION_SERVER_INVALID',
      'MCP_AUTHORIZATION_SERVER_INVALID: credentials, query, and fragment are forbidden',
    );
  }
  if (mode === 'REMOTE_HTTPS' && url.protocol !== 'https:') {
    throw new ConnectionContractError(
      'MCP_AUTHORIZATION_SERVER_INVALID',
      'MCP_AUTHORIZATION_SERVER_INVALID: remote authorization server requires HTTPS',
    );
  }
  if (mode === 'LOCAL_LOOPBACK_HTTP'
      && (url.protocol !== 'http:' || !isLoopbackHostname(url.hostname))) {
    throw new ConnectionContractError(
      'MCP_AUTHORIZATION_SERVER_INVALID',
      'MCP_AUTHORIZATION_SERVER_INVALID: local authorization server must be loopback HTTP',
    );
  }
}

function isLoopbackHostname(hostname: string): boolean {
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '[::1]';
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
