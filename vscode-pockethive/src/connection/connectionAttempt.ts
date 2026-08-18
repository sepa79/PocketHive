import {
  AuthenticationCancelledError,
  AuthenticationExpiredError,
  AuthenticationPort,
  ConnectionAttemptObserver,
  ConnectionAttemptState,
  ConnectionAttemptView,
  ConnectionContractError,
  ConnectionEvidence,
  ConnectionFailure,
  EndpointValidationPort,
  McpConnectionProfile,
  McpConnectionTestPort,
  OAuthSession,
  ValidatedEndpoint,
} from './contracts';

export class ConnectionAttempt {
  private state: ConnectionAttemptState = 'EDITING';
  private endpointValidated = false;
  private authenticated = false;
  private failure?: ConnectionFailure;
  private evidence?: ConnectionEvidence;
  private endpoint?: ValidatedEndpoint;
  private activeAbort?: AbortController;

  constructor(
    private readonly profile: McpConnectionProfile,
    private readonly endpoints: EndpointValidationPort,
    private readonly authentication: AuthenticationPort,
    private readonly mcp: McpConnectionTestPort,
    private readonly observer: ConnectionAttemptObserver,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async connect(): Promise<ConnectionAttemptView> {
    this.requireState('EDITING');
    const operation = this.beginOperation();
    try {
      let endpoint: ValidatedEndpoint;
      try {
        endpoint = await this.endpoints.validate(this.profile);
        this.endpoint = endpoint;
        this.endpointValidated = true;
      } catch (error) {
        this.failure = failure(error, this.now());
        return this.emit();
      }
      this.transition('AUTHENTICATING');
      let session: OAuthSession;
      try {
        session = await this.authentication.authenticate(this.profile, endpoint, operation.signal);
        this.authenticated = true;
      } catch (error) {
        if (error instanceof AuthenticationCancelledError || operation.signal.aborted) {
          this.cancelled();
        } else {
          this.fail('AUTHENTICATION_FAILED', error);
        }
        return this.view();
      }
      return this.testSession(session, operation.signal);
    } finally {
      this.endOperation(operation);
    }
  }

  async retryTest(): Promise<ConnectionAttemptView> {
    this.requireState('CONNECTION_TEST_FAILED');
    const operation = this.beginOperation();
    try {
      const session = await this.authentication.session(this.profile);
      if (!session || Date.parse(session.expiresAt) <= this.now().getTime()) {
        this.authenticated = false;
        this.fail('AUTHENTICATION_FAILED', new AuthenticationExpiredError());
        return this.view();
      }
      return this.testSession(session, operation.signal);
    } finally {
      this.endOperation(operation);
    }
  }

  async reconnect(): Promise<ConnectionAttemptView> {
    this.requireState('EDITING');
    const operation = this.beginOperation();
    try {
      try {
        this.endpoint = await this.endpoints.validate(this.profile);
        this.endpointValidated = true;
      } catch (error) {
        this.failure = failure(error, this.now());
        return this.emit();
      }
      const session = await this.authentication.session(this.profile);
      if (!session || Date.parse(session.expiresAt) <= this.now().getTime()) {
        this.authenticated = false;
        this.fail('AUTHENTICATION_FAILED', new AuthenticationExpiredError());
        return this.view();
      }
      this.authenticated = true;
      return this.testSession(session, operation.signal);
    } finally {
      this.endOperation(operation);
    }
  }

  async signInAgain(): Promise<ConnectionAttemptView> {
    this.requireState('AUTHENTICATION_FAILED');
    const operation = this.beginOperation();
    try {
      this.authenticated = false;
      this.failure = undefined;
      this.evidence = undefined;
      this.transition('AUTHENTICATING');
      try {
        const session = await this.authentication.authenticate(this.profile, this.endpoint!, operation.signal);
        this.authenticated = true;
        return this.testSession(session, operation.signal);
      } catch (error) {
        if (error instanceof AuthenticationCancelledError || operation.signal.aborted) {
          this.cancelled();
        } else {
          this.fail('AUTHENTICATION_FAILED', error);
        }
        return this.view();
      }
    } finally {
      this.endOperation(operation);
    }
  }

  save(): ConnectionAttemptView {
    this.requireState('READY_TO_SAVE');
    this.transition('SAVED');
    return this.view();
  }

  cancel(): ConnectionAttemptView {
    if (!['AUTHENTICATING', 'TESTING', 'READY_TO_SAVE'].includes(this.state)) {
      throw new ConnectionContractError('CONNECTION_ATTEMPT_TRANSITION_INVALID', this.state);
    }
    this.activeAbort?.abort();
    this.cancelled();
    return this.view();
  }

  view(): ConnectionAttemptView {
    return Object.freeze({
      profileId: this.profile.id,
      state: this.state,
      endpointValidated: this.endpointValidated,
      authenticated: this.authenticated,
      failure: this.failure,
      evidence: this.evidence,
    });
  }

  private async testSession(session: OAuthSession, signal: AbortSignal): Promise<ConnectionAttemptView> {
    if (signal.aborted) return this.view();
    this.transition('TESTING');
    try {
      this.evidence = await this.mcp.test(this.profile, session, signal);
      if (signal.aborted) return this.view();
      this.failure = undefined;
      this.transition('READY_TO_SAVE');
    } catch (error) {
      if (signal.aborted) return this.view();
      if (error instanceof AuthenticationExpiredError) {
        this.authenticated = false;
        this.fail('AUTHENTICATION_FAILED', error);
      } else {
        this.fail('CONNECTION_TEST_FAILED', error);
      }
    }
    return this.view();
  }

  private beginOperation(): AbortController {
    if (this.activeAbort) {
      throw new ConnectionContractError('CONNECTION_ATTEMPT_ALREADY_RUNNING', this.state);
    }
    const operation = new AbortController();
    this.activeAbort = operation;
    return operation;
  }

  private endOperation(operation: AbortController): void {
    this.activeAbort = undefined;
  }

  private cancelled(): void {
    if (this.state !== 'CANCELLED') this.transition('CANCELLED');
  }

  private fail(state: 'AUTHENTICATION_FAILED' | 'CONNECTION_TEST_FAILED', error: unknown): void {
    this.failure = failure(error, this.now());
    this.transition(state);
  }

  private requireState(expected: ConnectionAttemptState): void {
    if (this.state !== expected) {
      throw new ConnectionContractError(
        'CONNECTION_ATTEMPT_TRANSITION_INVALID',
        `Expected ${expected}, found ${this.state}`,
      );
    }
  }

  private transition(next: ConnectionAttemptState): void {
    this.state = next;
    this.emit();
  }

  private emit(): ConnectionAttemptView {
    const view = this.view();
    this.observer.changed(view);
    return view;
  }
}

function failure(error: unknown, now: Date): ConnectionFailure {
  if (error instanceof ConnectionContractError) {
    return { code: error.code, message: error.message, observedAt: now.toISOString() };
  }
  if (error instanceof Error) {
    return { code: error.name, message: error.message, observedAt: now.toISOString() };
  }
  return { code: 'CONNECTION_FAILED', message: String(error), observedAt: now.toISOString() };
}
