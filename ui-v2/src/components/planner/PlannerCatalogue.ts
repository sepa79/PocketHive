export type PlannerCatalogue = {
  schemaVersion: 1
  instanceId: string
  revision: number
  plans: Array<{
    id: string
    name: string
    testPlan: string
    streams: Array<{ id: string; name: string }>
    activities: Array<{ id: string; name: string; streamId: string; tag: string }>
  }>
}
