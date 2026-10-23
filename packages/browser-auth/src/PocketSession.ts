export interface PocketSession {
  user: { username: string; displayName: string } | null
  canEdit: boolean
  configured: boolean
}
