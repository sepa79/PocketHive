/**
 * @vitest-environment jsdom
 */
import { render, act } from '@testing-library/react'
import TopologyView from './TopologyView'
import React, { type ReactNode } from 'react'
import { vi, test, expect } from 'vitest'

interface Node {
  id: string
  type: string
  x?: number
  y?: number
  enabled?: boolean
  swarmId?: string
}

interface RFShapeData {
  queueCount: number
  beeName?: string
  instanceId?: string
  role?: string
  swarmId?: string
}

interface RFSwarmData {
  swarmId: string
  label: string
  beeCount: number
  controllerName?: string
  controllerId?: string
}

interface RFNode {
  id: string
  type: string
  position: { x: number; y: number }
  positionAbsolute?: { x: number; y: number }
  parentNode?: string
  extent?: string
  data: RFShapeData | RFSwarmData
  style?: { width?: number; height?: number }
}

interface RFEdge {
  style: { stroke: string; strokeWidth: number }
}

interface RFProps {
  nodes: RFNode[]
  edges: RFEdge[]
  onNodeDragStop: (e: unknown, node: RFNode) => void
  onNodesChange: (
    changes: {
      id: string
      position: { x: number; y: number }
      positionAbsolute?: { x: number; y: number }
    }[],
  ) => void
  onNodeClick?: (e: unknown, node: RFNode) => void
  children?: ReactNode
}

const data = {
  nodes: [
    { id: 'queen', type: 'swarm-controller', swarmId: 'sw1' } as Node,
    { id: 'a', type: 'generator', swarmId: 'sw1' } as Node,
    { id: 'b', type: 'processor', swarmId: 'sw1' } as Node,
    { id: 'orc', type: 'orchestrator', swarmId: 'sw1' } as Node,
    { id: 'c', type: 'generator', swarmId: 'hive' } as Node,
  ],
  edges: [
    { from: 'queen', to: 'a', queue: 'q-ctrl' },
    { from: 'a', to: 'b', queue: 'q' },
  ] as unknown[],
}
let listener: (t: { nodes: Node[]; edges: unknown[] }) => void
const components = [
  {
    id: 'queen',
    name: 'swarm-controller',
    swarmId: 'sw1',
    queues: [{ name: 'q-ctrl', role: 'producer' }],
  },
  {
    id: 'a',
    name: 'generator',
    swarmId: 'sw1',
    queues: [
      { name: 'q', role: 'producer', depth: 5 },
      { name: 'q2', role: 'producer' },
    ],
  },
  {
    id: 'b',
    name: 'processor',
    swarmId: 'sw1',
    queues: [{ name: 'q', role: 'consumer' }],
  },
  {
    id: 'orc',
    name: 'orchestrator',
    swarmId: 'sw1',
    queues: [],
  },
  {
    id: 'c',
    name: 'generator',
    swarmId: 'hive',
    queues: [],
  },
]
const updateNodePosition = vi.fn<(id: string, x: number, y: number) => void>()

