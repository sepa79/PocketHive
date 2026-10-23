import type { HiveScenario } from './HiveScenario'

type ScenarioBee = NonNullable<NonNullable<HiveScenario['template']>['bees']>[number]
type ScenarioTopologyEdge = NonNullable<NonNullable<HiveScenario['topology']>['edges']>[number]

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function toStringOrNull(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null
}

/**
 * Responsibility: project authored scenario fields for Hive detail presentation.
 * Must not: redesign the Scenario API or substitute authoring images for runtime identity.
 * Contract: RESP-UI-SWARM-DETAIL — docs/architecture/runtime-responsibilities.md#resp-ui-swarm-detail; docs/scenarios/SCENARIO_CONTRACT.md.
 */
export function parseHiveScenario(data: unknown, requestedId: string): HiveScenario {
  if (!isRecord(data) || Array.isArray(data)) {
    throw new Error(`Invalid scenario response for '${requestedId}'.`)
  }
  const id = toStringOrNull(data.id)
  if (id !== requestedId) {
    throw new Error(`Scenario response does not match requested scenario '${requestedId}'.`)
  }
  const template = isRecord(data.template) ? data.template : null
  const bees = Array.isArray(template?.bees)
    ? (template?.bees as unknown[])
        .map((bee) => {
          if (!isRecord(bee)) return null
          const work = isRecord(bee.work) ? bee.work : null
          const workIn = isRecord(work?.in) ? (work?.in as Record<string, string>) : undefined
          const workOut = isRecord(work?.out) ? (work?.out as Record<string, string>) : undefined
          const ports = Array.isArray(bee.ports)
            ? (bee.ports as unknown[])
                .map((port) => {
                  if (!isRecord(port)) return null
                  const id = toStringOrNull(port.id)
                  const direction = toStringOrNull(port.direction)
                  if (!id || (direction !== 'in' && direction !== 'out')) return null
                  return { id, direction }
                })
                .filter((port): port is { id: string; direction: 'in' | 'out' } => port !== null)
            : null
          return {
            role: toStringOrNull(bee.role),
            image: toStringOrNull(bee.image),
            work: workIn || workOut ? { in: workIn, out: workOut } : null,
            ports,
          }
        })
        .filter((bee): bee is ScenarioBee => bee !== null)
    : undefined
  const topology = isRecord(data.topology) ? data.topology : null
  const edges = Array.isArray(topology?.edges)
    ? (topology?.edges as unknown[])
        .map((edge) => {
          if (!isRecord(edge)) return null
          const from = isRecord(edge.from) ? edge.from : null
          const to = isRecord(edge.to) ? edge.to : null
          return {
            id: toStringOrNull(edge.id),
            from: from
              ? { role: toStringOrNull(from.role), port: toStringOrNull(from.port) }
              : null,
            to: to ? { role: toStringOrNull(to.role), port: toStringOrNull(to.port) } : null,
          }
        })
        .filter((edge): edge is ScenarioTopologyEdge => edge !== null)
    : undefined

  return {
    id,
    name: toStringOrNull(data.name),
    description: toStringOrNull(data.description),
    template: template
      ? {
          image: toStringOrNull(template.image),
          bees,
        }
      : null,
    topology: topology
      ? {
          version: typeof topology.version === 'number' ? topology.version : null,
          edges,
        }
      : null,
  }
}
