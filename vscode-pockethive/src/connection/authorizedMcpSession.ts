import {
  AuthenticationExpiredError,
  ConnectionContractError,
  ConnectionEvidence,
  EndpointValidationPort,
  McpConnectionProfile,
  McpConnectionTestPort,
  OAuthSession,
  PocketHiveMcpScope,
  RenewableAuthenticationPort,
} from './contracts';

export const SESSION_RENEWAL_SKEW_MS = 60_000;
export const SESSION_REFRESH_RETRY_MS = 15_000;

export interface SessionTimerPort {
  schedule(delayMs: number, action: () => Promise<void>): unknown;
  cancel(handle: unknown): void;
}

export interface AuthorizedSessionObserver {
  renewed(profile: McpConnectionProfile, evidence: ConnectionEvidence): void;
  unavailable(profile: McpConnectionProfile, error: unknown): void;
}

const SYSTEM_TIMERS: SessionTimerPort = {
  schedule: (delayMs, action) => {
    const handle = setTimeout(() => { void action(); }, delayMs);
    handle.unref();
    return handle;
  },
  cancel: handle => clearTimeout(handle as ReturnType<typeof setTimeout>),
};

const NOOP_OBSERVER: AuthorizedSessionObserver = {
  renewed: () => undefined,
  unavailable: () => undefined,
};
const DEFINITIVE_REFRESH_FAILURES = new Set([
  'OAUTH_REFRESH_REJECTED',
  'OAUTH_REFRESH_TOKEN_NOT_ROTATED',
  'OAUTH_REFRESH_SCOPE_WIDENED',
  'OAUTH_TOKEN_RESPONSE_INVALID',
]);

interface ManagedMcpConnectionPort extends McpConnectionTestPort {
  close(): Promise<void>;
}

export interface SignOutResult {
  readonly transportClosure: 'CONFIRMED' | 'UNCONFIRMED';
  readonly remoteRevocation: 'CONFIRMED' | 'NOT_REQUIRED' | 'UNCONFIRMED';
}

export class AuthorizedMcpSession {
  private boundProfileId?: string;
  private boundAccessToken?: string;
  private renewal?: { readonly profileId: string; readonly promise: Promise<ConnectionEvidence | undefined> };
  private scheduledRefresh?: unknown;

  constructor(
    private readonly endpoints: EndpointValidationPort,
    private readonly authentication: RenewableAuthenticationPort,
    private readonly connection: ManagedMcpConnectionPort,
    private readonly now: () => Date = () => new Date(),
    private readonly timers: SessionTimerPort = SYSTEM_TIMERS,
    private readonly observer: AuthorizedSessionObserver = NOOP_OBSERVER,
  ) {}

  bind(profile: McpConnectionProfile, session: OAuthSession): void {
    if (session.renewal.kind !== 'ROTATING_REFRESH_TOKEN') {
      throw new ConnectionContractError('OAUTH_SESSION_NOT_RENEWABLE', 'OAUTH_SESSION_NOT_RENEWABLE');
    }
    this.boundProfileId = profile.id;
    this.boundAccessToken = session.accessToken;
    this.scheduleRefresh(profile, session);
  }

  unbind(): void {
    if (this.scheduledRefresh !== undefined) this.timers.cancel(this.scheduledRefresh);
    this.scheduledRefresh = undefined;
    this.boundProfileId = undefined;
    this.boundAccessToken = undefined;
  }

  ensure(
    profile: McpConnectionProfile,
    requiredScopes: readonly PocketHiveMcpScope[] = [],
    signal: AbortSignal = new AbortController().signal,
  ): Promise<ConnectionEvidence | undefined> {
    if (this.renewal) {
      if (this.renewal.profileId !== profile.id) {
        throw new ConnectionContractError('OAUTH_SESSION_PROFILE_CONFLICT', 'OAUTH_SESSION_PROFILE_CONFLICT');
      }
      return this.joinRenewalWithScopeCheck(profile, requiredScopes);
    }
    const promise = this.performEnsure(profile, requiredScopes, signal).finally(() => {
      this.renewal = undefined;
    });
    this.renewal = { profileId: profile.id, promise };
    return promise;
  }

  async signIn(
    profile: McpConnectionProfile,
    signal: AbortSignal = new AbortController().signal,
  ): Promise<ConnectionEvidence> {
    this.requireIdle();
    const endpoint = await this.endpoints.validate(profile);
    const session = await this.authentication.authenticate(profile, endpoint, signal);
    const evidence = await this.connection.test(profile, session, signal);
    this.bind(profile, session);
    return evidence;
  }

