import type { Topology } from '../../lib/stompClient'
import { normalizeSwarmId } from './TopologyUtils'

export interface GraphNode {
  id: string
  type: string
  x?: number
  y?: number
  enabled?: boolean
  swarmId?: string
}

export interface GraphLink {
  source: string
  target: string
  queue: string
}

export interface GraphData {
  nodes: GraphNode[]
  links: GraphLink[]
}

const FALLBACK_HORIZONTAL_SPACING = 280
const FALLBACK_VERTICAL_SPACING = 220

function average(values: number[]): number | undefined {
  if (!values.length) return undefined
  const total = values.reduce((sum, value) => sum + value, 0)
  return total / values.length
}

function compareNodes(a: GraphNode, b: GraphNode) {
  const typeCompare = (a.type || '').localeCompare(b.type || '')
  if (typeCompare !== 0) {
    return typeCompare
  }
  return (a.id || '').localeCompare(b.id || '')
}

export function buildGraph(
  topo: Topology,
  swarmId?: string,
): GraphData {
  const adjacency = new Map<string, string[]>()
  topo.edges.forEach((e) => {
    const arr = adjacency.get(e.from) ?? []
    arr.push(e.to)
    adjacency.set(e.from, arr)
  })

  const generators = topo.nodes.filter((n) => n.type === 'generator')
  const visited = new Set<string>()
  const order: string[] = []
  const q: string[] = generators.map((g) => g.id)
  while (q.length) {
    const id = q.shift()!
    if (visited.has(id)) continue
    visited.add(id)
    order.push(id)
    ;(adjacency.get(id) ?? []).forEach((next) => q.push(next))
  }

  const connectedNodes = order
    .map((id) => topo.nodes.find((n) => n.id === id))
    .filter((n): n is GraphNode => Boolean(n))
    .map((n) => ({ ...n }))

  const unconnectedNodes = topo.nodes
    .filter((n) => !visited.has(n.id))
    .map((n) => ({ ...n }))

  let nodes: GraphNode[] = [...connectedNodes, ...unconnectedNodes]
  if (swarmId) {
    const normalizedTarget = normalizeSwarmId(swarmId)
    nodes = normalizedTarget
      ? nodes.filter((n) => normalizeSwarmId(n.swarmId) === normalizedTarget)
      : []
  }

  const nodeSet = new Set(nodes.map((n) => n.id))
  const relevantLinks = topo.edges.filter(
    (edge) => nodeSet.has(edge.from) && nodeSet.has(edge.to),
  )

  const positionedNodes = applyFallbackPositions(
    nodes,
    relevantLinks.map((edge) => ({
      source: edge.from,
      target: edge.to,
      queue: edge.queue,
    })),
  )

  const ids = new Set(positionedNodes.map((n) => n.id))
  const links: GraphLink[] = relevantLinks
    .filter((edge) => ids.has(edge.from) && ids.has(edge.to))
    .map((edge) => ({ source: edge.from, target: edge.to, queue: edge.queue }))

  return { nodes: positionedNodes, links }
}

