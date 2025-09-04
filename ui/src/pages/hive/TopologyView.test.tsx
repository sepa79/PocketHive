import { render, act } from '@testing-library/react'
import TopologyView from './TopologyView'
import React from 'react'
import { vi, test, expect } from 'vitest'

interface Node {
  id: string
  x?: number
  y?: number
  role?: string
  name?: string
  status?: string
  progress?: number
}

interface GraphProps {
  graphData: { nodes: Node[]; links: unknown[] }
  onNodeDragEnd: (n: { id: string; x: number; y: number }) => void
}

const data = { nodes: [{ id: 'a' } as Node], edges: [] as unknown[] }
let topoListener: (t: { nodes: Node[]; edges: unknown[] }) => void
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
      topoListener = cb
      cb(data)
      return () => {}
    },
    subscribeComponents: (cb: (c: { id: string; name: string; status: string; queues: unknown[] }[]) => void) => {
      cb([{ id: 'a', name: 'Worker', status: 'ok', queues: [] }])
      return () => {}
    },
    updateNodePosition: (id: string, x: number, y: number) => {
      updateNodePosition(id, x, y)
      data.nodes[0].x = x
      data.nodes[0].y = y
      topoListener({ nodes: data.nodes, edges: data.edges })
    },
  }
})

test('node position updates after drag', () => {
  render(<TopologyView />)
  const props = (globalThis as unknown as { __GRAPH_PROPS__: GraphProps }).__GRAPH_PROPS__
  expect(props.graphData.nodes[0].x).toBe(0)
  act(() => {
    props.onNodeDragEnd({ id: 'a', x: 10, y: 20 })
  })
  expect(updateNodePosition).toHaveBeenCalledWith('a', 10, 20)
  const newProps = (globalThis as unknown as { __GRAPH_PROPS__: GraphProps }).__GRAPH_PROPS__
  expect(newProps.graphData.nodes[0].x).toBe(10)
  expect(newProps.graphData.nodes[0].y).toBe(20)
})
