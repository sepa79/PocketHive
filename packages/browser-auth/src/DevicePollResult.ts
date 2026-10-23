import type { StoredToken } from './StoredToken.js'

export type DevicePollResult =
  | { kind: 'authorized'; token: StoredToken }
  | { kind: 'authorization_pending' | 'slow_down' | 'access_denied' | 'expired_token' }
