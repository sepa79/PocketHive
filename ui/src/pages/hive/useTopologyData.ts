import { useEffect, useState } from 'react'
import {
  subscribeTopology,
  subscribeComponents,
  type Topology,
} from '../../lib/stompClient'
import type { Component } from '../../types/hive'
import { buildGraph, type GraphData } from './TopologyBuilder'

export type TopologyData = {
  data: GraphData
  queueDepths: Record<string, number>
  componentsById: Record<string, Component>
}

export function useTopologyData(swarmId?: string): TopologyData {
  const [data, setData] = useState<GraphData>({ nodes: [], links: [] })
  const [queueDepths, setQueueDepths] = useState<Record<string, number>>({})
  const [componentsById, setComponentsById] = useState<Record<string, Component>>({})

  useEffect(() => {
    const unsub = subscribeComponents((comps: Component[]) => {
      const depths: Record<string, number> = {}
      const map: Record<string, Component> = {}
      comps.forEach((c) => {
        map[c.id] = c
        c.queues.forEach((q) => {
          if (typeof q.depth === 'number') {
            const d = depths[q.name]
            depths[q.name] = d === undefined ? q.depth : Math.max(d, q.depth)
          }
        })
      })
      setQueueDepths(depths)
      setComponentsById(map)
    })
    return () => unsub()
  }, [])

  useEffect(() => {
    const unsub = subscribeTopology((topo: Topology) => {
      const graph = buildGraph(topo, swarmId)
      setData(graph)
    })
    return () => unsub()
  }, [swarmId])

  return { data, queueDepths, componentsById }
}

