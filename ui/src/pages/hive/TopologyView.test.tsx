/**
 * @vitest-environment jsdom
 */
import { render, act, within } from '@testing-library/react'
import TopologyView from './TopologyView'
import React, { type ReactNode } from 'react'
import { vi, test, expect, beforeEach } from 'vitest'

interface Node {
  id: string
  type: string
  x?: number
  y?: number
  enabled?: boolean
  swarmId?: string
}

interface RFNode {
  id: string
  type?: string
  position: { x: number; y: number }
  data: Record<string, unknown>
  selected?: boolean
}

interface RFEdge {
  id: string
  source: string
  target: string
  label?: string
  style: { stroke: string; strokeWidth: number }
}

interface RFProps {
  nodes: RFNode[]
  edges: RFEdge[]
  onNodeDragStop: (e: unknown, node: RFNode) => void
  onNodesChange: (changes: { id: string; position: { x: number; y: number } }[]) => void
  children?: ReactNode
  nodeTypes?: Record<string, React.ComponentType<any>>
}

const data = {
  nodes: [
    { id: 'sw1-swarm-controller', type: 'swarm-controller', swarmId: 'sw1' } as Node,
    { id: 'sw1-generator', type: 'generator', swarmId: 'sw1' } as Node,
    { id: 'sw1-processor', type: 'processor', swarmId: 'sw1' } as Node,
    { id: 'c', type: 'generator' } as Node,
    { id: 'hive-orchestrator', type: 'orchestrator', swarmId: 'hive' } as Node,
  ],
  edges: [
    { from: 'sw1-generator', to: 'sw1-processor', queue: 'internal-q' },
    { from: 'sw1-generator', to: 'c', queue: 'external-q' },
  ] as unknown[],
}
let listener: (t: { nodes: Node[]; edges: unknown[] }) => void
const components = [
  {
    id: 'sw1-generator',
    name: 'sw1-generator',
    role: 'generator',
    swarmId: 'sw1',
    queues: [
      { name: 'internal-q', role: 'producer', depth: 0 },
      { name: 'external-q', role: 'producer', depth: 5 },
    ],
  },
  {
    id: 'sw1-processor',
    name: 'sw1-processor',
    role: 'processor',
    swarmId: 'sw1',
    queues: [{ name: 'internal-q', role: 'consumer' }],
  },
  {
    id: 'c',
    name: 'c',
    role: 'generator',
    queues: [],
  },
  {
    id: 'sw1-swarm-controller',
    name: 'sw1-swarm-controller',
    role: 'swarm-controller',
    swarmId: 'sw1',
    queues: [],
  },
  {
    id: 'hive-orchestrator',
    name: 'hive-orchestrator',
    role: 'orchestrator',
    swarmId: 'hive',
    queues: [],
    config: { swarmCount: 4, enabled: true },
    status: 'status-full',
  },
]
const updateNodePosition = vi.fn<(id: string, x: number, y: number) => void>()

beforeEach(() => {
  const orchestrator = components.find((component) => component.id === 'hive-orchestrator')
  if (orchestrator) {
    orchestrator.name = 'hive-orchestrator'
    orchestrator.role = 'orchestrator'
  }
})

