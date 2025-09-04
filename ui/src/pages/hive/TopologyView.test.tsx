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
}

interface GraphProps {
  graphData: { nodes: Node[]; links: unknown[] }
  onNodeDragEnd: (n: { id: string; x: number; y: number }) => void
  [key: string]: unknown
}

const data = { nodes: [{ id: 'a', type: 'generator' } as Node], edges: [] as unknown[] }
let listener: (t: { nodes: Node[]; edges: unknown[] }) => void
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
    updateNodePosition: (id: string, x: number, y: number) => {
      updateNodePosition(id, x, y)
      data.nodes[0].x = x
      data.nodes[0].y = y
      listener({ nodes: data.nodes, edges: data.edges })
    },
  }
})

test('node position updates after drag', () => {
  render(<TopologyView />)
  const props = (globalThis as unknown as { __GRAPH_PROPS__: GraphProps }).__GRAPH_PROPS__
  expect(typeof props.width).toBe('number')
  expect(typeof props.height).toBe('number')
  expect(props.graphData.nodes[0].x).toBeUndefined()
  act(() => {
    props.onNodeDragEnd({ id: 'a', x: 10, y: 20 })
  })
  expect(updateNodePosition).toHaveBeenCalledWith('a', 10, 20)
  const newProps = (globalThis as unknown as { __GRAPH_PROPS__: GraphProps }).__GRAPH_PROPS__
  expect(newProps.graphData.nodes[0].x).toBe(10)
  expect(newProps.graphData.nodes[0].y).toBe(20)
})
