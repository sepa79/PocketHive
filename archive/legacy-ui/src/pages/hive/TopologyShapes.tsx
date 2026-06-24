// @ts-nocheck
import { Fragment, useCallback, useMemo } from 'react'
import { Handle, Position, type NodeProps } from '@xyflow/react'

export type NodeShape =
  | 'circle'
  | 'square'
  | 'triangle'
  | 'diamond'
  | 'pentagon'
  | 'hexagon'
  | 'star'

export const DEFAULT_COLOR = '#60a5fa'
export const DISABLED_COLOR = '#64748b'

export interface ShapeNodeData {
  label: string
  shape: NodeShape
  enabled?: boolean
  swarmId?: string
  componentType?: string
  componentId?: string
  status?: string
  meta?: Record<string, unknown>
  role?: string
  fill?: string
  [key: string]: unknown
}

export interface SwarmGroupComponentData {
  id: string
  name: string
  shape: NodeShape
  enabled?: boolean
  componentType?: string
  fill?: string
  abbreviation?: string
  queueCount?: number
  tps?: number
}

export interface SwarmGroupEdgeData {
  source: string
  target: string
  queue: string
  depth: number
}

export interface SwarmGroupNodeData {
  label: string
  swarmId: string
  controllerId: string
  components: SwarmGroupComponentData[]
  edges: SwarmGroupEdgeData[]
  onDetails?: (swarmId: string) => void
  selectedId?: string
}

function formatMetaValue(value: unknown): string | null {
  if (value === undefined || value === null) return null
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (typeof value === 'number') return value.toString()
  if (typeof value === 'string') return value
  return null
}

function abbreviateName(name: string | undefined): string {
  if (!name) return ''
  return name
    .split(/[-_\s]+/)
    .filter((part) => part.length > 0)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('')
    .slice(0, 2)
}

function shortInstanceName(id: string | undefined): string {
  if (!id) return ''
  const parts = id
    .split(/[-_\s]+/)
    .filter((part) => part.length > 0)
  if (parts.length <= 3) {
    return id
  }
  return parts.slice(-3).join('-')
}

function getString(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : undefined
}

function getNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!trimmed) return undefined
    const parsed = Number(trimmed)
    return Number.isFinite(parsed) ? parsed : undefined
  }
  return undefined
}

function polygonPoints(sides: number, r: number) {
  const cx = r
  const cy = r
  const pts: string[] = []
  for (let i = 0; i < sides; i++) {
    const angle = -Math.PI / 2 + (2 * Math.PI * i) / sides
    pts.push(`${cx + r * Math.cos(angle)},${cy + r * Math.sin(angle)}`)
  }
  return pts.join(' ')
}

function starPoints(r: number) {
  const outer = r
  const inner = r / 2
  const cx = r
  const cy = r
  const pts: string[] = []
  let rot = -Math.PI / 2
  const step = Math.PI / 5
  for (let i = 0; i < 10; i++) {
    const radius = i % 2 === 0 ? outer : inner
    pts.push(`${cx + Math.cos(rot) * radius},${cy + Math.sin(rot) * radius}`)
    rot += step
  }
  return pts.join(' ')
}

