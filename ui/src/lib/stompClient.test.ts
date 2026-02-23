import { describe, expect, it, vi } from 'vitest'
import type { Client } from '@stomp/stompjs'
import {
  setClient,
  subscribeComponents,
  subscribeTopology,
  requestStatusSnapshots,
  upsertSyntheticComponent,
  removeSyntheticComponent,
} from './stompClient'
import { subscribeLogs, type LogEntry, resetLogs } from './logs'
import { useUIStore } from '../store'
import type { Component } from '../types/hive'

/**
 * @vitest-environment jsdom
 */

function statusMetricEnvelope(input: {
  swarmId: string
  role: string
  instance: string
  type?: 'status-full' | 'status-delta'
  data?: Record<string, unknown>
}) {
  const now = new Date().toISOString()
  const metricType = input.type ?? 'status-full'
  const baseData: Record<string, unknown> = {
    enabled: true,
    tps: 0,
    context: {},
    ...(metricType === 'status-full' ? { startedAt: now } : {}),
  }
  return {
    timestamp: now,
    version: '1',
    kind: 'metric',
    type: metricType,
    origin: input.instance,
    scope: { swarmId: input.swarmId, role: input.role, instance: input.instance },
    correlationId: null,
    idempotencyKey: null,
    data: { ...baseData, ...(input.data ?? {}) },
  }
}

