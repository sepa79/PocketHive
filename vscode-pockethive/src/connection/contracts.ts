export const MCP_PROTOCOL_REVISION = '2025-11-25' as const;
export const EXPECTED_MCP_SERVER_NAME = 'pockethive-mcp' as const;
export const POCKETHIVE_MCP_SCOPES = Object.freeze({
  DISCOVER: 'pockethive:mcp:discover',
  READ: 'pockethive:mcp:read',
  OPERATE: 'pockethive:mcp:operate',
  AUTHOR: 'pockethive:mcp:author',
  PUBLISH: 'pockethive:mcp:publish',
  CLEANUP: 'pockethive:mcp:cleanup',
} as const);
export type PocketHiveMcpScope = typeof POCKETHIVE_MCP_SCOPES[keyof typeof POCKETHIVE_MCP_SCOPES];
export const POCKETHIVE_COMPANION_SCOPES = Object.freeze([
  POCKETHIVE_MCP_SCOPES.DISCOVER,
  POCKETHIVE_MCP_SCOPES.READ,
  POCKETHIVE_MCP_SCOPES.OPERATE,
  POCKETHIVE_MCP_SCOPES.AUTHOR,
  POCKETHIVE_MCP_SCOPES.PUBLISH,
] as const);

export type EndpointSecurityMode = 'REMOTE_HTTPS' | 'LOCAL_LOOPBACK_HTTP';
export type AuthenticationMode = 'OAUTH_AUTHORIZATION_CODE_PKCE';

export interface McpConnectionProfile {
  readonly id: string;
  readonly displayName: string;
  readonly mcpUrl: string;
  readonly endpointSecurityMode: EndpointSecurityMode;
  readonly authenticationMode: AuthenticationMode;
  readonly secretKey: string;
}

export type ConnectionAttemptState =
  | 'EDITING'
  | 'AUTHENTICATING'
  | 'TESTING'
  | 'READY_TO_SAVE'
  | 'AUTHENTICATION_FAILED'
  | 'CONNECTION_TEST_FAILED'
  | 'SAVED'
  | 'CANCELLED';

export interface ConnectionFailure {
  readonly code: string;
  readonly message: string;
  readonly observedAt: string;
}

export interface ConnectionEvidence {
  readonly serverName: typeof EXPECTED_MCP_SERVER_NAME;
  readonly serverVersion: string;
  readonly principalLabel: string;
  readonly capabilityFingerprint: string;
  readonly observedAt: string;
}

export interface ConnectionAttemptView {
  readonly profileId: string;
  readonly state: ConnectionAttemptState;
  readonly endpointValidated: boolean;
  readonly authenticated: boolean;
  readonly failure?: ConnectionFailure;
  readonly evidence?: ConnectionEvidence;
}

export interface ValidatedEndpoint {
  readonly mcpUrl: string;
  readonly resourceMetadataUrl: string;
  readonly authorizationServer: string;
}

export type OAuthSessionRenewal =
  | { readonly kind: 'ROTATING_REFRESH_TOKEN'; readonly refreshToken: string }
  | { readonly kind: 'NONE' };

export interface OAuthSession {
  readonly accessToken: string;
  readonly expiresAt: string;
  readonly scopes: readonly PocketHiveMcpScope[];
  readonly renewal: OAuthSessionRenewal;
}

export interface EndpointValidationPort {
  validate(profile: McpConnectionProfile): Promise<ValidatedEndpoint>;
}

export interface AuthenticationPort {
  authenticate(profile: McpConnectionProfile, endpoint: ValidatedEndpoint, signal: AbortSignal): Promise<OAuthSession>;
  session(profile: McpConnectionProfile): Promise<OAuthSession | undefined>;
}

export interface RenewableAuthenticationPort extends AuthenticationPort {
  refresh(
    profile: McpConnectionProfile,
    endpoint: ValidatedEndpoint,
    session: OAuthSession,
    signal: AbortSignal,
  ): Promise<OAuthSession>;
  revoke(
    profile: McpConnectionProfile,
    endpoint: ValidatedEndpoint,
    session: OAuthSession,
    signal: AbortSignal,
  ): Promise<void>;
  clear(profile: McpConnectionProfile): Promise<void>;
}

export interface BrowserAuthorizationPort {
  authorize(authorizationUrl: string, signal: AbortSignal): Promise<URL>;
}

export interface OAuthSessionStore {
  get(key: string): PromiseLike<string | undefined>;
  store(key: string, value: string): PromiseLike<void>;
  delete(key: string): PromiseLike<void>;
}

export interface McpConnectionTestPort {
  test(profile: McpConnectionProfile, session: OAuthSession, signal: AbortSignal): Promise<ConnectionEvidence>;
}

export interface ConnectionAttemptObserver {
  changed(view: ConnectionAttemptView): void;
}

export class AuthenticationCancelledError extends Error {
  constructor() {
    super('Authentication was cancelled');
    this.name = 'AuthenticationCancelledError';
  }
}

export class AuthenticationExpiredError extends Error {
  constructor() {
    super('OAuth session expired');
    this.name = 'AuthenticationExpiredError';
  }
}

export class ConnectionContractError extends Error {
  constructor(readonly code: string, message: string, readonly details?: Record<string, unknown>) {
    super(`${code}: ${message}`);
    this.name = 'ConnectionContractError';
  }
}
