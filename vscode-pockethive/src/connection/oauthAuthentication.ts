import { createHash, randomBytes } from 'node:crypto';

import {
  AuthenticationCancelledError,
  AuthenticationPort,
  BrowserAuthorizationPort,
  ConnectionContractError,
  McpConnectionProfile,
  OAuthSession,
  OAuthSessionStore,
  PocketHiveMcpScope,
  POCKETHIVE_MCP_SCOPES,
  ScopedAuthenticationPort,
  ValidatedEndpoint,
} from './contracts';

const CLIENT_ID = 'pockethive-vscode';
const REDIRECT_URI = 'http://127.0.0.1:57548/callback';
const MAX_RESPONSE_CHARACTERS = 65_536;
const CONNECTION_SCOPES = [
  POCKETHIVE_MCP_SCOPES.DISCOVER,
  POCKETHIVE_MCP_SCOPES.READ,
] as const;
const SUPPORTED_SCOPES = new Set<PocketHiveMcpScope>(Object.values(POCKETHIVE_MCP_SCOPES));

type RandomBytes = (size: number) => Uint8Array;

export class PocketHiveOAuthAuthentication implements AuthenticationPort, ScopedAuthenticationPort {
  constructor(
    private readonly fetcher: typeof fetch,
    private readonly browser: BrowserAuthorizationPort,
    private readonly sessions: OAuthSessionStore,
    private readonly secureRandom: RandomBytes = size => randomBytes(size),
    private readonly now: () => Date = () => new Date(),
  ) {}

  async authenticate(
    profile: McpConnectionProfile,
    endpoint: ValidatedEndpoint,
    signal: AbortSignal,
  ): Promise<OAuthSession> {
    const session = await this.authorize(profile, endpoint, CONNECTION_SCOPES, signal);
    await this.sessions.store(profile.secretKey, JSON.stringify(session));
    return session;
  }

  async authenticateForScopes(
    profile: McpConnectionProfile,
    endpoint: ValidatedEndpoint,
    scopes: readonly PocketHiveMcpScope[],
    signal: AbortSignal,
  ): Promise<OAuthSession> {
    return this.authorize(profile, endpoint, scopes, signal);
  }

  private async authorize(
    profile: McpConnectionProfile,
    endpoint: ValidatedEndpoint,
    requestedScopes: readonly PocketHiveMcpScope[],
    signal: AbortSignal,
  ): Promise<OAuthSession> {
    try {
    validateRequestedScopes(requestedScopes);
    const metadataUrl = `${endpoint.authorizationServer}/.well-known/oauth-authorization-server`;
    const metadata = await this.readJson(metadataUrl, { method: 'GET', redirect: 'error', signal });
    const authorizationEndpoint = validateMetadata(metadata, endpoint.authorizationServer, requestedScopes);
    const state = base64url(this.secureRandom(32));
    const verifier = base64url(this.secureRandom(64));
    const challenge = createHash('sha256').update(verifier, 'ascii').digest('base64url');
    const authorizationUrl = new URL(authorizationEndpoint);
    authorizationUrl.searchParams.set('response_type', 'code');
    authorizationUrl.searchParams.set('client_id', CLIENT_ID);
    authorizationUrl.searchParams.set('redirect_uri', REDIRECT_URI);
    authorizationUrl.searchParams.set('resource', endpoint.mcpUrl);
    authorizationUrl.searchParams.set('scope', requestedScopes.join(' '));
    authorizationUrl.searchParams.set('state', state);
    authorizationUrl.searchParams.set('code_challenge', challenge);
    authorizationUrl.searchParams.set('code_challenge_method', 'S256');

    const callback = await this.browser.authorize(authorizationUrl.toString(), signal);
    validateCallback(callback, state);
    if (callback.searchParams.get('error') === 'access_denied') {
      throw new AuthenticationCancelledError();
    }
    const oauthError = callback.searchParams.get('error');
    if (oauthError) {
      throw new ConnectionContractError('OAUTH_AUTHORIZATION_FAILED', `OAUTH_AUTHORIZATION_FAILED: ${oauthError}`);
    }
    const code = callback.searchParams.get('code');
    if (!code) {
      throw new ConnectionContractError('OAUTH_AUTHORIZATION_FAILED', 'OAUTH_AUTHORIZATION_FAILED: code missing');
    }
    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: CLIENT_ID,
      code,
      redirect_uri: REDIRECT_URI,
      resource: endpoint.mcpUrl,
      code_verifier: verifier,
    });
    const tokenEndpoint = requiredString(metadata, 'token_endpoint', 'OAUTH_METADATA_INVALID');
    const token = await this.readJson(tokenEndpoint, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: body.toString(),
      redirect: 'error',
      signal,
    });
    const session = tokenSession(token, this.now());
    if (signal.aborted) throw new AuthenticationCancelledError();
    return session;
    } catch (error) {
      if (signal.aborted) throw new AuthenticationCancelledError();
      throw error;
    }
  }

  async session(profile: McpConnectionProfile): Promise<OAuthSession | undefined> {
    const stored = await this.sessions.get(profile.secretKey);
    if (!stored) return undefined;
    try {
      const parsed: unknown = JSON.parse(stored);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not an object');
      const keys = Object.keys(parsed).sort();
      if (keys.length !== 2 || keys[0] !== 'accessToken' || keys[1] !== 'expiresAt') {
        throw new Error('unexpected fields');
      }
      const accessToken = requiredString(parsed as Record<string, unknown>, 'accessToken', 'OAUTH_SESSION_INVALID');
      const expiresAt = requiredString(parsed as Record<string, unknown>, 'expiresAt', 'OAUTH_SESSION_INVALID');
      if (Number.isNaN(Date.parse(expiresAt))) throw new Error('invalid expiry');
      return { accessToken, expiresAt };
    } catch (error) {
      throw new ConnectionContractError(
        'OAUTH_SESSION_INVALID',
        `OAUTH_SESSION_INVALID: ${error instanceof Error ? error.message : String(error)}`,
      );
    }
  }

  private async readJson(url: string, init: RequestInit): Promise<Record<string, unknown>> {
    const response = await this.fetcher(url, {
      ...init,
      headers: { Accept: 'application/json', ...init.headers },
    });
    if (!response.ok || !(response.headers.get('Content-Type') ?? '').startsWith('application/json')) {
      throw new ConnectionContractError(
        'OAUTH_HTTP_FAILED',
        `OAUTH_HTTP_FAILED: HTTP ${response.status}`,
      );
    }
    const text = await response.text();
    if (text.length > MAX_RESPONSE_CHARACTERS) {
      throw new ConnectionContractError('OAUTH_RESPONSE_INVALID', 'OAUTH_RESPONSE_INVALID: response too large');
    }
    try {
      const value: unknown = JSON.parse(text);
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('not an object');
      return value as Record<string, unknown>;
    } catch (error) {
      throw new ConnectionContractError(
        'OAUTH_RESPONSE_INVALID',
        `OAUTH_RESPONSE_INVALID: ${error instanceof Error ? error.message : String(error)}`,
      );
    }
  }
}

