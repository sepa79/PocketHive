/**
 * Responsibility: Own the companion's explicit endpoint modes and transport validation.
 * Must not: Discover endpoints, persist profiles, or infer HTTP permission from a URL.
 * Contract: RESP-PUBLIC-ENDPOINT-TRANSPORT — docs/architecture/runtime-responsibilities.md.
 */
import { ConnectionContractError } from './contracts';

export const ENDPOINT_SECURITY_MODES = ['REMOTE_HTTPS', 'LOCAL_LOOPBACK_HTTP', 'REMOTE_HTTP'] as const;
export type EndpointSecurityMode = typeof ENDPOINT_SECURITY_MODES[number];

export function isEndpointSecurityMode(value: unknown): value is EndpointSecurityMode {
  return ENDPOINT_SECURITY_MODES.some(mode => mode === value);
}

export function isLoopbackHostname(hostname: string): boolean {
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '[::1]';
}

export function validateEndpointTransport(url: URL, mode: EndpointSecurityMode, authorizationServer = false): void {
  let code: string;
  let message: string;
  if (!isEndpointSecurityMode(mode)) {
    code = 'MCP_ENDPOINT_SECURITY_MODE_INVALID';
    message = 'An explicit supported endpoint security mode is required';
  } else if (url.username || url.password || url.search || url.hash) {
    code = 'MCP_ENDPOINT_INVALID';
    message = 'Endpoint must not contain credentials, query parameters, or a fragment';
  } else if (mode === 'REMOTE_HTTPS' && url.protocol !== 'https:') {
    code = 'MCP_ENDPOINT_HTTPS_REQUIRED';
    message = 'Remote MCP environments require HTTPS in Remote HTTPS mode';
  } else if (mode === 'REMOTE_HTTP' && url.protocol !== 'http:') {
    code = 'MCP_ENDPOINT_HTTP_REQUIRED';
    message = 'Remote HTTP mode requires an explicit HTTP URL';
  } else if (mode === 'LOCAL_LOOPBACK_HTTP'
      && (url.protocol !== 'http:' || !isLoopbackHostname(url.hostname))) {
    code = 'MCP_ENDPOINT_LOOPBACK_REQUIRED';
    message = 'Local HTTP MCP environments require an explicit loopback host';
  } else {
    return;
  }
  throw new ConnectionContractError(authorizationServer ? 'MCP_AUTHORIZATION_SERVER_INVALID' : code, message);
}