vi.mock('@xyflow/react', () => {
  const rf = (props: RFProps) => {
    ;(globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__ = props
    const nodes = props.nodes ?? []
    return (
      <div data-testid="react-flow">
        {nodes.map((node) => {
          const NodeComponent = props.nodeTypes?.[node.type ?? ''] as
            | React.ComponentType<any>
            | undefined
          if (!NodeComponent) {
            return null
          }
          return (
            <div key={node.id} data-node-id={node.id}>
              <NodeComponent
                id={node.id}
                data={node.data}
                selected={Boolean(node.selected)}
                dragging={false}
                isConnectable={false}
                xPos={node.position?.x ?? 0}
                yPos={node.position?.y ?? 0}
              />
            </div>
          )
        })}
        {props.children}
      </div>
    )
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
      changes: { id: string; position: { x: number; y: number } }[],
      nodes: RFNode[],
    ) =>
      nodes.map((n) => {
        const c = changes.find((ch) => ch.id === n.id)
        return c ? { ...n, position: c.position } : n
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

test('grouped swarm node renders and edges aggregate by swarm', () => {
  render(<TopologyView />)
  const props = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  const groupNode = props.nodes.find((n) => n.id === 'sw1-swarm-controller')!
  expect(groupNode.type).toBe('swarmGroup')
  expect(groupNode.position.x).toEqual(expect.any(Number))
  expect(groupNode.position.y).toEqual(expect.any(Number))
  act(() => {
    props.onNodesChange([{ id: 'sw1-swarm-controller', position: { x: 10, y: 20 } }])
  })
  const dragged = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  const movedGroup = dragged.nodes.find((n) => n.id === 'sw1-swarm-controller')!
  expect(movedGroup.position.x).toBe(10)
  expect(movedGroup.position.y).toBe(20)
  act(() => {
    dragged.onNodeDragStop({}, movedGroup)
  })
  expect(updateNodePosition).toHaveBeenCalledWith('sw1-swarm-controller', 10, 20)
  const newProps = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  const updatedGroup = newProps.nodes.find((n) => n.id === 'sw1-swarm-controller')!
  expect(updatedGroup.position.x).toBe(10)
  expect(updatedGroup.position.y).toBe(20)
  const externalEdge = newProps.edges.find((e) => e.id.includes('external-q'))!
  expect(externalEdge.source).toBe('sw1-swarm-controller')
  expect(externalEdge.target).toBe('c')
  expect(externalEdge.style.stroke).toBe('#ff6666')
  expect(externalEdge.style.strokeWidth).toBeGreaterThan(2)
  const groupData = updatedGroup.data as {
    components?: { id: string; queueCount: number }[]
    edges?: { queue: string }[]
  }
  expect(groupData.components).toBeDefined()
  expect(groupData.components).toHaveLength(3)
  const generator = groupData.components?.find((c) => c.id === 'sw1-generator')
  expect(generator?.queueCount).toBe(2)
  expect(groupData.edges?.some((edge) => edge.queue === 'internal-q')).toBe(true)
  const orchestrator = newProps.nodes.find((n) => n.id === 'hive-orchestrator')
  expect(orchestrator?.type).toBe('shape')
  const orchestratorData = orchestrator?.data as
    | {
        label?: string
        componentType?: string
        status?: string
        meta?: { swarmCount?: number }
        role?: string
      }
    | undefined
  expect(orchestratorData?.label).toBe('hive-orchestrator')
  expect(orchestratorData?.role).toBe('orchestrator')
  expect(orchestratorData?.componentType).toBe('orchestrator')
  expect(orchestratorData?.status).toBe('status-full')
  expect(orchestratorData?.meta?.swarmCount).toBe(4)
})

test('orchestrator card renders instance name, role and active swarm count only', () => {
  render(<TopologyView />)
  const card = document.querySelector(
    '[data-node-id="hive-orchestrator"] .shape-node',
  ) as HTMLElement | null
  expect(card).not.toBeNull()
  const scope = within(card as HTMLElement)
  scope.getByText('hive-orchestrator')
  const roleElement = (card as HTMLElement).querySelector('.shape-node__role')
  expect(roleElement).not.toBeNull()
  expect(roleElement?.textContent).toBe('orchestrator')
  scope.getByText('Active swarms')
  scope.getByText('4')
  expect(scope.queryByText(/Status/i)).toBeNull()
  expect(scope.queryByText(/Enabled/i)).toBeNull()
  expect(scope.queryByText(/Role:/i)).toBeNull()
})

test('filters nodes for default swarm', () => {
  render(<TopologyView swarmId="default" />)
  const props = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  expect(props.nodes).toHaveLength(1)
  expect(props.nodes[0].id).toBe('c')
})

test('orchestrator falls back to instance id when name is empty', () => {
  const orchestrator = components.find((component) => component.id === 'hive-orchestrator')!
  orchestrator.name = ''
  render(<TopologyView />)
  const props = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  const orchestratorNode = props.nodes.find((node) => node.id === 'hive-orchestrator')
  expect(orchestratorNode?.data.label).toBe('hive-orchestrator')
})

