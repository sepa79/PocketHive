import type { PocketSession } from './PocketSession.js'
import type { PocketAuthError } from './PocketAuthError.js'

export interface PocketAuthSnapshot<Session extends PocketSession = PocketSession> {
  session: Session
  loading: boolean
  signingIn: boolean
  confirmationUrl: string | null
  error: PocketAuthError | null
}
