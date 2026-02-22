import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useTopologyData } from './useTopologyData'

vi.mock('../../lib/stompClient', () => {
  return {
    requestStatusSnapshots: vi.fn(() => true),
    getNodePosition: vi.fn(() => undefined),
    subscribeTopology: (cb: (topo: any) => void) => {
      ;(subscribeTopology as any).cb = cb
      return () => {}
    },
    subscribeComponents: (cb: (components: any[]) => void) => {
      ;(subscribeComponents as any).cb = cb
      return () => {}
    },
  }
})

const subscribeTopology: { cb?: (topo: any) => void } = {}
const subscribeComponents: { cb?: (comps: any[]) => void } = {}

// Wire our callback holders into the mock implementation
vi.doMock('../../lib/stompClient', () => ({
  requestStatusSnapshots: vi.fn(() => true),
  getNodePosition: vi.fn(() => undefined),
  subscribeTopology: (cb: (topo: any) => void) => {
    subscribeTopology.cb = cb
    return () => {}
  },
  subscribeComponents: (cb: (components: any[]) => void) => {
    subscribeComponents.cb = cb
    return () => {}
  },
}))

describe('useTopologyData', () => {
  beforeEach(() => {
    subscribeTopology.cb = undefined
    subscribeComponents.cb = undefined
  })

  it('aggregates components and queue depths', () => {
    const { result } = renderHook(() => useTopologyData(undefined))

    act(() => {
      subscribeComponents.cb?.([
        {
          id: 'gen-1',
          queues: [{ name: 'q1', depth: 5 }],
        },
        {
          id: 'proc-1',
          queues: [{ name: 'q1', depth: 7 }],
        },
      ])
    })

    expect(result.current.componentsById['gen-1']).toBeTruthy()
    expect(result.current.componentsById['proc-1']).toBeTruthy()
    // q1 depth should be the max across components
    expect(result.current.queueDepths['q1']).toBe(7)
  })

  it('stores graph data from topology updates', () => {
    const { result } = renderHook(() => useTopologyData(undefined))

    act(() => {
      subscribeTopology.cb?.({
        nodes: [
          { id: 'a', type: 'generator', swarmId: 'sw1' },
          { id: 'b', type: 'processor', swarmId: 'sw1' },
        ],
        edges: [{ from: 'a', to: 'b', queue: 'q1' }],
      })
    })

    expect(result.current.data.nodes.length).toBeGreaterThan(0)
    expect(result.current.data.links).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ source: 'a', target: 'b', queue: 'q1' }),
      ]),
    )
  })
})
