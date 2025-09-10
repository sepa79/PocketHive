/**
 * @vitest-environment jsdom
 */
import { render, act } from '@testing-library/react'
import TopologyView from './TopologyView'
import React from 'react'
import { vi, test, expect } from 'vitest'

interface Node {
  id: string
  type: string
  x?: number
  y?: number
  enabled?: boolean
  swarmId?: string
}

interface GraphProps {
  graphData: { nodes: Node[]; links: unknown[] }
  onNodeDragEnd: (n: { id: string; x: number; y: number }) => void
  linkColor?: (l: { queue: string }) => string
  linkWidth?: (l: { queue: string }) => number
  nodeCanvasObject: (
    n: Node,
    ctx: CanvasRenderingContext2D,
    globalScale: number,
  ) => void
  [key: string]: unknown
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

vi.mock('react-force-graph-2d', () => ({
  __esModule: true,
  default: (props: GraphProps) => {
    ;(globalThis as unknown as { __GRAPH_PROPS__: GraphProps }).__GRAPH_PROPS__ = props
    return React.createElement('div')
  },
}))

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
  const props = (globalThis as unknown as { __GRAPH_PROPS__: GraphProps }).__GRAPH_PROPS__
  expect(typeof props.width).toBe('number')
  expect(typeof props.height).toBe('number')
  expect(props.graphData.nodes[0].x).toBe(0)
  expect(props.graphData.nodes[0].y).toBe(0)
  act(() => {
    props.onNodeDragEnd({ id: 'a', x: 10, y: 20 })
  })
  expect(updateNodePosition).toHaveBeenCalledWith('a', 10, 20)
  const newProps = (globalThis as unknown as { __GRAPH_PROPS__: GraphProps }).__GRAPH_PROPS__
  expect(newProps.graphData.nodes[0].x).toBe(10)
  expect(newProps.graphData.nodes[0].y).toBe(20)
  const color = props.linkColor!({ queue: 'q' })
  expect(color).toBe('#ff6666')
  const width = props.linkWidth!({ queue: 'q' })
  expect(width).toBeGreaterThan(2)
  const ctx = {
    beginPath: vi.fn(),
    arc: vi.fn(),
    rect: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
    closePath: vi.fn(),
    fill: vi.fn(),
    stroke: vi.fn(),
    fillText: vi.fn(),
    measureText: () => ({ width: 10 }),
    font: '',
    textAlign: '',
    textBaseline: '',
    fillStyle: '',
    strokeStyle: '',
    lineWidth: 1,
  } as unknown as CanvasRenderingContext2D
  props.nodeCanvasObject(
    { id: 'a', type: 'generator', x: 0, y: 0 } as Node,
    ctx,
    1,
  )
  expect(ctx.fillText).toHaveBeenCalledWith('2', expect.any(Number), expect.any(Number))
})

test('filters nodes for default swarm', () => {
  render(<TopologyView swarmId="default" />)
  const props = (globalThis as unknown as { __GRAPH_PROPS__: GraphProps }).__GRAPH_PROPS__
  expect(props.graphData.nodes).toHaveLength(1)
  expect(props.graphData.nodes[0].id).toBe('c')
})
