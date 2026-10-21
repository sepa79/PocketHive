/**
 * Responsibility: describe Hive's read-only subset of the authored Scenario response.
 * Must not: redefine authoring validation, runtime identity or command permissions.
 * Contract: RESP-UI-SWARM-DETAIL — docs/architecture/runtime-responsibilities.md#resp-ui-swarm-detail; docs/scenarios/SCENARIO_CONTRACT.md.
 */
export type HiveScenario = {
  id: string | null
  name: string | null
  description: string | null
  template?: {
    image: string | null
    bees?: {
      role: string | null
      image: string | null
      work: {
        in: Record<string, string> | undefined
        out: Record<string, string> | undefined
      } | null
      ports: { id: string; direction: 'in' | 'out' }[] | null
    }[]
  } | null
  topology?: {
    version: number | null
    edges?: {
      id: string | null
      from: { role: string | null; port: string | null } | null
      to: { role: string | null; port: string | null } | null
    }[]
  } | null
}