vi.mock('@xyflow/react', () => {
  const rf = (props: RFProps) => {
    ;(globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__ = props
    return React.createElement('div', null, props.children)
  }
  return {
    __esModule: true,
    ReactFlow: rf,
    default: rf,
    MarkerType: { ArrowClosed: 'arrow' },
    Background: () => React.createElement('div'),
    Handle: () => React.createElement('div'),
    Position: { Left: 'left', Right: 'right' },
    applyNodeChanges: (
      changes: {
        id: string
        position: { x: number; y: number }
        positionAbsolute?: { x: number; y: number }
      }[],
      nodes: RFNode[],
    ) =>
      nodes.map((n) => {
        const c = changes.find((ch) => ch.id === n.id)
        if (!c) return n
        const next = { ...n, position: c.position }
        if (n.type !== 'swarmCard') {
          next.positionAbsolute = c.positionAbsolute ?? n.positionAbsolute
        }
        return next
      }),
  }
})

vi.mock('../../lib/stompClient', () => {
  return {
    subscribeTopology: (cb: (t: { nodes: Node[]; edges: unknown[] }) => void) => {
      listener = cb
      cb(data)
      return () => {}
    },
    subscribeComponents: (cb: (c: unknown) => void) => {
      cb(components)
      return () => {}
    },
    updateNodePosition: (id: string, x: number, y: number) => {
      updateNodePosition(id, x, y)
      const target = data.nodes.find((node) => node.id === id)
      if (target) {
        target.x = x
        target.y = y
      }
      listener({ nodes: data.nodes, edges: data.edges })
    },
  }
})

test('node position updates after drag and edge depth styles', () => {
  render(<TopologyView />)
  const props = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  const swarmCard = props.nodes.find((n) => n.type === 'swarmCard')
  expect(swarmCard?.id).toBe('swarm:sw1')
  expect(swarmCard?.style?.width).toBeGreaterThan(0)
  const controllerNode = props.nodes.find((n) => n.id === 'queen') as RFNode
  expect(controllerNode.type).toBe('beeIcon')
  expect(controllerNode.parentNode).toBe('swarm:sw1')
  const nodeA = props.nodes.find((n) => n.id === 'a') as RFNode
  expect(nodeA.type).toBe('beeIcon')
  expect(nodeA.parentNode).toBe('swarm:sw1')
  const nodeAData = nodeA.data as RFShapeData
  expect(nodeAData.beeName).toBe('a')
  expect(nodeAData.instanceId).toBe('a')
  expect(nodeAData.role).toBe('generator')
  expect(nodeAData.swarmId).toBe('sw1')
  expect(nodeAData.queueCount).toBe(2)
  expect(nodeA.positionAbsolute).toEqual({ x: 0, y: 0 })
  expect(nodeA.position.x).toBe(nodeA.positionAbsolute!.x - (swarmCard?.position.x ?? 0))
  expect(nodeA.position.y).toBe(nodeA.positionAbsolute!.y - (swarmCard?.position.y ?? 0))
  const orchestratorNode = props.nodes.find((n) => n.id === 'orc') as RFNode
  expect(orchestratorNode.type).toBe('shape')
  expect(orchestratorNode.parentNode).toBeUndefined()
  act(() => {
    props.onNodesChange([{ id: 'a', position: { x: 10, y: 20 } }])
  })
  const dragged = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  const draggedNode = dragged.nodes.find((n) => n.id === 'a') as RFNode
  expect(draggedNode.position.x).toBe(10)
  expect(draggedNode.position.y).toBe(20)
  act(() => {
    dragged.onNodeDragStop({}, {
      ...draggedNode,
      positionAbsolute: { x: 42, y: 64 },
    })
  })
  expect(updateNodePosition).toHaveBeenCalledWith('a', 42, 64)
  const newProps = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  const updatedNode = newProps.nodes.find((n) => n.id === 'a') as RFNode
  const updatedSwarm = newProps.nodes.find((n) => n.id === 'swarm:sw1') as RFNode
  expect(updatedNode.positionAbsolute).toEqual({ x: 42, y: 64 })
  expect(updatedNode.position.x).toBeCloseTo(
    updatedNode.positionAbsolute!.x - updatedSwarm.position.x,
  )
  expect(updatedNode.position.y).toBeCloseTo(
    updatedNode.positionAbsolute!.y - updatedSwarm.position.y,
  )
  const deepEdge = newProps.edges.find(
    (edge) => (edge as unknown as { label?: string }).label === 'q',
  )
  expect(deepEdge).toBeTruthy()
  const styledEdge = deepEdge as RFEdge
  expect(styledEdge.style.stroke).toBe('#ff6666')
  expect(styledEdge.style.strokeWidth).toBeGreaterThan(2)
})

test('filters nodes for default swarm', () => {
  render(<TopologyView swarmId="default" />)
  const props = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  const visibleNodes = props.nodes.filter((n) => n.type !== 'swarmCard')
  expect(visibleNodes.map((n) => n.id).sort()).toEqual(['c', 'orc'])
  visibleNodes.forEach((n) => expect(n.type).toBe('shape'))
})

test('swarm detail view renders full cards for members only', () => {
  render(<TopologyView swarmId="sw1" />)
  const props = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  const nodes = props.nodes.filter((n) => n.type !== 'swarmCard')
  expect(nodes.map((n) => n.id).sort()).toEqual(['a', 'b', 'queen'])
  nodes.forEach((n) => expect(n.type).toBe('shape'))
  expect(props.nodes.find((n) => n.id === 'orc')).toBeUndefined()
})

test('clicking hive-scoped node selects default swarm bucket', () => {
  const onSwarmSelect = vi.fn<(id: string) => void>()
  render(<TopologyView onSwarmSelect={onSwarmSelect} />)
  const props = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  const hiveNode = props.nodes.find((n) => n.id === 'c')
  expect(hiveNode).toBeTruthy()
  props.onNodeClick?.({}, hiveNode as RFNode)
  expect(onSwarmSelect).toHaveBeenCalledWith('default')
})

test('clicking swarm card selects the swarm', () => {
  const onSwarmSelect = vi.fn<(id: string) => void>()
  render(<TopologyView onSwarmSelect={onSwarmSelect} />)
  const props = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  const swarmCard = props.nodes.find((n) => n.type === 'swarmCard')
  expect(swarmCard).toBeTruthy()
  props.onNodeClick?.({}, swarmCard as RFNode)
  expect(onSwarmSelect).toHaveBeenCalledWith('sw1')
})

