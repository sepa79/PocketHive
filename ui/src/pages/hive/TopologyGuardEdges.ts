import type { Edge } from '@xyflow/react'
import type { GraphData } from './TopologyBuilder'
import type { Component } from '../../types/hive'
import { normalizeSwarmId } from './TopologyUtils'

export type GuardQueuesConfig = {
  primary?: string
  backpressure?: string
  targetDepth?: number
  minDepth?: number
  maxDepth?: number
  highDepth?: number
  recoveryDepth?: number
  minRate?: number
  maxRate?: number
}

const BASE_LABEL_STYLE = {
  fill: '#fff',
  fontSize: 6,
  whiteSpace: 'normal' as const,
}

const BASE_LABEL_BG_STYLE = { fill: 'rgba(0,0,0,0.6)' }
const GUARD_LABEL_BG_STYLE = { fill: 'rgba(15,23,42,0.9)' }

function normalizeEdgeLabel(label: string): string {
  return label
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .join('\n')
}

function queueMatchesAlias(queueName: string | undefined, alias: string | undefined): boolean {
  if (!queueName || !alias) return false
  if (queueName === alias) return true
  return queueName.endsWith(`.${alias}`)
}

function getNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!trimmed) return undefined
    const parsed = Number(trimmed)
    return Number.isFinite(parsed) ? parsed : undefined
  }
  return undefined
}

function getString(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : undefined
}

export function buildGuardQueuesBySwarm(
  componentsById: Record<string, Component>,
): Map<string, GuardQueuesConfig> {
  const map = new Map<string, GuardQueuesConfig>()
  Object.values(componentsById).forEach((component) => {
    const role = typeof component.role === 'string' ? component.role.trim().toLowerCase() : ''
    if (role !== 'swarm-controller') return
    const swarm = normalizeSwarmId(component.swarmId)
    if (!swarm) return
    const rawConfig =
      component.config && typeof component.config === 'object'
        ? (component.config as Record<string, unknown>)
        : undefined
    const trafficPolicy =
      rawConfig && rawConfig.trafficPolicy && typeof rawConfig.trafficPolicy === 'object'
        ? (rawConfig.trafficPolicy as Record<string, unknown>)
        : undefined
    const bufferGuard =
      trafficPolicy &&
      trafficPolicy.bufferGuard &&
      typeof trafficPolicy.bufferGuard === 'object'
        ? (trafficPolicy.bufferGuard as Record<string, unknown>)
        : undefined
    if (!bufferGuard) return
    const primary = getString(bufferGuard.queueAlias)
    const targetDepth = getNumber(bufferGuard.targetDepth)
    const minDepth = getNumber(bufferGuard.minDepth)
    const maxDepth = getNumber(bufferGuard.maxDepth)
    let minRate: number | undefined
    let maxRate: number | undefined
    const adjust =
      bufferGuard.adjust && typeof bufferGuard.adjust === 'object'
        ? (bufferGuard.adjust as Record<string, unknown>)
        : undefined
    if (adjust) {
      minRate = getNumber(adjust.minRatePerSec)
      maxRate = getNumber(adjust.maxRatePerSec)
    }
    let backpressure: string | undefined
    let highDepth: number | undefined
    let recoveryDepth: number | undefined
    const bp =
      bufferGuard.backpressure && typeof bufferGuard.backpressure === 'object'
        ? (bufferGuard.backpressure as Record<string, unknown>)
        : undefined
    if (bp) {
      backpressure = getString(bp.queueAlias)
      highDepth = getNumber(bp.highDepth)
      recoveryDepth = getNumber(bp.recoveryDepth)
    }
    if (!primary && !backpressure) return
    map.set(swarm, {
      primary,
      backpressure,
      targetDepth,
      minDepth,
      maxDepth,
      highDepth,
      recoveryDepth,
      minRate,
      maxRate,
    })
  })
  return map
}

