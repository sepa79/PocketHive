import '@testing-library/jest-dom/vitest'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import WiremockPanel from './WiremockPanel'
import type { Component } from '../../types/hive'

vi.mock('../../lib/wiremockClient', () => ({
  fetchWiremockComponent: vi.fn(async () => null),
}))

function wiremockComponent(stubCount: number): Component {
  return {
    id: 'wiremock',
    name: 'WireMock',
    role: 'wiremock',
    lastHeartbeat: Date.now(),
    status: 'OK',
    queues: [],
    config: {
      healthStatus: 'OK',
      stubCount,
      recentRequests: [],
      unmatchedRequests: [],
      scenarios: [],
      unmatchedCount: 0,
      adminUrl: 'http://localhost:8080/__admin/',
      lastUpdatedTs: Date.now(),
    },
  }
}

describe('WiremockPanel', () => {
  it('updates rendered config when component prop changes', () => {
    const { rerender } = render(<WiremockPanel component={wiremockComponent(1)} />)
    expect(screen.getByText('Stub count')).toBeInTheDocument()
    expect(screen.getByText('1')).toBeInTheDocument()

    rerender(<WiremockPanel component={wiremockComponent(42)} />)
    expect(screen.getByText('42')).toBeInTheDocument()
  })
})