export function ShapeNode({ data, selected }: NodeProps<ShapeNodeData>) {
  const size = 10
  const fill =
    data.enabled === false
      ? DISABLED_COLOR
      : typeof data.fill === 'string' && data.fill.trim().length > 0
      ? data.fill
      : DEFAULT_COLOR
  const isOrchestrator = data.componentType === 'orchestrator'
  const role = data.role || data.componentType
  const componentId =
    typeof data.componentId === 'string' ? data.componentId.trim() : ''
  const fallbackLabel = typeof data.label === 'string' ? data.label : ''
  const meta =
    data.meta && typeof data.meta === 'object'
      ? (data.meta as Record<string, unknown>)
      : undefined

  const cfg =
    meta && typeof meta === 'object' ? (meta as Record<string, unknown>) : undefined
  const inputs =
    cfg && cfg.inputs && typeof cfg.inputs === 'object'
      ? (cfg.inputs as Record<string, unknown>)
      : undefined
  const inputType = inputs ? getString(inputs.type) : undefined
  const outputs =
    cfg && cfg.outputs && typeof cfg.outputs === 'object'
      ? (cfg.outputs as Record<string, unknown>)
      : undefined
  const outputType = outputs ? getString(outputs.type) : undefined

  const rawTps = meta?.tps
  const numericTps = getNumber(rawTps)
  const tps =
    typeof numericTps === 'number' && Number.isFinite(numericTps)
      ? Math.round(numericTps)
      : undefined

  const metaEntries = useMemo(() => {
    if (!isOrchestrator) {
      return []
    }
    const meta =
      data.meta && typeof data.meta === 'object'
        ? (data.meta as Record<string, unknown>)
        : undefined
    const entries: { key: string; value: string }[] = []
    const swarmCountValue =
      meta?.swarmCount ??
      meta?.activeSwarmCount ??
      meta?.activeSwarms ??
      meta?.['swarm-count'] ??
      meta?.['active-swarms']
    const formattedSwarmCount = formatMetaValue(swarmCountValue)
    if (formattedSwarmCount !== null) {
      entries.push({ key: 'Active swarms', value: formattedSwarmCount })
    }
    const guard =
      meta && meta.bufferGuard && typeof meta.bufferGuard === 'object'
        ? (meta.bufferGuard as Record<string, unknown>)
        : undefined
    if (guard) {
      const active = getString(guard.active)
      const problem = getString(guard.problem)
      if (active) {
        entries.push({
          key: 'Buffer guard',
          value: active.toLowerCase() === 'true' ? 'active' : 'inactive',
        })
      }
      if (problem) {
        entries.push({
          key: 'Guard problem',
          value: problem,
        })
      }
    }
    return entries
  }, [data.meta, isOrchestrator])

  const shortName = shortInstanceName(componentId || fallbackLabel)

  return (
    <div
      className={`shape-node${selected ? ' selected' : ''}${
        isOrchestrator ? ' shape-node--orchestrator' : ''
      }`}
      title={componentId || fallbackLabel}
    >
      {!isOrchestrator && typeof tps === 'number' && (
        <div className="shape-node__badge" title="Throughput (TPS)">
          {tps}
        </div>
      )}
      <Handle type="target" position={Position.Left} />
      <svg className="shape-icon" width={2 * size} height={2 * size}>
        {data.shape === 'square' && (
          <rect x={0} y={0} width={2 * size} height={2 * size} fill={fill} stroke="black" />
        )}
        {data.shape === 'triangle' && (
          <polygon
            points={`${size},0 ${2 * size},${2 * size} 0,${2 * size}`}
            fill={fill}
            stroke="black"
          />
        )}
        {data.shape === 'diamond' && (
          <polygon
            points={`${size},0 ${2 * size},${size} ${size},${2 * size} 0,${size}`}
            fill={fill}
            stroke="black"
          />
        )}
        {data.shape === 'pentagon' && (
          <polygon points={polygonPoints(5, size)} fill={fill} stroke="black" />
        )}
        {data.shape === 'hexagon' && (
          <polygon points={polygonPoints(6, size)} fill={fill} stroke="black" />
        )}
        {data.shape === 'star' && <polygon points={starPoints(size)} fill={fill} stroke="black" />}
        {data.shape === 'circle' && <circle cx={size} cy={size} r={size} fill={fill} stroke="black" />}
      </svg>
      <div className="shape-node__content">
        {role && <span className="shape-node__role">{role}</span>}
        {shortName && <span className="label">{shortName}</span>}
        {!isOrchestrator && inputType && (
          <span className="shape-node__role">Input: {inputType}</span>
        )}
        {!isOrchestrator && outputType && (
          <span className="shape-node__role">Output: {outputType}</span>
        )}
        {isOrchestrator && metaEntries.length > 0 && (
          <dl className="shape-node__meta">
            {metaEntries.map((entry) => (
              <Fragment key={`${entry.key}:${entry.value}`}>
                <dt className="shape-node__meta-term">{entry.key}</dt>
                <dd className="shape-node__meta-value">{entry.value}</dd>
              </Fragment>
            ))}
          </dl>
        )}
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

export function SwarmGroupNode({ data }: NodeProps<SwarmGroupNodeData>) {
  const size = 180
  const center = size / 2
  const controller = data.components.find((c) => c.id === data.controllerId)
  const ringMembers = controller
    ? data.components.filter((c) => c.id !== controller.id)
    : data.components
  const ringRadius =
    ringMembers.length > 1 || controller ? Math.min(center - 24, 60) : 0

  const placements = useMemo(() => {
    const list: (SwarmGroupComponentData & { x: number; y: number })[] = []
    if (controller) {
      list.push({ ...controller, x: center, y: center })
    }
    if (ringMembers.length === 0 && !controller && data.components[0]) {
      list.push({ ...data.components[0], x: center, y: center })
      return list
    }
    const denominator = Math.max(ringMembers.length, 1)
    ringMembers.forEach((comp, idx) => {
      const angle = -Math.PI / 2 + (2 * Math.PI * idx) / denominator
      const radius = ringRadius
      const x = radius ? center + radius * Math.cos(angle) : center
      const y = radius ? center + radius * Math.sin(angle) : center
      list.push({ ...comp, x, y })
    })
    return list
  }, [center, controller, data.components, ringMembers, ringRadius])

  const byId = useMemo(() => {
    const map = new Map<string, (SwarmGroupComponentData & { x: number; y: number })>()
    placements.forEach((p) => map.set(p.id, p))
    return map
  }, [placements])

  const hasSelected = data.selectedId
    ? data.components.some((c) => c.id === data.selectedId)
    : false

  const renderShape = useCallback(
    (comp: SwarmGroupComponentData & { x: number; y: number }) => {
      const fill =
        comp.enabled === false
          ? DISABLED_COLOR
          : typeof comp.fill === 'string' && comp.fill.trim().length > 0
          ? comp.fill
          : DEFAULT_COLOR
      const iconRadius = comp.id === data.controllerId ? 14 : 11
      const abbreviation =
        typeof comp.abbreviation === 'string' && comp.abbreviation.trim().length > 0
          ? comp.abbreviation.trim()
          : abbreviateName(comp.name)
      const tps =
        typeof comp.tps === 'number' && Number.isFinite(comp.tps)
          ? Math.round(comp.tps)
          : undefined
      return (
        <g key={comp.id}>
          {comp.id === data.selectedId && (
            <circle
              className="swarm-group__selection"
              cx={comp.x}
              cy={comp.y}
              r={iconRadius + 6}
            />
          )}
          {comp.shape === 'square' && (
            <rect
              x={comp.x - iconRadius}
              y={comp.y - iconRadius}
              width={iconRadius * 2}
              height={iconRadius * 2}
              fill={fill}
              stroke="black"
            />
          )}
          {comp.shape === 'triangle' && (
            <polygon
              points={`${comp.x},${comp.y - iconRadius} ${comp.x + iconRadius},${comp.y + iconRadius} ${comp.x - iconRadius},${comp.y + iconRadius}`}
              fill={fill}
              stroke="black"
            />
          )}
          {comp.shape === 'diamond' && (
            <polygon
              points={`${comp.x},${comp.y - iconRadius} ${comp.x + iconRadius},${comp.y} ${comp.x},${comp.y + iconRadius} ${comp.x - iconRadius},${comp.y}`}
              fill={fill}
              stroke="black"
            />
          )}
          {comp.shape === 'pentagon' && (
            <polygon
              points={polygonPoints(5, iconRadius)
                .split(' ')
                .map((pair) => {
                  const [px, py] = pair.split(',').map(Number)
                  return `${px - iconRadius + comp.x},${py - iconRadius + comp.y}`
                })
                .join(' ')}
              fill={fill}
              stroke="black"
            />
          )}
          {comp.shape === 'hexagon' && (
            <polygon
              points={polygonPoints(6, iconRadius)
                .split(' ')
                .map((pair) => {
                  const [px, py] = pair.split(',').map(Number)
                  return `${px - iconRadius + comp.x},${py - iconRadius + comp.y}`
                })
                .join(' ')}
              fill={fill}
              stroke="black"
            />
          )}
          {comp.shape === 'star' && (
            <polygon
              points={starPoints(iconRadius)
                .split(' ')
                .map((pair) => {
                  const [px, py] = pair.split(',').map(Number)
                  return `${px - iconRadius + comp.x},${py - iconRadius + comp.y}`
                })
                .join(' ')}
              fill={fill}
              stroke="black"
            />
          )}
          {comp.shape === 'circle' && (
            <circle cx={comp.x} cy={comp.y} r={iconRadius} fill={fill} stroke="black" />
          )}
          <text
            x={comp.x}
            y={comp.y + 3}
            textAnchor="middle"
            className="swarm-group__icon-label"
          >
            {abbreviation || '?'}
          </text>
          {typeof tps === 'number' && (
            <>
              <rect
                x={comp.x + iconRadius - 10}
                y={comp.y - iconRadius - 3}
                width={18}
                height={10}
                rx={6}
                ry={6}
                fill="rgba(15,23,42,0.9)"
                stroke="#38bdf8"
                strokeWidth={0.7}
              />
              <text
                x={comp.x + iconRadius - 1}
                y={comp.y - iconRadius + 6}
                textAnchor="middle"
                className="swarm-group__icon-label"
              >
                {tps}
              </text>
            </>
          )}
        </g>
      )
    },
    [data.controllerId, data.selectedId],
  )

  return (
    <div className={`swarm-group${hasSelected ? ' selected' : ''}`}>
      <Handle
        type="target"
        position={Position.Left}
        className="swarm-group__handle swarm-group__handle--target"
      />
      <div className="swarm-group__header">
        <span className="swarm-group__title">{data.label}</span>
        <button
          className="swarm-group__details"
          onClick={(e) => {
            e.stopPropagation()
            data.onDetails?.(data.swarmId)
          }}
        >
          Details
        </button>
      </div>
      <svg
        className="swarm-group__svg"
        viewBox={`0 0 ${size} ${size}`}
        preserveAspectRatio="xMidYMid meet"
      >
        {data.edges.map((edge) => {
          const from = byId.get(edge.source)
          const to = byId.get(edge.target)
          if (!from || !to) return null
          const color = edge.depth > 0 ? '#ff6666' : '#66aaff'
          const width = 1 + Math.log(edge.depth + 1)
          return (
            <line
              key={`${edge.source}-${edge.target}-${edge.queue}`}
              x1={from.x}
              y1={from.y}
              x2={to.x}
              y2={to.y}
              stroke={color}
              strokeWidth={width}
              strokeLinecap="round"
              className="swarm-group__edge"
            />
          )
        })}
        {placements.map((comp) => renderShape(comp))}
      </svg>
      <Handle
        type="source"
        position={Position.Right}
        className="swarm-group__handle swarm-group__handle--source"
      />
    </div>
  )
}
