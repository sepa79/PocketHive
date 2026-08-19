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
  RenewableAuthenticationPort,
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

interface OAuthServerContract {
  readonly authorizationEndpoint: string;
  readonly tokenEndpoint: string;
  readonly revocationEndpoint: string;
}

type RenewableOAuthSession = OAuthSession & {
  readonly renewal: { readonly kind: 'ROTATING_REFRESH_TOKEN'; readonly refreshToken: string };
};

export class PocketHiveOAuthAuthentication implements AuthenticationPort, ScopedAuthenticationPort, RenewableAuthenticationPort {
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
    const session = await this.authorize(profile, endpoint, CONNECTION_SCOPES, true, signal);
    await this.sessions.store(profile.secretKey, JSON.stringify(session));
    return session;
  }

  async authenticateForScopes(
    profile: McpConnectionProfile,
    endpoint: ValidatedEndpoint,
    scopes: readonly PocketHiveMcpScope[],
    signal: AbortSignal,
  ): Promise<OAuthSession> {
    return this.authorize(profile, endpoint, scopes, false, signal);
  }

  private async authorize(
    profile: McpConnectionProfile,
    endpoint: ValidatedEndpoint,
    requestedScopes: readonly PocketHiveMcpScope[],
    renewable: boolean,
    signal: AbortSignal,
  ): Promise<OAuthSession> {
    try {
    validateRequestedScopes(requestedScopes);
    const metadataUrl = `${endpoint.authorizationServer}/.well-known/oauth-authorization-server`;
    const metadata = await this.readJson(metadataUrl, { method: 'GET', redirect: 'error', signal });
    const server = validateMetadata(metadata, endpoint.authorizationServer, requestedScopes);
    const state = base64url(this.secureRandom(32));
    const verifier = base64url(this.secureRandom(64));
    const challenge = createHash('sha256').update(verifier).digest('base64url');
    const authorizationUrl = new URL(server.authorizationEndpoint);
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
    const token = await this.readJson(server.tokenEndpoint, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: body.toString(),
      redirect: 'error',
      signal,
    });
    const session = tokenSession(token, this.now(), renewable);
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
      if (parsed === null) throw new Error('not an object');
      if (Array.isArray(parsed)) throw new Error('not an object');
      if (typeof parsed !== 'object') throw new Error('not an object');
      const record = parsed as Record<string, unknown>;
      if (Object.keys(record).length !== 3) throw new Error('unexpected fields');
      if (!Object.hasOwn(record, 'accessToken')) throw new Error('unexpected fields');
      if (!Object.hasOwn(record, 'expiresAt')) throw new Error('unexpected fields');
      if (!Object.hasOwn(record, 'renewal')) throw new Error('unexpected fields');
      const accessToken = requiredString(record, 'accessToken', 'OAUTH_SESSION_INVALID');
      const expiresAt = requiredString(record, 'expiresAt', 'OAUTH_SESSION_INVALID');
      if (Number.isNaN(Date.parse(expiresAt))) throw new Error('invalid expiry');
      return { accessToken, expiresAt, renewal: storedRenewal(record.renewal) };
    } catch (error) {
      throw new ConnectionContractError(
        'OAUTH_SESSION_INVALID',
        `OAUTH_SESSION_INVALID: ${error instanceof Error ? error.message : String(error)}`,
      );
    }
  }

  async refresh(
    profile: McpConnectionProfile,
    endpoint: ValidatedEndpoint,
    session: OAuthSession,
    signal: AbortSignal,
  ): Promise<OAuthSession> {
    if (session.renewal.kind !== 'ROTATING_REFRESH_TOKEN') {
      throw new ConnectionContractError('OAUTH_SESSION_NOT_RENEWABLE', 'OAUTH_SESSION_NOT_RENEWABLE');
    }
    const server = await this.metadata(endpoint, signal);
    const body = new URLSearchParams({
      grant_type: 'refresh_token',
      client_id: CLIENT_ID,
      refresh_token: session.renewal.refreshToken,
      resource: endpoint.mcpUrl,
    });
    const token = await this.readJson(server.tokenEndpoint, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: body.toString(),
      redirect: 'error',
      signal,
    });
    const refreshed = tokenSession(token, this.now(), true);
    if (refreshed.renewal.refreshToken === session.renewal.refreshToken) {
      throw new ConnectionContractError('OAUTH_REFRESH_TOKEN_NOT_ROTATED', 'OAUTH_REFRESH_TOKEN_NOT_ROTATED');
    }
    await this.sessions.store(profile.secretKey, JSON.stringify(refreshed));
    return refreshed;
  }

  async revoke(
    _profile: McpConnectionProfile,
    endpoint: ValidatedEndpoint,
    session: OAuthSession,
    signal: AbortSignal,
  ): Promise<void> {
    if (session.renewal.kind !== 'ROTATING_REFRESH_TOKEN') {
      throw new ConnectionContractError('OAUTH_SESSION_NOT_RENEWABLE', 'OAUTH_SESSION_NOT_RENEWABLE');
    }
    const server = await this.metadata(endpoint, signal);
    const failures: unknown[] = [];
    for (const [token, hint] of [
      [session.accessToken, 'access_token'],
      [session.renewal.refreshToken, 'refresh_token'],
    ] as const) {
      try {
        await this.postRevocation(server.revocationEndpoint, token, hint, signal);
      } catch (error) {
        failures.push(error);
      }
    }
    if (failures.length > 0) {
      throw new ConnectionContractError(
        'OAUTH_REVOCATION_UNCONFIRMED',
        `OAUTH_REVOCATION_UNCONFIRMED: ${failures.length} request(s) failed`,
      );
    }
  }

  async clear(profile: McpConnectionProfile): Promise<void> {
    await this.sessions.delete(profile.secretKey);
  }

  private async metadata(endpoint: ValidatedEndpoint, signal: AbortSignal): Promise<OAuthServerContract> {
    const value = await this.readJson(`${endpoint.authorizationServer}/.well-known/oauth-authorization-server`, {
      method: 'GET', redirect: 'error', signal,
    });
    return validateMetadata(value, endpoint.authorizationServer, CONNECTION_SCOPES);
  }

  private async postRevocation(
    endpoint: string,
    token: string,
    tokenTypeHint: 'access_token' | 'refresh_token',
    signal: AbortSignal,
  ): Promise<void> {
    const response = await this.fetcher(endpoint, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: new URLSearchParams({
        client_id: CLIENT_ID,
        token,
        token_type_hint: tokenTypeHint,
      }).toString(),
      redirect: 'error',
      signal,
    });
    if (!response.ok) {
      throw new Error();
    }
  }

  private async readJson(url: string, init: RequestInit): Promise<Record<string, unknown>> {
    const response = await this.fetcher(url, {
      ...init,
      headers: { Accept: 'application/json', ...init.headers },
    });
    const contentType = response.headers.get('Content-Type');
    if (!response.ok || contentType === null || !contentType.startsWith('application/json')) {
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
      if (value === null) throw new Error('not an object');
      if (Array.isArray(value)) throw new Error('not an object');
      if (typeof value !== 'object') throw new Error('not an object');
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
): OAuthServerContract {
  const issuer = requiredString(metadata, 'issuer', 'OAUTH_METADATA_INVALID').replace(/\/$/, '');
  const authorizationEndpoint = requiredString(metadata, 'authorization_endpoint', 'OAUTH_METADATA_INVALID');
  const tokenEndpoint = requiredString(metadata, 'token_endpoint', 'OAUTH_METADATA_INVALID');
  const revocationEndpoint = requiredString(metadata, 'revocation_endpoint', 'OAUTH_METADATA_INVALID');
  if (issuer !== expectedIssuer
      || !ownedEndpoint(issuer, authorizationEndpoint)
      || !ownedEndpoint(issuer, tokenEndpoint)
      || !ownedEndpoint(issuer, revocationEndpoint)
      || !arrayContainsOnlySupported(metadata, 'response_types_supported', 'code')
      || !arrayEquals(metadata, 'grant_types_supported', ['authorization_code', 'refresh_token'])
      || !arrayContainsOnlySupported(metadata, 'token_endpoint_auth_methods_supported', 'none')
      || !arrayContainsOnlySupported(metadata, 'revocation_endpoint_auth_methods_supported', 'none')
      || !arrayContainsOnlySupported(metadata, 'code_challenge_methods_supported', 'S256')
      || !supportsScopes(metadata.scopes_supported, requestedScopes)) {
    throw new ConnectionContractError('OAUTH_METADATA_INVALID', 'OAUTH_METADATA_INVALID: contract mismatch');
  }
  return { authorizationEndpoint, tokenEndpoint, revocationEndpoint };
}

function validateRequestedScopes(scopes: readonly PocketHiveMcpScope[]): void {
  if (scopes[0] !== POCKETHIVE_MCP_SCOPES.DISCOVER) invalidScopeSet();
  if (scopes[1] !== POCKETHIVE_MCP_SCOPES.READ) invalidScopeSet();
  if (new Set(scopes).size !== scopes.length) invalidScopeSet();
  if (scopes.some(scope => !SUPPORTED_SCOPES.has(scope))) invalidScopeSet();
}

function invalidScopeSet(): never {
  throw new ConnectionContractError('OAUTH_SCOPE_SET_INVALID', 'OAUTH_SCOPE_SET_INVALID');
}

function validateCallback(callback: URL, expectedState: string): void {
  if (`${callback.protocol}//${callback.host}${callback.pathname}` !== REDIRECT_URI) {
    throw new ConnectionContractError('OAUTH_CALLBACK_INVALID', 'OAUTH_CALLBACK_INVALID: redirect URI mismatch');
  }
  if (callback.searchParams.get('state') !== expectedState) {
    throw new ConnectionContractError('OAUTH_STATE_MISMATCH', 'OAUTH_STATE_MISMATCH');
  }
}

function tokenSession(token: Record<string, unknown>, now: Date, renewable: true): RenewableOAuthSession;
function tokenSession(token: Record<string, unknown>, now: Date, renewable: false): OAuthSession;
function tokenSession(token: Record<string, unknown>, now: Date, renewable: boolean): OAuthSession;
function tokenSession(token: Record<string, unknown>, now: Date, renewable: boolean): OAuthSession {
  if (token.token_type !== 'Bearer') invalidTokenResponse();
  if (!Number.isInteger(token.expires_in)) invalidTokenResponse();
  const expiresIn = token.expires_in as number;
  if (expiresIn <= 0) invalidTokenResponse();
  if (expiresIn > 86_400) invalidTokenResponse();
  const accessToken = requiredString(token, 'access_token', 'OAUTH_TOKEN_RESPONSE_INVALID');
  const renewal = renewable
    ? { kind: 'ROTATING_REFRESH_TOKEN' as const,
      refreshToken: requiredString(token, 'refresh_token', 'OAUTH_TOKEN_RESPONSE_INVALID') }
    : { kind: 'NONE' as const };
  if (!renewable && token.refresh_token !== undefined) {
    invalidTokenResponse();
  }
  return {
    accessToken,
    expiresAt: new Date(now.getTime() + expiresIn * 1000).toISOString(),
    renewal,
  };
}

function invalidTokenResponse(): never {
  throw new ConnectionContractError('OAUTH_TOKEN_RESPONSE_INVALID', 'OAUTH_TOKEN_RESPONSE_INVALID');
}

function storedRenewal(value: unknown): OAuthSession['renewal'] {
  const record = value as Record<string, unknown>;
  if (record?.kind === 'NONE') {
    if (Object.keys(record).length !== 1) throw new Error('invalid renewal');
    return { kind: 'NONE' };
  }
  if (record?.kind === 'ROTATING_REFRESH_TOKEN') {
    if (Object.keys(record).length !== 2 || !Object.hasOwn(record, 'refreshToken')) {
      throw new Error('invalid renewal');
    }
    return {
      kind: 'ROTATING_REFRESH_TOKEN',
      refreshToken: requiredString(record, 'refreshToken', 'OAUTH_SESSION_INVALID'),
    };
  }
  throw new Error('invalid renewal');
}

function ownedEndpoint(issuer: string, candidate: string): boolean {
  if (!URL.canParse(candidate)) return false;
  const value = new URL(candidate);
  return candidate.startsWith(`${issuer}/`) && !value.username && !value.password && !value.search && !value.hash;
}

function arrayContainsOnlySupported(metadata: Record<string, unknown>, key: string, required: string): boolean {
  const values = metadata[key];
  return Array.isArray(values) && values.length === 1 && values[0] === required;
}

function supportsScopes(value: unknown, required: readonly PocketHiveMcpScope[]): boolean {
  if (!Array.isArray(value)) return false;
  if (value.some(item => typeof item !== 'string')) return false;
  return required.every(scope => value.includes(scope));
}

function arrayEquals(metadata: Record<string, unknown>, key: string, required: readonly string[]): boolean {
  return JSON.stringify(metadata[key]) === JSON.stringify(required);
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
