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
        swarmId: 'sw1',
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
        swarmId: 'sw1',
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

    const baseHeaders = { destination: '/exchange/ph.control/ev.status.swarm-sw1' }
    cb({
      headers: baseHeaders,
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'generator',
        instance: 'generator-sw1',
        swarmId: 'sw1',
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
      headers: baseHeaders,
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'swarm-controller',
        instance: 'swarm-controller-sw1',
        swarmId: 'sw1',
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
      headers: { destination: '/exchange/ph.control/ev.status.swarm-sw1' },
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'moderator',
        instance: 'moderator-sw1',
        swarmId: 'sw1',
        messageId: 'm-1',
        timestamp: new Date().toISOString(),
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

    const headers = { destination: '/exchange/ph.control/ev.status.orchestrator' }
    cb({
      headers,
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'orchestrator',
        instance: 'orch',
        swarmId: 'hive',
        messageId: 'm0',
        timestamp: new Date().toISOString(),
        data: { swarmCount: 0 },
      }),
    })

    cb({
      headers,
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'orchestrator',
        instance: 'orch',
        swarmId: 'hive',
        messageId: 'm1',
        timestamp: new Date().toISOString(),
        data: { swarmCount: 4 },
      }),
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
      headers: { destination: '/exchange/ph.control/ev.status.swarm-hive' },
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'orchestrator',
        instance: 'hive-orchestrator',
        swarmId: 'hive',
        messageId: 'orch-1',
        timestamp: new Date().toISOString(),
        queues: {
          control: {
            out: ['ph.swarm.configure'],
          },
        },
      }),
    })

    cb({
      headers: { destination: '/exchange/ph.control/ev.status.swarm-sw1' },
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'swarm-controller',
        instance: 'sw1-swarm-controller',
        swarmId: 'sw1',
        messageId: 'sw1-1',
        timestamp: new Date().toISOString(),
      }),
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

  it('drops swarm components when a swarm-remove ready confirmation arrives', () => {
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

    const statusHeadersSw1 = { destination: '/exchange/ph.control/ev.status.swarm-sw1' }
    const statusHeadersSw2 = { destination: '/exchange/ph.control/ev.status.swarm-sw2' }
    const now = new Date().toISOString()

    cb({
      headers: statusHeadersSw1,
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'generator',
        instance: 'sw1-generator',
        swarmId: 'sw1',
        messageId: 'm-1',
        timestamp: now,
      }),
    })

    cb({
      headers: statusHeadersSw1,
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'processor',
        instance: 'sw1-processor',
        swarmId: 'sw1',
        messageId: 'm-2',
        timestamp: now,
      }),
    })

    cb({
      headers: statusHeadersSw2,
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'processor',
        instance: 'sw2-processor',
        swarmId: 'sw2',
        messageId: 'm-3',
        timestamp: now,
      }),
    })

    const beforeRemoval = updates.at(-1)
    expect(beforeRemoval?.some((component) => component.swarmId === 'sw1')).toBe(true)

    cb({
      headers: {
        destination: '/exchange/ph.control/ev.ready.swarm-remove.sw1.swarm-controller.inst',
      },
      body: JSON.stringify({
        result: 'success',
        signal: 'swarm-remove',
        scope: { swarmId: 'sw1', role: 'swarm-controller', instance: 'inst' },
      }),
    })

    const afterRemoval = updates.at(-1)
    expect(afterRemoval?.some((component) => component.swarmId === 'sw1')).toBe(false)
    expect(afterRemoval?.some((component) => component.swarmId === 'sw2')).toBe(true)

    unsubscribe()

    cb({
      headers: {
        destination: '/exchange/ph.control/ev.ready.swarm-remove.sw2.swarm-controller.inst',
      },
      body: JSON.stringify({ signal: 'swarm-remove', scope: { swarmId: 'sw2' } }),
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

    const now = new Date().toISOString()

    cb({
      headers: { destination: '/exchange/ph.control/ev.status.swarm-sw1' },
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'generator',
        instance: 'sw1-generator',
        swarmId: 'sw1',
        messageId: 'm-gen',
        timestamp: now,
      }),
    })

    cb({
      headers: { destination: '/exchange/ph.control/ev.status.swarm-sw1' },
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'swarm-controller',
        instance: 'sw1-controller',
        swarmId: 'sw1',
        messageId: 'm-ctrl',
        timestamp: now,
        data: { swarmStatus: 'RUNNING' },
      }),
    })

    cb({
      headers: { destination: '/exchange/ph.control/ev.status.swarm-sw2' },
      body: JSON.stringify({
        event: 'status',
        kind: 'status',
        version: '1',
        role: 'processor',
        instance: 'sw2-processor',
        swarmId: 'sw2',
        messageId: 'm-proc',
        timestamp: now,
      }),
    })

    const beforeRemoval = updates.at(-1)
    expect(beforeRemoval?.some((component) => component.swarmId === 'sw1')).toBe(true)
    expect(beforeRemoval?.some((component) => component.swarmId === 'sw2')).toBe(true)

    cb({
      headers: { destination: '/exchange/ph.control/ev.status.swarm-sw1' },
      body: JSON.stringify({
        event: 'status',
        kind: 'status-delta',
        version: '1',
        role: 'swarm-controller',
        instance: 'sw1-controller',
        swarmId: 'sw1',
        messageId: 'm-ctrl-removed',
        timestamp: now,
        data: { swarmStatus: 'REMOVED' },
      }),
    })

    const afterRemoval = updates.at(-1)
    expect(afterRemoval?.some((component) => component.swarmId === 'sw1')).toBe(false)
    expect(afterRemoval?.some((component) => component.swarmId === 'sw2')).toBe(true)

    unsubscribe()
    setClient(null)
  })
})