export function buildGuardedEdgesForSwarm(
  data: GraphData,
  queueDepths: Record<string, number>,
  swarmId: string,
  guardQueues?: GuardQueuesConfig,
): Edge[] {
  const baseEdges: Edge[] = data.links.map((link) => {
    const depth = queueDepths[link.queue] ?? 0
    const color = depth > 0 ? '#ff6666' : '#66aaff'
    const width = 2 + Math.log(depth + 1)

    return {
      id: `${link.source}-${link.target}-${link.queue}`,
      source: link.source,
      target: link.target,
      label: link.queue,
      style: { stroke: color, strokeWidth: width },
      markerEnd: { type: 'arrowclosed', color },
      labelBgPadding: [2, 2],
      labelBgBorderRadius: 2,
      labelStyle: BASE_LABEL_STYLE,
      labelBgStyle: BASE_LABEL_BG_STYLE,
    }
  })

  if (!guardQueues) {
    return baseEdges
  }

  const controllerNode = data.nodes.find(
    (node) =>
      node.type === 'swarm-controller' &&
      (node.swarmId === swarmId || normalizeSwarmId(node.swarmId) === swarmId),
  )

  if (!controllerNode) {
    return baseEdges
  }

  const { primary: primaryAlias, backpressure: backpressureAlias } = guardQueues

  if (primaryAlias) {
    const depthParts: string[] = []
    if (guardQueues.minDepth !== undefined && guardQueues.maxDepth !== undefined) {
      depthParts.push(`depth ${guardQueues.minDepth}..${guardQueues.maxDepth}`)
    }
    if (guardQueues.targetDepth !== undefined) {
      depthParts.push(`target ${guardQueues.targetDepth}`)
    }
    const depthLabel = depthParts.length > 0 ? depthParts.join(' ') : 'guard'
    const normalizedDepthLabel = normalizeEdgeLabel(depthLabel)

    const hasRateRange =
      guardQueues.minRate !== undefined && guardQueues.maxRate !== undefined
    const rateLabel = hasRateRange
      ? `rate ${guardQueues.minRate}..${guardQueues.maxRate}`
      : 'rate'
    const normalizedRateLabel = normalizeEdgeLabel(rateLabel)

    const seenPrimaryRateTargets = new Set<string>()
    const seenPrimaryDepthTargets = new Set<string>()

    data.links
      .filter((link) => queueMatchesAlias(link.queue, primaryAlias))
      .forEach((link) => {
        const producerId = link.source
        if (!seenPrimaryRateTargets.has(producerId)) {
          seenPrimaryRateTargets.add(producerId)
          baseEdges.push({
            id: `guard-rate-${controllerNode.id}-${producerId}`,
            source: controllerNode.id,
            target: producerId,
            label: normalizedRateLabel,
            style: {
              stroke: '#22c55e',
              strokeWidth: 2.5,
              strokeDasharray: '4 2',
            },
            markerEnd: { type: 'arrowclosed', color: '#22c55e' },
            labelBgPadding: [2, 2],
            labelBgBorderRadius: 2,
            labelStyle: BASE_LABEL_STYLE,
            labelBgStyle: GUARD_LABEL_BG_STYLE,
          })
        }

        ;[link.target].forEach((targetId) => {
          const key = `primary-depth|${targetId}`
          if (seenPrimaryDepthTargets.has(key)) return
          seenPrimaryDepthTargets.add(key)
          baseEdges.push({
            id: `guard-depth-${controllerNode.id}-${targetId}`,
            source: controllerNode.id,
            target: targetId,
            label: normalizedDepthLabel,
            style: {
              stroke: '#f97316',
              strokeWidth: 2.5,
              strokeDasharray: '4 2',
            },
            markerEnd: { type: 'arrowclosed', color: '#f97316' },
            labelBgPadding: [2, 2],
            labelBgBorderRadius: 2,
            labelStyle: BASE_LABEL_STYLE,
            labelBgStyle: GUARD_LABEL_BG_STYLE,
          })
        })
      })
  }

  if (backpressureAlias) {
    const bpParts: string[] = ['backpressure']
    if (guardQueues.highDepth !== undefined) {
      bpParts.push(`high ${guardQueues.highDepth}`)
    }
    if (guardQueues.recoveryDepth !== undefined) {
      bpParts.push(`recovery ${guardQueues.recoveryDepth}`)
    }
    const bpLabel = bpParts.join(' ')
    const normalizedBpLabel = normalizeEdgeLabel(bpLabel)
    const seenBpTargets = new Set<string>()

    data.links
      .filter((link) => queueMatchesAlias(link.queue, backpressureAlias))
      .forEach((link) => {
        const targetId = link.target
        const key = `bp|${targetId}`
        if (seenBpTargets.has(key)) return
        seenBpTargets.add(key)
        baseEdges.push({
          id: `guard-bp-${controllerNode.id}-${targetId}`,
          source: controllerNode.id,
          target: targetId,
          label: normalizedBpLabel,
          style: {
            stroke: '#a855f7',
            strokeWidth: 2.5,
            strokeDasharray: '4 2',
          },
          markerEnd: { type: 'arrowclosed', color: '#a855f7' },
          labelBgPadding: [2, 2],
          labelBgBorderRadius: 2,
          labelStyle: BASE_LABEL_STYLE,
          labelBgStyle: GUARD_LABEL_BG_STYLE,
        })
      })
  }

  return baseEdges
}
