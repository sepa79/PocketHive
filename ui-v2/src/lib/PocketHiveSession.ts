import type { PocketSession } from '@pockethive/browser-auth'
import type { AuthenticatedUser } from './authIdentity'

export interface PocketHiveSession extends PocketSession {
  user: AuthenticatedUser | null
  canEdit: false
}
