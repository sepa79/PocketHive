import { useEffect, useMemo, useState } from 'react'
import {
  subscribeTopology,
  subscribeComponents,
  type Topology,
} from '../../lib/stompClient'
import type { Component } from '../../types/hive'
import { buildGraph, type GraphData } from './TopologyBuilder'
import { useSwarmMetadata } from '../../contexts/SwarmMetadataContext'
import type { SwarmSummary } from '../../types/orchestrator'

export type TopologyData = {
  data: GraphData
  queueDepths: Record<string, number>
  componentsById: Record<string, Component>
}

export function useTopologyData(swarmId?: string): TopologyData {
  const [rawGraph, setRawGraph] = useState<GraphData>({ nodes: [], links: [] })
  const [queueDepths, setQueueDepths] = useState<Record<string, number>>({})
  const [componentsById, setComponentsById] = useState<Record<string, Component>>({})
  const { swarms } = useSwarmMetadata()

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
      setRawGraph(graph)
    })
    return () => unsub()
  }, [swarmId])

  const data = useMemo(
    () => augmentGraphWithSuts(rawGraph, swarms, swarmId),
    [rawGraph, swarms, swarmId],
  )

  return { data, queueDepths, componentsById }
}

function augmentGraphWithSuts(
  graph: GraphData,
  swarms: SwarmSummary[],
  swarmId?: string,
): GraphData {
  if (!graph.nodes.length || !swarms.length) {
    return graph
  }

  const nodes = [...graph.nodes]
  const links = [...graph.links]
  const existingIds = new Set(nodes.map((n) => n.id))

  const relevantSwarms = swarmId
    ? swarms.filter((swarm) => normalizeId(swarm.id) === normalizeId(swarmId))
    : swarms

  for (const swarm of relevantSwarms) {
    const sutId = swarm.sutId
    const swarmKey = normalizeId(swarm.id)
    if (!sutId || !swarmKey) continue
    const nodeId = `sut:${swarmKey}`
    if (existingIds.has(nodeId)) continue
    nodes.push({
      id: nodeId,
      type: 'sut',
      enabled: true,
      swarmId: swarmKey,
    })
    existingIds.add(nodeId)
  }

  return { nodes, links }
}

function normalizeId(value: string | null | undefined): string | null {
  if (value == null) return null
  const trimmed = value.trim()
  return trimmed || null
}
