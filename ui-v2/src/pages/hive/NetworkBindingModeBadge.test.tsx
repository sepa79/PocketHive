import { renderToStaticMarkup } from 'react-dom/server'
import { expect, it } from 'vitest'
import NetworkBindingModeBadge from './NetworkBindingModeBadge'
import type { NetworkBinding } from '../../lib/NetworkBinding'

const binding: NetworkBinding = {
  swarmId: 'swarm-a', sutId: 'sut-a', networkMode: 'PROXIED',
  networkProfileId: 'latency', effectiveMode: 'DIRECT', requestedBy: 'tester',
  appliedAt: '2026-09-25T10:00:00Z', affectedEndpoints: [],
}

it('shows observed effective mode even when desired mode differs', () => {
  const html = renderToStaticMarkup(<NetworkBindingModeBadge binding={binding} loading={false} error={null} />)
  expect(html).toContain('DIRECT')
  expect(html).not.toContain('PROXIED')
})

it.each([
  { loading: true, error: null, expected: 'Loading…' },
  { loading: false, error: 'HTTP 503', expected: 'Unavailable' },
])('does not present a previous observation as current during $expected', ({ loading, error, expected }) => {
  const html = renderToStaticMarkup(<NetworkBindingModeBadge binding={binding} loading={loading} error={error} />)
  expect(html).toContain(expected)
  expect(html).not.toContain('DIRECT')
})

it('shows missing binding explicitly instead of DIRECT', () => {
  const html = renderToStaticMarkup(<NetworkBindingModeBadge binding={null} loading={false} error={null} />)
  expect(html).toContain('No binding')
  expect(html).not.toContain('DIRECT')
})
