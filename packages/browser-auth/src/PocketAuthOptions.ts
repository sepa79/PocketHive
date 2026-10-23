import type { PocketAuthError } from './PocketAuthError.js'
import type { PocketSession } from './PocketSession.js'
import type { SessionApiPort } from './SessionApiPort.js'

export interface PocketAuthOptions<Session extends PocketSession = PocketSession> {
  storageKey: string
  api?: SessionApiPort<Session>
  onError?: (error: PocketAuthError) => void
}