function validateMetadata(
  metadata: Record<string, unknown>,
  expectedIssuer: string,
  requestedScopes: readonly PocketHiveMcpScope[],
): string {
  const issuer = requiredString(metadata, 'issuer', 'OAUTH_METADATA_INVALID').replace(/\/$/, '');
  const authorizationEndpoint = requiredString(metadata, 'authorization_endpoint', 'OAUTH_METADATA_INVALID');
  const tokenEndpoint = requiredString(metadata, 'token_endpoint', 'OAUTH_METADATA_INVALID');
  if (issuer !== expectedIssuer
      || !ownedEndpoint(issuer, authorizationEndpoint)
      || !ownedEndpoint(issuer, tokenEndpoint)
      || !arrayContainsOnlySupported(metadata, 'response_types_supported', 'code')
      || !arrayContainsOnlySupported(metadata, 'grant_types_supported', 'authorization_code')
      || !arrayContainsOnlySupported(metadata, 'token_endpoint_auth_methods_supported', 'none')
      || !arrayContainsOnlySupported(metadata, 'code_challenge_methods_supported', 'S256')
      || !requestedScopes.every(scope => array(metadata.scopes_supported).includes(scope))) {
    throw new ConnectionContractError('OAUTH_METADATA_INVALID', 'OAUTH_METADATA_INVALID: contract mismatch');
  }
  return authorizationEndpoint;
}

function validateRequestedScopes(scopes: readonly PocketHiveMcpScope[]): void {
  if (scopes.length < 2
      || scopes[0] !== POCKETHIVE_MCP_SCOPES.DISCOVER
      || scopes[1] !== POCKETHIVE_MCP_SCOPES.READ
      || new Set(scopes).size !== scopes.length
      || scopes.some(scope => !SUPPORTED_SCOPES.has(scope))) {
    throw new ConnectionContractError('OAUTH_SCOPE_SET_INVALID', 'OAUTH_SCOPE_SET_INVALID');
  }
}

function validateCallback(callback: URL, expectedState: string): void {
  if (`${callback.protocol}//${callback.host}${callback.pathname}` !== REDIRECT_URI) {
    throw new ConnectionContractError('OAUTH_CALLBACK_INVALID', 'OAUTH_CALLBACK_INVALID: redirect URI mismatch');
  }
  if (callback.searchParams.get('state') !== expectedState) {
    throw new ConnectionContractError('OAUTH_STATE_MISMATCH', 'OAUTH_STATE_MISMATCH');
  }
}

function tokenSession(token: Record<string, unknown>, now: Date): OAuthSession {
  if (token.refresh_token !== undefined
      || token.token_type !== 'Bearer'
      || typeof token.expires_in !== 'number'
      || !Number.isInteger(token.expires_in)
      || token.expires_in <= 0
      || token.expires_in > 86_400) {
    throw new ConnectionContractError('OAUTH_TOKEN_RESPONSE_INVALID', 'OAUTH_TOKEN_RESPONSE_INVALID');
  }
  const accessToken = requiredString(token, 'access_token', 'OAUTH_TOKEN_RESPONSE_INVALID');
  return {
    accessToken,
    expiresAt: new Date(now.getTime() + token.expires_in * 1000).toISOString(),
  };
}

function ownedEndpoint(issuer: string, candidate: string): boolean {
  try {
    const value = new URL(candidate);
    return candidate.startsWith(`${issuer}/`) && !value.username && !value.password && !value.search && !value.hash;
  } catch {
    return false;
  }
}

function arrayContainsOnlySupported(metadata: Record<string, unknown>, key: string, required: string): boolean {
  const values = array(metadata[key]);
  return values.length === 1 && values[0] === required;
}

function array(value: unknown): string[] {
  return Array.isArray(value) && value.every(item => typeof item === 'string') ? value : [];
}

function requiredString(value: Record<string, unknown>, key: string, code: string): string {
  const field = value[key];
  if (typeof field !== 'string' || !field.trim()) {
    throw new ConnectionContractError(code, `${code}: ${key} missing`);
  }
  return field;
}

function base64url(value: Uint8Array): string {
  return Buffer.from(value).toString('base64url');
}
