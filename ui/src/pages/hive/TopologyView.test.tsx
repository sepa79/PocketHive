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

interface RFNode {
  id: string
  position: { x: number; y: number }
  data: { queueCount: number }
}

interface RFEdge {
  style: { stroke: string; strokeWidth: number }
}

interface RFProps {
  nodes: RFNode[]
  edges: RFEdge[]
  onNodeDragStop: (e: unknown, node: RFNode) => void
  children?: ReactNode
}

const data = {
  nodes: [
    { id: 'a', type: 'generator', swarmId: 'sw1' } as Node,
    { id: 'b', type: 'processor', swarmId: 'sw1' } as Node,
    { id: 'c', type: 'generator' } as Node,
  ],
  edges: [{ from: 'a', to: 'b', queue: 'q' }] as unknown[],
}
let listener: (t: { nodes: Node[]; edges: unknown[] }) => void
const components = [
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
    id: 'c',
    name: 'generator',
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
      data.nodes[0].x = x
      data.nodes[0].y = y
      listener({ nodes: data.nodes, edges: data.edges })
    },
  }
})

test('node position updates after drag and edge depth styles', () => {
  render(<TopologyView />)
  const props = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  expect(props.nodes[0].position.x).toBe(0)
  expect(props.nodes[0].position.y).toBe(0)
  act(() => {
    props.onNodeDragStop({}, {
      id: 'a',
      position: { x: 10, y: 20 },
      data: props.nodes[0].data,
    })
  })
  expect(updateNodePosition).toHaveBeenCalledWith('a', 10, 20)
  const newProps = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  expect(newProps.nodes[0].position.x).toBe(10)
  expect(newProps.nodes[0].position.y).toBe(20)
  expect(newProps.edges[0].style.stroke).toBe('#ff6666')
  expect(newProps.edges[0].style.strokeWidth).toBeGreaterThan(2)
  expect(newProps.nodes[0].data.queueCount).toBe(2)
})

test('filters nodes for default swarm', () => {
  render(<TopologyView swarmId="default" />)
  const props = (globalThis as unknown as { __RF_PROPS__: RFProps }).__RF_PROPS__
  expect(props.nodes).toHaveLength(1)
  expect(props.nodes[0].id).toBe('c')
})

