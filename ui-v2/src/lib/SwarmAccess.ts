/** Read-only permission projection from GET /api/access/swarms; not lifecycle eligibility. */
export type SwarmAccess = {
  swarmId: string
  canRun: boolean
  canManage: boolean
}
