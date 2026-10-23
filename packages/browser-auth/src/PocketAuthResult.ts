import type { PocketAuthSnapshot } from './PocketAuthSnapshot.js'
import type { PocketSession } from './PocketSession.js'

export interface PocketAuthResult<Session extends PocketSession = PocketSession> extends PocketAuthSnapshot<Session> {
  signIn: () => Promise<void>
  cancelSignIn: () => void
  openConfirmation: () => void
  signOut: () => Promise<void>
  refresh: () => Promise<void>
  getAccessToken: () => string | null
  fetchWithAuth: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>
}
