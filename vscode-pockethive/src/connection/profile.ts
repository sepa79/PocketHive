import {
  AuthenticationMode,
  ConnectionContractError,
  EndpointSecurityMode,
  McpConnectionProfile,
} from './contracts';

const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);
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
  if (endpoint.username || endpoint.password || endpoint.search || endpoint.hash) {
    throw new ConnectionContractError(
      'MCP_ENDPOINT_INVALID',
      'MCP URL must not contain credentials, query parameters, or a fragment',
    );
  }
  if (endpoint.pathname !== '/mcp') {
    throw new ConnectionContractError('MCP_ENDPOINT_PATH_INVALID', 'MCP URL path must be exactly /mcp');
  }
  if (input.endpointSecurityMode === 'REMOTE_HTTPS' && endpoint.protocol !== 'https:') {
    throw new ConnectionContractError('MCP_ENDPOINT_HTTPS_REQUIRED', 'Remote MCP environments require HTTPS');
  }
  if (input.endpointSecurityMode === 'LOCAL_LOOPBACK_HTTP'
      && (endpoint.protocol !== 'http:' || !LOOPBACK_HOSTS.has(endpoint.hostname))) {
    throw new ConnectionContractError(
      'MCP_ENDPOINT_LOOPBACK_REQUIRED',
      'Local HTTP MCP environments require an explicit loopback host',
    );
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