function outcomeEnvelope(input: {
  swarmId: string
  role: string
  instance: string
  type: string
  correlationId?: string
  data?: Record<string, unknown>
}) {
  const now = new Date().toISOString()
  return {
    timestamp: now,
    version: '1',
    kind: 'outcome',
    type: input.type,
    origin: 'swarm-controller',
    scope: { swarmId: input.swarmId, role: input.role, instance: input.instance },
    correlationId: input.correlationId ?? 'corr-1',
    idempotencyKey: null,
    data: input.data ?? { status: 'success' },
  }
}


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
      body: JSON.stringify({
        timestamp: new Date().toISOString(),
        version: '1',
        kind: 'event',
        type: 'alert',
        origin: 'orchestrator-1',
        scope: { swarmId: 'sw1', role: 'orchestrator', instance: 'orchestrator-1' },
        correlationId: 'e1',
        idempotencyKey: null,
        data: { level: 'error', code: 'boom', message: 'boom' },
      }),
      headers: { destination: '/exchange/ph.control/event.alert.alert.sw1.orchestrator.orchestrator-1', 'x-correlation-id': 'e1' },
    })
    expect(entries[0].destination).toContain('event.alert.alert.sw1')
    expect(entries[0].body).toBe('boom')
    expect(useUIStore.getState().toast).toBe('Error: sw1 boom: boom')
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

    cb({
      headers: { destination: '/exchange/ph.control/event.metric.status-full.sw1.processor.processor-sw1' },
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw1',
          role: 'processor',
          instance: 'processor-sw1',
          data: {
            io: { work: { queues: { out: ['ph.sw1.jobs'] } } },
          },
        }),
      ),
    })

    cb({
      headers: { destination: '/exchange/ph.control/event.metric.status-full.sw1.swarm-controller.controller-sw1' },
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw1',
          role: 'swarm-controller',
          instance: 'controller-sw1',
          data: {
            io: {
              work: {
                queueStats: {
                  'ph.sw1.jobs': {
                    depth: 3,
                    consumers: 2,
                    oldestAgeSec: 12,
                  },
                },
              },
            },
          },
        }),
      ),
    })

    const latest = updates.at(-1)
    expect(latest).toBeTruthy()
    const processor = latest?.find((comp) => comp.id === 'processor-sw1')
    expect(processor).toBeTruthy()
    expect(processor?.swarmId).toBe('sw1')
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

    cb({
      headers: { destination: '/exchange/ph.control/event.metric.status-full.sw1.generator.generator-sw1' },
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw1',
          role: 'generator',
          instance: 'generator-sw1',
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
      ),
    })

    const generator = latest.find((comp) => comp.id === 'generator-sw1')
    expect(generator).toBeTruthy()
    expect(generator?.swarmId).toBe('sw1')
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
      headers: { destination: '/exchange/ph.control/event.metric.status-full.sw1.swarm-controller.swarm-controller-sw1' },
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw1',
          role: 'swarm-controller',
          instance: 'swarm-controller-sw1',
          data: {
            enabled: false,
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
      ),
    })

    const controller = latest.find((comp) => comp.id === 'swarm-controller-sw1')
    expect(controller).toBeTruthy()
    expect(controller?.swarmId).toBe('sw1')
    expect(controller?.config).toMatchObject({
      heartbeatIntervalSec: 15,
      enabled: false,
    })
    expect(controller?.config).not.toHaveProperty('batchSize')

    unsubscribe()
    setClient(null)
  })

  it('preserves nested config values when worker status exposes scalar fields', () => {
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

    cb({
      headers: { destination: '/exchange/ph.control/event.metric.status-full.sw1.moderator.moderator-sw1' },
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw1',
          role: 'moderator',
          instance: 'moderator-sw1',
          data: {
            mode: 'rate-per-sec',
            workers: [
              {
                role: 'moderator',
                enabled: true,
                config: {
                  mode: {
                    type: 'ratePerSec',
                    ratePerSec: 5,
                    sine: { min: 1, max: 10, periodSec: 60 },
                  },
                },
                data: {
                  mode: 'rate-per-sec',
                },
              },
            ],
          },
        }),
      ),
    })

    const moderator = latest.find((comp) => comp.id === 'moderator-sw1')
    expect(moderator?.config?.mode).toEqual({
      type: 'ratePerSec',
      ratePerSec: 5,
      sine: { min: 1, max: 10, periodSec: 60 },
    })

    unsubscribe()
    setClient(null)
  })

  it('updates scalar metadata from subsequent status deltas', () => {
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

    const headers = { destination: '/exchange/ph.control/event.metric.status-delta.hive.orchestrator.orch' }
    cb({
      headers,
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'hive',
          role: 'orchestrator',
          instance: 'orch',
          type: 'status-delta',
          data: { swarmCount: 0 },
        }),
      ),
    })

    cb({
      headers,
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'hive',
          role: 'orchestrator',
          instance: 'orch',
          type: 'status-delta',
          data: { swarmCount: 4 },
        }),
      ),
    })

    const orchestrator = latest.find((comp) => comp.id === 'orch')
    expect(orchestrator?.config?.swarmCount).toBe(4)

    unsubscribe()
    setClient(null)
  })

  it('notifies listeners when synthetic components are upserted', () => {
    const snapshots: Component[][] = []
    const unsubscribe = subscribeComponents((list) => {
      snapshots.push(list.map((component) => ({ ...component })))
    })

    const component: Component = {
      id: 'synthetic',
      name: 'Synthetic',
      role: 'synthetic-role',
      lastHeartbeat: Date.now(),
      queues: [],
      config: { healthStatus: 'UP' },
    }

    upsertSyntheticComponent(component)

    const latest = snapshots.at(-1)
    expect(latest?.some((entry) => entry.id === 'synthetic')).toBe(true)

    removeSyntheticComponent('synthetic')
    unsubscribe()
  })

  it('connects orchestrators to swarm controllers in the topology output', () => {
    const publish = vi.fn()
    let cb: (msg: { body: string; headers: Record<string, string> }) => void = () => {}
    const subscribe = vi
      .fn()
      .mockImplementation((_dest: string, fn: (msg: { body: string; headers: Record<string, string> }) => void) => {
        cb = fn
        return { unsubscribe() {} }
      })
    setClient({ active: true, publish, subscribe } as unknown as Client)

    const topologies: { edges: { from: string; to: string; queue: string }[] }[] = []
    const unsubscribeTopology = subscribeTopology((topology) => {
      topologies.push({ edges: topology.edges.map((edge) => ({ ...edge })) })
    })

    cb({
      headers: { destination: '/exchange/ph.control/event.metric.status-full.hive.orchestrator.hive-orchestrator' },
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'hive',
          role: 'orchestrator',
          instance: 'hive-orchestrator',
          data: {
            io: { control: { queues: { out: ['ph.swarm.configure'] } } },
          },
        }),
      ),
    })

    cb({
      headers: { destination: '/exchange/ph.control/event.metric.status-full.sw1.swarm-controller.sw1-swarm-controller' },
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw1',
          role: 'swarm-controller',
          instance: 'sw1-swarm-controller',
        }),
      ),
    })

    const latest = topologies.at(-1)
    expect(latest?.edges).toContainEqual({
      from: 'hive-orchestrator',
      to: 'sw1-swarm-controller',
      queue: 'swarm-control',
    })

    unsubscribeTopology()
    setClient(null)
  })

  it('builds worker edges from swarm-controller status workers snapshot', () => {
    const publish = vi.fn()
    let cb: (msg: { body: string; headers: Record<string, string> }) => void = () => {}
    const subscribe = vi
      .fn()
      .mockImplementation((_dest: string, fn: (msg: { body: string; headers: Record<string, string> }) => void) => {
        cb = fn
        return { unsubscribe() {} }
      })
    setClient({ active: true, publish, subscribe } as unknown as Client)

    let latest: { edges: { from: string; to: string; queue: string }[] } | null = null
    const unsubscribeTopology = subscribeTopology((topology) => {
      latest = { edges: topology.edges.map((edge) => ({ ...edge })) }
    })

    cb({
      headers: { destination: '/exchange/ph.control/event.metric.status-full.sw1.swarm-controller.sw1-swarm-controller' },
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw1',
          role: 'swarm-controller',
          instance: 'sw1-swarm-controller',
          data: {
            workers: [
              {
                role: 'generator',
                inQueue: 'ph.sw1.gen',
                outQueue: 'ph.sw1.jobs',
                enabled: true,
              },
              {
                role: 'processor',
                inQueue: 'ph.sw1.jobs',
                outQueue: 'ph.sw1.final',
                enabled: true,
              },
            ],
          },
        }),
      ),
    })

    expect(latest).not.toBeNull()
    expect(latest!.edges).toContainEqual({
      from: 'generator-sw1',
      to: 'processor-sw1',
      queue: 'ph.sw1.jobs',
    })

    unsubscribeTopology()
    setClient(null)
  })

  it('retains queues when status-delta omits io payload', () => {
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
      latest = list.map((component) => ({
        ...component,
        queues: component.queues.map((queue) => ({ ...queue })),
      }))
    })

    const headers = {
      destination: '/exchange/ph.control/event.metric.status-full.sw-cache.processor.proc-cache',
    }
    cb({
      headers,
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw-cache',
          role: 'processor',
          instance: 'proc-cache',
          data: {
            io: {
              work: {
                queues: {
                  out: ['ph.sw-cache.work.out'],
                },
              },
            },
          },
        }),
      ),
    })

    cb({
      headers: {
        destination: '/exchange/ph.control/event.metric.status-delta.sw-cache.processor.proc-cache',
      },
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw-cache',
          role: 'processor',
          instance: 'proc-cache',
          type: 'status-delta',
          data: { enabled: true, tps: 5 },
        }),
      ),
    })

    const component = latest.find((entry) => entry.id === 'proc-cache')
    expect(component?.queues).toContainEqual({
      name: 'ph.sw-cache.work.out',
      role: 'producer',
    })

    unsubscribe()
    setClient(null)
  })

  it('does not publish status-request signals over read-only stomp client', () => {
    const publish = vi.fn()
    const subscribe = vi
      .fn()
      .mockImplementation((_dest: string, _fn: (msg: { body: string; headers: Record<string, string> }) => void) => {
        return { unsubscribe() {} }
      })
    setClient({ active: true, publish, subscribe } as unknown as Client)

    const requested = requestStatusSnapshots({ force: true })
    expect(requested).toBe(false)
    expect(publish).not.toHaveBeenCalled()

    setClient(null)
  })

  it('drops swarm components when a swarm-remove outcome arrives', () => {
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
      updates.push(list.map((component) => ({ ...component })))
    })

    const statusHeadersSw1 = { destination: '/exchange/ph.control/event.metric.status-full.sw1.generator.sw1-generator' }
    const statusHeadersSw2 = { destination: '/exchange/ph.control/event.metric.status-full.sw2.processor.sw2-processor' }

    cb({
      headers: statusHeadersSw1,
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw1',
          role: 'generator',
          instance: 'sw1-generator',
        }),
      ),
    })

    cb({
      headers: { destination: '/exchange/ph.control/event.metric.status-full.sw1.processor.sw1-processor' },
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw1',
          role: 'processor',
          instance: 'sw1-processor',
        }),
      ),
    })

    cb({
      headers: statusHeadersSw2,
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw2',
          role: 'processor',
          instance: 'sw2-processor',
        }),
      ),
    })

    const beforeRemoval = updates.at(-1)
    expect(beforeRemoval?.some((component) => component.swarmId === 'sw1')).toBe(true)

    cb({
      headers: {
        destination: '/exchange/ph.control/event.outcome.swarm-remove.sw1.swarm-controller.inst',
      },
      body: JSON.stringify(
        outcomeEnvelope({
          swarmId: 'sw1',
          role: 'swarm-controller',
          instance: 'inst',
          type: 'swarm-remove',
          correlationId: 'e1',
        }),
      ),
    })

    const afterRemoval = updates.at(-1)
    expect(afterRemoval?.some((component) => component.swarmId === 'sw1')).toBe(false)
    expect(afterRemoval?.some((component) => component.swarmId === 'sw2')).toBe(true)

    unsubscribe()

    cb({
      headers: {
        destination: '/exchange/ph.control/event.outcome.swarm-remove.sw2.swarm-controller.inst',
      },
      body: JSON.stringify(
        outcomeEnvelope({
          swarmId: 'sw2',
          role: 'swarm-controller',
          instance: 'inst',
          type: 'swarm-remove',
          correlationId: 'e2',
        }),
      ),
    })

    setClient(null)
  })

  it('drops swarm components when a swarm-controller status reports REMOVED', () => {
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
      updates.push(list.map((component) => ({ ...component })))
    })

    cb({
      headers: { destination: '/exchange/ph.control/event.metric.status-full.sw1.generator.sw1-generator' },
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw1',
          role: 'generator',
          instance: 'sw1-generator',
        }),
      ),
    })

    cb({
      headers: { destination: '/exchange/ph.control/event.metric.status-full.sw1.swarm-controller.sw1-controller' },
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw1',
          role: 'swarm-controller',
          instance: 'sw1-controller',
          data: { swarmStatus: 'RUNNING' },
        }),
      ),
    })

    cb({
      headers: { destination: '/exchange/ph.control/event.metric.status-full.sw2.processor.sw2-processor' },
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw2',
          role: 'processor',
          instance: 'sw2-processor',
        }),
      ),
    })

    const beforeRemoval = updates.at(-1)
    expect(beforeRemoval?.some((component) => component.swarmId === 'sw1')).toBe(true)
    expect(beforeRemoval?.some((component) => component.swarmId === 'sw2')).toBe(true)

    cb({
      headers: { destination: '/exchange/ph.control/event.metric.status-delta.sw1.swarm-controller.sw1-controller' },
      body: JSON.stringify(
        statusMetricEnvelope({
          swarmId: 'sw1',
          role: 'swarm-controller',
          instance: 'sw1-controller',
          type: 'status-delta',
          data: { swarmStatus: 'REMOVED' },
        }),
      ),
    })

    const afterRemoval = updates.at(-1)
    expect(afterRemoval?.some((component) => component.swarmId === 'sw1')).toBe(false)
    expect(afterRemoval?.some((component) => component.swarmId === 'sw2')).toBe(true)

    unsubscribe()
    setClient(null)
  })
})
