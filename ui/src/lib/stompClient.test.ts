import { describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import {
  setClient,
  subscribeComponents,
  subscribeTopology,
  upsertSyntheticComponent,
  removeSyntheticComponent,
} from './stompClient'
import { subscribeLogs, type LogEntry, resetLogs } from './logs'
import { useUIStore } from '../store'
import type { Component } from '../types/hive'

/**
 * @vitest-environment jsdom
 */


describe('swarm lifecycle', () => {
  it('logs error events and sets toast', () => {
    resetLogs()
    useUIStore.setState({ toast: null })
    const publish = vi.fn()
    let cb: (msg: { body: string; headers: Record<string, string> }) => void = () => {}
    const subscribe = vi
      .fn()
      .mockImplementation((_dest: string, fn: (msg: { body: string; headers: Record<string, string> }) => void) => {
        cb = fn
        return { unsubscribe() {} }
      })
    setClient({ active: true, publish, subscribe } as unknown as Client)
    let entries: LogEntry[] = []
    subscribeLogs((l) => {
      entries = l.filter((e) => e.type === 'error')
    })
    cb({
      body: 'boom',
      headers: { destination: '/exchange/ph.control/ev.error.swarm-create.sw1', 'x-correlation-id': 'e1' },
    })
    expect(entries[0].destination).toContain('ev.error.swarm-create.sw1')
    expect(entries[0].body).toBe('boom')
    expect(useUIStore.getState().toast).toBe('Error: error swarm-create sw1: boom')
    setClient(null)
  })

  it('propagates queue stats to all component queues', () => {
    const publish = vi.fn()
    let cb: (msg: { body: string; headers: Record<string, string> }) => void = () => {}
    const subscribe = vi
      .fn()
      .mockImplementation((_dest: string, fn: (msg: { body: string; headers: Record<string, string> }) => void) => {
        cb = fn
        return { unsubscribe() {} }
      })
    setClient({ active: true, publish, subscribe } as unknown as Client)

    const updates: Component[][] = []
    const unsubscribe = subscribeComponents((list) => {
      updates.push(
        list.map((comp) => ({
          ...comp,
          queues: comp.queues.map((q) => ({ ...q })),
        })),
      )
    })

    const baseHeaders = { destination: '/exchange/ph.control/ev.status.swarm-sw1' }
    cb({
      headers: baseHeaders,
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'processor',
        instance: 'processor-sw1',
        messageId: 'm-1',
        timestamp: new Date().toISOString(),
        queues: {
          work: {
            out: ['ph.sw1.jobs'],
          },
        },
      }),
    })

    cb({
      headers: baseHeaders,
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'swarm-controller',
        instance: 'controller-sw1',
        messageId: 'm-2',
        timestamp: new Date().toISOString(),
        queueStats: {
          'ph.sw1.jobs': {
            depth: 3,
            consumers: 2,
            oldestAgeSec: 12,
          },
        },
      }),
    })

    const latest = updates.at(-1)
    expect(latest).toBeTruthy()
    const processor = latest?.find((comp) => comp.id === 'processor-sw1')
    expect(processor).toBeTruthy()
    expect(processor?.queues[0]).toMatchObject({
      name: 'ph.sw1.jobs',
      role: 'producer',
      depth: 3,
      consumers: 2,
      oldestAgeSec: 12,
    })

    unsubscribe()
    setClient(null)
  })

  it('merges worker-specific config and data from status snapshots', () => {
    const publish = vi.fn()
    let cb: (msg: { body: string; headers: Record<string, string> }) => void = () => {}
    const subscribe = vi
      .fn()
      .mockImplementation((_dest: string, fn: (msg: { body: string; headers: Record<string, string> }) => void) => {
        cb = fn
        return { unsubscribe() {} }
      })
    setClient({ active: true, publish, subscribe } as unknown as Client)

    let latest: Component[] = []
    const unsubscribe = subscribeComponents((list) => {
      latest = list.map((comp) => ({
        ...comp,
        config: comp.config ? { ...comp.config } : undefined,
      }))
    })

    const baseHeaders = { destination: '/exchange/ph.control/ev.status.swarm-sw1' }
    cb({
      headers: baseHeaders,
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'generator',
        instance: 'generator-sw1',
        messageId: 'm-1',
        timestamp: new Date().toISOString(),
        enabled: true,
        data: {
          processedTotal: 321,
          workers: [
            {
              role: 'moderator',
              enabled: true,
              config: { ratePerSec: 2 },
              data: { processedDelta: 7 },
            },
            {
              role: 'generator',
              enabled: false,
              config: { ratePerSec: 5, path: '/demo' },
              data: { processedDelta: 42 },
            },
          ],
        },
      }),
    })

    const generator = latest.find((comp) => comp.id === 'generator-sw1')
    expect(generator).toBeTruthy()
    expect(generator?.config).toBeTruthy()
    expect(generator?.config).not.toHaveProperty('workers')
    expect(generator?.config).toMatchObject({
      ratePerSec: 5,
      path: '/demo',
      processedDelta: 42,
      processedTotal: 321,
      enabled: false,
    })

    cb({
      headers: baseHeaders,
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'swarm-controller',
        instance: 'swarm-controller-sw1',
        messageId: 'm-2',
        timestamp: new Date().toISOString(),
        enabled: false,
        data: {
          heartbeatIntervalSec: 15,
          workers: [
            {
              role: 'processor',
              enabled: true,
              config: { batchSize: 10 },
            },
          ],
        },
      }),
    })

    const controller = latest.find((comp) => comp.id === 'swarm-controller-sw1')
    expect(controller).toBeTruthy()
    expect(controller?.config).toMatchObject({
      heartbeatIntervalSec: 15,
      enabled: false,
    })
    expect(controller?.config).not.toHaveProperty('batchSize')

    unsubscribe()
    setClient(null)
  })

  it('notifies listeners when synthetic components are upserted', () => {
    const snapshots: Component[][] = []
    const unsubscribe = subscribeComponents((list) => {
      snapshots.push(list.map((component) => ({ ...component })))
    })

    const component: Component = {
      id: 'wiremock',
      name: 'WireMock',
      role: 'wiremock',
      lastHeartbeat: Date.now(),
      queues: [],
      config: { healthStatus: 'UP', requestCount: 5 },
    }

    upsertSyntheticComponent(component)

    const latest = snapshots.at(-1)
    expect(latest?.some((entry) => entry.id === 'wiremock')).toBe(true)

    removeSyntheticComponent('wiremock')
    unsubscribe()
  })

  it('emits topology edges targeting wiremock when the synthetic component is present', () => {
    const publish = vi.fn()
    let cb: (msg: { body: string; headers: Record<string, string> }) => void = () => {}
    const subscribe = vi
      .fn()
      .mockImplementation((_dest: string, fn: (msg: { body: string; headers: Record<string, string> }) => void) => {
        cb = fn
        return { unsubscribe() {} }
      })
    setClient({ active: true, publish, subscribe } as unknown as Client)

    const topologies: { nodes: unknown[]; edges: { from: string; to: string; queue: string }[] }[] = []
    const unsubscribeTopo = subscribeTopology((topology) => {
      topologies.push({
        nodes: topology.nodes.map((n) => ({ ...n })),
        edges: topology.edges.map((e) => ({ ...e })),
      })
    })

    const baseHeaders = { destination: '/exchange/ph.control/ev.status.swarm-default' }
    cb({
      headers: baseHeaders,
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'processor',
        instance: 'processor',
        messageId: 'm-processor',
        timestamp: new Date().toISOString(),
      }),
    })

    let latest = topologies.at(-1)
    expect(latest?.edges).toContainEqual({ from: 'processor', to: 'sut', queue: 'sut' })
    expect(latest?.edges?.some((edge) => edge.to === 'wiremock')).toBe(false)

    const wiremock: Component = {
      id: 'wiremock',
      name: 'WireMock',
      role: 'wiremock',
      lastHeartbeat: Date.now(),
      queues: [],
      config: { healthStatus: 'UP' },
    }
    upsertSyntheticComponent(wiremock)

    latest = topologies.at(-1)
    expect(latest?.edges).toContainEqual({ from: 'processor', to: 'wiremock', queue: 'sut' })
    expect(latest?.edges).toContainEqual({ from: 'processor', to: 'sut', queue: 'sut' })
    expect(latest?.nodes.some((node) => (node as { id: string }).id === 'wiremock')).toBe(true)

    unsubscribeTopo()
    removeSyntheticComponent('wiremock')
    setClient(null)
  })
})
