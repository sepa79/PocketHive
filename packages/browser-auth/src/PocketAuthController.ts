import { authenticatedRequest } from './authenticatedRequest.js'
import { BrowserTokenStore } from './BrowserTokenStore.js'
import { ConfirmationTab } from './ConfirmationTab.js'
import { pollDeviceToken } from './DeviceTokenPoller.js'
import { PocketAuthError } from './PocketAuthError.js'
import type { PocketAuthSnapshot } from './PocketAuthSnapshot.js'
import type { PocketSession } from './PocketSession.js'
import { scheduleDeadline } from './scheduleDeadline.js'
import type { SessionApiPort } from './SessionApiPort.js'
import type { StoredToken } from './StoredToken.js'

/**
 * Responsibility: own one mounted browser credential lifecycle and its session projection.
 * Must not: issue tokens, schedule device polls, select product permissions or render UI.
 * Contract: RESP-POCKET-BROWSER-AUTH — packages/browser-auth/README.md#resp-pocket-browser-auth.
 */
export class PocketAuthController<Session extends PocketSession = PocketSession> {
  private snapshot: PocketAuthSnapshot<Session>
  private readonly listeners = new Set<() => void>()
  private readonly store: BrowserTokenStore
  private readonly tab = new ConfirmationTab()
  private mounted = false
  private operation: AbortController | null = null
  private token: StoredToken | null = null
  private credentialInvalidation = 0
  private cancelExpiry: (() => void) | null = null

  constructor(storageKey: string, private readonly onError: (error: PocketAuthError) => void,
    private readonly api: SessionApiPort<Session>) {
    this.store = new BrowserTokenStore(storageKey)
    this.snapshot = {
      session: api.anonymousSession, loading: true,
      signingIn: false, confirmationUrl: null, error: null,
    }
  }

  getSnapshot = (): PocketAuthSnapshot<Session> => this.snapshot
  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  mount(): () => void {
    this.mounted = true
    void this.refresh()
    return () => {
      this.mounted = false
      this.operation?.abort()
      this.operation = null
      this.cancelExpiry?.()
      this.cancelExpiry = null
      this.tab.close()
    }
  }

  signIn = async (): Promise<void> => {
    const operation = this.begin()
    this.tab.reserve()
    this.publish({ loading: false, signingIn: true, confirmationUrl: null, error: null })
    try {
      const bearer = this.currentBearer()
      const grant = await this.api.start(bearer, operation.signal)
      if (!this.isCurrent(operation)) return
      this.publish({ confirmationUrl: grant.verificationUrl })
      try { this.tab.navigate(grant.verificationUrl) } catch (error) { this.report(error) }
      const token = await pollDeviceToken(this.api, grant, bearer, operation.signal)
      const session = await this.api.session(token.accessToken, operation.signal)
      if (!this.isCurrent(operation)) return
      this.install(token, session)
      this.tab.release()
      this.publish({ signingIn: false, confirmationUrl: null, error: null })
    } catch (error) {
      if (!this.isCurrent(operation)) return
      this.tab.close()
      this.publish({ signingIn: false, confirmationUrl: null })
      this.report(error)
    }
  }

  cancelSignIn = (): void => {
    if (!this.snapshot.signingIn) return
    this.operation?.abort()
    this.operation = null
    this.tab.close()
    this.publish({ signingIn: false, confirmationUrl: null, error: null })
  }

  openConfirmation = (): void => {
    const url = this.snapshot.confirmationUrl
    if (!url) return
    this.tab.reserve()
    try {
      this.tab.navigate(url)
      this.publish({ error: null })
      if (!this.snapshot.signingIn) this.tab.release()
    } catch (error) { this.report(error) }
  }

  signOut = async (): Promise<void> => {
    const bearer = this.currentBearer()
    const operation = this.begin()
    this.tab.reserve()
    this.publish({ loading: true, signingIn: false, confirmationUrl: null, error: null })
    try {
      const url = await this.api.logout(bearer, operation.signal)
      if (!this.isCurrent(operation)) return
      this.removeCredential()
      if (url) {
        this.publish({ confirmationUrl: url })
        try { this.tab.navigate(url); this.tab.release() } catch (error) { this.report(error) }
      } else this.tab.close()
    } catch (error) {
      if (!this.isCurrent(operation)) return
      this.tab.close()
      this.report(error)
    } finally {
      if (this.isCurrent(operation)) this.publish({ loading: false })
    }
  }

