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
    listSwarmsMock.mockResolvedValue([
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
    ])
    setRefreshMock.mockClear()
  })

  it('loads swarm metadata and resolves bee images', async () => {
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
    expect(context!.getControllerImage('sw1')).toBe('ctrl:1')
  })
})