function applyFallbackPositions(nodes: GraphNode[], links: GraphLink[]): GraphNode[] {
  if (nodes.length === 0) {
    return nodes
  }

  const nodeMap = new Map(nodes.map((node) => [node.id, node]))
  const adjacency = new Map<string, string[]>()
  const reverseAdjacency = new Map<string, string[]>()
  const indegree = new Map<string, number>()

  links.forEach((link) => {
    if (!nodeMap.has(link.source) || !nodeMap.has(link.target)) {
      return
    }
    const children = adjacency.get(link.source) ?? []
    children.push(link.target)
    adjacency.set(link.source, children)
    const parents = reverseAdjacency.get(link.target) ?? []
    parents.push(link.source)
    reverseAdjacency.set(link.target, parents)
    indegree.set(link.target, (indegree.get(link.target) ?? 0) + 1)
  })

  nodes.forEach((node) => {
    if (!adjacency.has(node.id)) {
      adjacency.set(node.id, [])
    }
    if (!reverseAdjacency.has(node.id)) {
      reverseAdjacency.set(node.id, [])
    }
  })

  const queue: string[] = []
  const remaining = new Map(indegree)
  const level = new Map<string, number>()

  nodes.forEach((node) => {
    if ((remaining.get(node.id) ?? 0) === 0) {
      queue.push(node.id)
      level.set(node.id, 0)
    }
  })

  if (queue.length === 0) {
    nodes.forEach((node) => {
      queue.push(node.id)
      if (!level.has(node.id)) {
        level.set(node.id, 0)
      }
    })
  }

  while (queue.length) {
    const id = queue.shift()!
    const currentLevel = level.get(id) ?? 0
    const neighbors = adjacency.get(id) ?? []
    neighbors.forEach((next) => {
      const candidate = currentLevel + 1
      const existingLevel = level.get(next)
      if (existingLevel === undefined || candidate > existingLevel) {
        level.set(next, candidate)
      }
      const nextRemaining = (remaining.get(next) ?? 0) - 1
      remaining.set(next, nextRemaining)
      if (nextRemaining === 0) {
        queue.push(next)
      }
    })
  }

  let maxLevel = 0
  level.forEach((value) => {
    if (value > maxLevel) {
      maxLevel = value
    }
  })

  const unassigned = nodes.filter((node) => !level.has(node.id))
  unassigned.forEach((node, index) => {
    const nextLevel = maxLevel + 1 + index
    level.set(node.id, nextLevel)
    maxLevel = nextLevel
  })

  const groups = new Map<number, GraphNode[]>()
  nodes.forEach((node) => {
    const nodeLevel = level.get(node.id) ?? 0
    const list = groups.get(nodeLevel) ?? []
    list.push(node)
    groups.set(nodeLevel, list)
  })

  const orderedLevels = Array.from(groups.keys()).sort((a, b) => a - b)
  const levelIndexMap = new Map<number, number>()
  orderedLevels.forEach((lvl, idx) => levelIndexMap.set(lvl, idx))
  const totalWidth = (orderedLevels.length - 1) * FALLBACK_HORIZONTAL_SPACING
  const offsetX = totalWidth / 2

  const columns = orderedLevels.map((lvl) => {
    const members = groups.get(lvl) ?? []
    return [...members]
  })

  const rowOrder = new Map<string, number>()

  columns.forEach((column, columnIndex) => {
    const baseSorted = [...column].sort(compareNodes)
    if (columnIndex === 0) {
      baseSorted.forEach((node, index) => {
        rowOrder.set(node.id, index)
      })
      columns[columnIndex] = baseSorted
      return
    }
    const sorted = [...column].sort((a, b) => {
      const parentsA = reverseAdjacency.get(a.id) ?? []
      const parentsB = reverseAdjacency.get(b.id) ?? []
      const scoreA = average(
        parentsA
          .map((id) => rowOrder.get(id))
          .filter((value): value is number => value !== undefined),
      )
      const scoreB = average(
        parentsB
          .map((id) => rowOrder.get(id))
          .filter((value): value is number => value !== undefined),
      )
      if (scoreA !== undefined && scoreB !== undefined && scoreA !== scoreB) {
        return scoreA - scoreB
      }
      if (scoreA !== undefined && scoreB === undefined) return -1
      if (scoreA === undefined && scoreB !== undefined) return 1
      return compareNodes(a, b)
    })
    sorted.forEach((node, index) => {
      rowOrder.set(node.id, index)
    })
    columns[columnIndex] = sorted
  })

  for (let columnIndex = columns.length - 2; columnIndex >= 0; columnIndex -= 1) {
    const column = columns[columnIndex]
    const sorted = [...column].sort((a, b) => {
      const childrenA = adjacency.get(a.id) ?? []
      const childrenB = adjacency.get(b.id) ?? []
      const scoreA = average(
        childrenA
          .map((id) => rowOrder.get(id))
          .filter((value): value is number => value !== undefined),
      )
      const scoreB = average(
        childrenB
          .map((id) => rowOrder.get(id))
          .filter((value): value is number => value !== undefined),
      )
      if (scoreA !== undefined && scoreB !== undefined && scoreA !== scoreB) {
        return scoreA - scoreB
      }
      if (scoreA !== undefined && scoreB === undefined) return -1
      if (scoreA === undefined && scoreB !== undefined) return 1
      return compareNodes(a, b)
    })
    sorted.forEach((node, index) => {
      rowOrder.set(node.id, index)
    })
    columns[columnIndex] = sorted
  }

  const fallbackPositions = new Map<string, { x: number; y: number }>()

  orderedLevels.forEach((lvl) => {
    const columnIndex = levelIndexMap.get(lvl) ?? 0
    const columnX = columnIndex * FALLBACK_HORIZONTAL_SPACING - offsetX
    const members = columns[columnIndex]
    if (!members || members.length === 0) return
    const columnHeight = (members.length - 1) * FALLBACK_VERTICAL_SPACING
    const offsetY = columnHeight / 2
    members.forEach((member, rowIndex) => {
      const y = rowIndex * FALLBACK_VERTICAL_SPACING - offsetY
      fallbackPositions.set(member.id, { x: columnX, y })
    })
  })

  return nodes.map((node) => {
    const fallback = fallbackPositions.get(node.id)
    const hasX = typeof node.x === 'number' && Number.isFinite(node.x)
    const hasY = typeof node.y === 'number' && Number.isFinite(node.y)
    if (!fallback) {
      return node
    }
    return {
      ...node,
      x: hasX ? node.x : fallback.x,
      y: hasY ? node.y : fallback.y,
    }
  })
}
