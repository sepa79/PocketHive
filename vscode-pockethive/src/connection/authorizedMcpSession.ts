import {
  AuthenticationExpiredError,
  ConnectionContractError,
  ConnectionEvidence,
  EndpointValidationPort,
  McpConnectionProfile,
  McpConnectionTestPort,
  OAuthSession,
  RenewableAuthenticationPort,
} from './contracts';

export const SESSION_RENEWAL_SKEW_MS = 60_000;

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

  constructor(
    private readonly endpoints: EndpointValidationPort,
    private readonly authentication: RenewableAuthenticationPort,
    private readonly connection: ManagedMcpConnectionPort,
    private readonly now: () => Date = () => new Date(),
  ) {}

  bind(profile: McpConnectionProfile, session: OAuthSession): void {
    if (session.renewal.kind !== 'ROTATING_REFRESH_TOKEN') {
      throw new ConnectionContractError('OAUTH_SESSION_NOT_RENEWABLE', 'OAUTH_SESSION_NOT_RENEWABLE');
    }
    this.boundProfileId = profile.id;
    this.boundAccessToken = session.accessToken;
  }

  unbind(): void {
    this.boundProfileId = undefined;
    this.boundAccessToken = undefined;
  }

  ensure(
    profile: McpConnectionProfile,
    signal: AbortSignal = new AbortController().signal,
  ): Promise<ConnectionEvidence | undefined> {
    if (this.renewal) {
      if (this.renewal.profileId !== profile.id) {
        throw new ConnectionContractError('OAUTH_SESSION_PROFILE_CONFLICT', 'OAUTH_SESSION_PROFILE_CONFLICT');
      }
      return this.renewal.promise;
    }
    const promise = this.performEnsure(profile, signal).finally(() => {
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
    signal: AbortSignal,
  ): Promise<ConnectionEvidence | undefined> {
    let session = await this.authentication.session(profile);
    if (!session || session.renewal.kind !== 'ROTATING_REFRESH_TOKEN') {
      this.unbind();
      throw new AuthenticationExpiredError();
    }
    const bound = this.boundProfileId === profile.id && this.boundAccessToken === session.accessToken;
    const endpoint = bound && Date.parse(session.expiresAt) > this.now().getTime() + SESSION_RENEWAL_SKEW_MS
      ? undefined
      : await this.endpoints.validate(profile);
    if (!endpoint) return undefined;
    if (Date.parse(session.expiresAt) <= this.now().getTime() + SESSION_RENEWAL_SKEW_MS) {
      try {
        session = await this.authentication.refresh(profile, endpoint, session, signal);
      } catch (error) {
        this.unbind();
        await this.authentication.clear(profile);
        throw error;
      }
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
}