  fetchWithAuth = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const bearer = this.currentBearer()
    const response = await fetch(authenticatedRequest(input, init, bearer))
    if (this.mounted && response.status === 401 && bearer !== null && this.token?.accessToken === bearer) {
      try { this.removeCredential() } catch (error) { this.report(error) }
      this.report(new PocketAuthError('unauthorized', 'Your Pocket session is no longer valid. Sign in again.'))
    }
    return response
  }

  getAccessToken = (): string | null => this.token !== null && this.token.expiresAt > Date.now() ? this.token.accessToken : null

  refresh = async (): Promise<void> => {
    const operation = this.begin()
    this.publish({ loading: true, signingIn: false, error: null, confirmationUrl: null })
    try {
      const token = this.store.read()
      const validToken = token !== null && token.expiresAt > Date.now() ? token : null
      if (validToken === null) this.removeCredential()
      await this.restoreSession(validToken, operation)
    } catch (error) {
      if (!this.isCurrent(operation)) return
      if (error instanceof PocketAuthError && error.code === 'unauthorized') {
        try { this.removeCredential() } catch (storageError) { this.report(storageError) }
      }
      this.report(error)
    } finally {
      if (this.isCurrent(operation)) this.publish({ loading: false })
    }
  }

  private async restoreSession(token: StoredToken | null, operation: AbortController): Promise<void> {
    const invalidation = this.credentialInvalidation
    let session: Session
    try {
      session = await this.api.session(token?.accessToken ?? null, operation.signal)
    } catch (error) {
      if (this.isCurrent(operation) && invalidation === this.credentialInvalidation) throw error
      return
    }
    if (!this.isCurrent(operation) || invalidation !== this.credentialInvalidation) return
    if (token) this.install(token, session)
    else this.publish({ session })
  }

  private begin(): AbortController {
    this.operation?.abort()
    this.tab.close()
    const operation = new AbortController()
    this.operation = operation
    return operation
  }

  private isCurrent(operation: AbortController): boolean {
    return this.mounted && this.operation === operation && !operation.signal.aborted
  }

  private install(token: StoredToken, session: Session): void {
    if (!session.user) throw new PocketAuthError('protocol', 'PH did not confirm the signed-in identity.')
    if (token.expiresAt <= Date.now()) throw new PocketAuthError('session_expired', 'Your Pocket session expired. Sign in again.')
    this.store.write(token)
    this.token = token
    this.cancelExpiry?.()
    this.cancelExpiry = scheduleDeadline(token.expiresAt, () => {
      if (!this.mounted || this.token !== token) return
      try { this.removeCredential() } catch (error) { this.report(error) }
      this.report(new PocketAuthError('session_expired', 'Your Pocket session expired. Sign in again.'))
    })
    this.publish({ session })
  }

  private removeCredential(): void {
    this.credentialInvalidation++
    this.token = null
    this.cancelExpiry?.()
    this.cancelExpiry = null
    this.publish({ session: { ...this.snapshot.session, user: null, canEdit: false } })
    this.store.write(null)
  }

  private currentBearer(): string | null {
    if (this.token !== null && this.token.expiresAt <= Date.now()) {
      try { this.removeCredential() } catch (error) { this.report(error) }
      this.report(new PocketAuthError('session_expired', 'Your Pocket session expired. Sign in again.'))
    }
    return this.token?.accessToken ?? null
  }

  private publish(change: Partial<PocketAuthSnapshot<Session>>): void {
    this.snapshot = { ...this.snapshot, ...change }
    this.listeners.forEach(listener => listener())
  }

  private report(error: unknown): void {
    const failure = error instanceof PocketAuthError ? error
      : new PocketAuthError('request_failed', 'Pocket sign-in failed. Please try again.')
    this.publish({ error: failure })
    this.onError(failure)
  }
}
