/**
 * @vitest-environment jsdom
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, act } from '@testing-library/react'
import React from 'react'
import { SwarmMetadataProvider, useSwarmMetadata } from './SwarmMetadataContext'
import type { SwarmMetadataContextValue } from './SwarmMetadataContext'

vi.mock('../lib/orchestratorApi', () => ({
  listSwarms: vi.fn(),
}))

vi.mock('../lib/stompClient', () => ({
  setSwarmMetadataRefreshHandler: vi.fn(),
}))

const { listSwarms } = await import('../lib/orchestratorApi')
const { setSwarmMetadataRefreshHandler } = await import('../lib/stompClient')
const listSwarmsMock = vi.mocked(listSwarms)
const setRefreshMock = vi.mocked(setSwarmMetadataRefreshHandler)

function Capture({ onValue }: { onValue: (value: SwarmMetadataContextValue) => void }) {
  const value = useSwarmMetadata()
  React.useEffect(() => {
    onValue(value)
  }, [onValue, value])
  return null
}

describe('SwarmMetadataContext', () => {
beforeEach(() => {
  listSwarmsMock.mockReset()
  setRefreshMock.mockClear()
})

  it('loads swarm metadata, resolves images, and refreshes via lifecycle handler', async () => {
    const initialPayload = [
      {
        id: 'sw1',
        status: 'RUNNING',
        health: 'HEALTHY',
        heartbeat: '2024-01-01T00:00:00Z',
        workEnabled: true,
        controllerEnabled: true,
        templateId: 'tpl',
        controllerImage: 'ctrl:1',
        bees: [
          { role: 'Generator', image: 'gen:1' },
          { role: 'processor', image: null },
        ],
      },
    ]

    const refreshedPayload = [
      ...initialPayload,
      {
        id: 'sw2',
        status: 'RUNNING',
        health: 'HEALTHY',
        heartbeat: '2024-01-01T00:01:00Z',
        workEnabled: true,
        controllerEnabled: true,
        templateId: 'tpl-2',
        controllerImage: 'ctrl:2',
        bees: [{ role: 'moderator', image: 'mod:2' }],
      },
    ]

    listSwarmsMock.mockResolvedValueOnce(initialPayload)
    listSwarmsMock.mockResolvedValueOnce(refreshedPayload)
    listSwarmsMock.mockResolvedValue(refreshedPayload)

    let context: SwarmMetadataContextValue | null = null

    render(
      <SwarmMetadataProvider>
        <Capture onValue={(value) => {
          context = value
        }} />
      </SwarmMetadataProvider>,
    )

    expect(setRefreshMock).toHaveBeenCalledTimes(1)
    expect(context).not.toBeNull()

    await act(async () => {
      await context!.ensureSwarms()
    })

    expect(listSwarmsMock).toHaveBeenCalledTimes(1)
    expect(context!.getBeeImage('sw1', 'generator')).toBe('gen:1')
    expect(context!.getBeeImage('sw1', 'GENERATOR')).toBe('gen:1')
    expect(context!.getBeeImage(null, 'processor')).toBeNull()
    expect(context!.getBeeImage('', 'generator')).toBeNull()
    expect(context!.getControllerImage('sw1')).toBe('ctrl:1')
    expect(context!.findSwarm('')).toBeNull()

    const handler = setRefreshMock.mock.calls.at(-1)?.[0]
    expect(handler).toBeTypeOf('function')

    await act(async () => {
      handler?.(' sw2 ')
      await Promise.resolve()
    })

    expect(listSwarmsMock).toHaveBeenCalledTimes(2)
    expect(context!.swarms).toEqual(refreshedPayload)
    expect(context!.getControllerImage('sw2')).toBe('ctrl:2')
    const ids = context!.swarms.map((swarm) => swarm.id)
    expect(new Set(ids).size).toBe(ids.length)
  })
})
