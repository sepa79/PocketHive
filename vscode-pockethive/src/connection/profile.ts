/**
 * Responsibility: Construct validated immutable connection profiles from explicit input.
 * Must not: Discover endpoints, persist profiles, or decide transport policy independently.
 * Contract: RESP-COMPANION-CONNECTION-PROFILE — docs/architecture/runtime-responsibilities.md#resp-companion-connection-profile.
 */
import { validateEndpointTransport } from './endpointSecurityPolicy';
import {
  AuthenticationMode,
  ConnectionContractError,
  EndpointSecurityMode,
  McpConnectionProfile,
} from './contracts';

const AUTHENTICATION_MODE: AuthenticationMode = 'OAUTH_AUTHORIZATION_CODE_PKCE';

export function createConnectionProfile(input: {
  id: string;
  displayName: string;
  mcpUrl: string;
  endpointSecurityMode: EndpointSecurityMode;
  secretKey: string;
}): McpConnectionProfile {
  const id = required(input.id, 'PROFILE_ID_REQUIRED');
  const displayName = required(input.displayName, 'PROFILE_NAME_REQUIRED');
  const secretKey = required(input.secretKey, 'PROFILE_SECRET_KEY_REQUIRED');
  let endpoint: URL;
  try {
    endpoint = new URL(input.mcpUrl);
  } catch {
    throw new ConnectionContractError('MCP_ENDPOINT_INVALID', 'MCP URL must be an absolute URL');
  }
  validateEndpointTransport(endpoint, input.endpointSecurityMode);
  if (endpoint.pathname !== '/mcp') {
    throw new ConnectionContractError('MCP_ENDPOINT_PATH_INVALID', 'MCP URL path must be exactly /mcp');
  }
  endpoint.pathname = '/mcp';
  return Object.freeze({
    id,
    displayName,
    mcpUrl: endpoint.toString(),
    endpointSecurityMode: input.endpointSecurityMode,
    authenticationMode: AUTHENTICATION_MODE,
    secretKey,
  });
}

function required(value: string, code: string): string {
  const normalized = value.trim();
  if (!normalized) {
    throw new ConnectionContractError(code, code);
  }
  return normalized;
}