  async signOut(
    profile: McpConnectionProfile,
    signal: AbortSignal = new AbortController().signal,
  ): Promise<SignOutResult> {
    this.requireIdle();
    this.unbind();
    let transportClosure: SignOutResult['transportClosure'] = 'CONFIRMED';
    try {
      await this.connection.close();
    } catch {
      transportClosure = 'UNCONFIRMED';
    }
    const session = await this.authentication.session(profile);
    let remoteRevocation: SignOutResult['remoteRevocation'] = session ? 'CONFIRMED' : 'NOT_REQUIRED';
    if (session) {
      try {
        const endpoint = await this.endpoints.validate(profile);
        await this.authentication.revoke(profile, endpoint, session, signal);
      } catch {
        remoteRevocation = 'UNCONFIRMED';
      }
    }
    await this.authentication.clear(profile);
    return { transportClosure, remoteRevocation };
  }

  private async performEnsure(
    profile: McpConnectionProfile,
    requiredScopes: readonly PocketHiveMcpScope[],
    signal: AbortSignal,
  ): Promise<ConnectionEvidence | undefined> {
    let session = await this.authentication.session(profile);
    if (!session || session.renewal.kind !== 'ROTATING_REFRESH_TOKEN') {
      this.unbind();
      throw new AuthenticationExpiredError();
    }
    requireScopes(session, requiredScopes);
    const bound = this.boundProfileId === profile.id && this.boundAccessToken === session.accessToken;
    const endpoint = bound && Date.parse(session.expiresAt) > this.now().getTime() + SESSION_RENEWAL_SKEW_MS
      ? undefined
      : await this.endpoints.validate(profile);
    if (!endpoint) return undefined;
    if (Date.parse(session.expiresAt) <= this.now().getTime() + SESSION_RENEWAL_SKEW_MS) {
      try {
        session = await this.authentication.refresh(profile, endpoint, session, signal);
      } catch (error) {
        if (definitiveRefreshFailure(error)) {
          this.unbind();
          await this.authentication.clear(profile);
        }
        throw error;
      }
      requireScopes(session, requiredScopes);
    }
    const evidence = await this.connection.test(profile, session, signal);
    this.bind(profile, session);
    return evidence;
  }

  private requireIdle(): void {
    if (this.renewal) {
      throw new ConnectionContractError('OAUTH_SESSION_TRANSITION_IN_PROGRESS', 'OAUTH_SESSION_TRANSITION_IN_PROGRESS');
    }
  }

  private async joinRenewalWithScopeCheck(
    profile: McpConnectionProfile,
    requiredScopes: readonly PocketHiveMcpScope[],
  ): Promise<ConnectionEvidence | undefined> {
    const evidence = await this.renewal!.promise;
    if (requiredScopes.length === 0) return evidence;
    const session = await this.authentication.session(profile);
    if (!session || session.renewal.kind !== 'ROTATING_REFRESH_TOKEN') {
      this.unbind();
      throw new AuthenticationExpiredError();
    }
    requireScopes(session, requiredScopes);
    return evidence;
  }

  private scheduleRefresh(profile: McpConnectionProfile, session: OAuthSession): void {
    if (this.scheduledRefresh !== undefined) this.timers.cancel(this.scheduledRefresh);
    const delay = Math.max(0, Date.parse(session.expiresAt) - this.now().getTime() - SESSION_RENEWAL_SKEW_MS);
    this.scheduledRefresh = this.timers.schedule(delay, async () => {
      try {
        const evidence = await this.ensure(profile);
        if (evidence) this.observer.renewed(profile, evidence);
      } catch (error) {
        this.observer.unavailable(profile, error);
        if (!definitiveRefreshFailure(error) && this.boundProfileId === profile.id) {
          this.scheduledRefresh = this.timers.schedule(SESSION_REFRESH_RETRY_MS, async () => {
            await this.runScheduledRetry(profile);
          });
        }
      }
    });
  }

  private async runScheduledRetry(profile: McpConnectionProfile): Promise<void> {
    try {
      const evidence = await this.ensure(profile);
      if (evidence) this.observer.renewed(profile, evidence);
    } catch (error) {
      this.observer.unavailable(profile, error);
    }
  }
}

function requireScopes(session: OAuthSession, requiredScopes: readonly PocketHiveMcpScope[]): void {
  if (requiredScopes.some(scope => !session.scopes.includes(scope))) {
    throw new ConnectionContractError('OAUTH_SCOPE_NOT_GRANTED', 'OAUTH_SCOPE_NOT_GRANTED');
  }
}

function definitiveRefreshFailure(error: unknown): boolean {
  return error instanceof AuthenticationExpiredError
    || (error instanceof ConnectionContractError && DEFINITIVE_REFRESH_FAILURES.has(error.code));
}
