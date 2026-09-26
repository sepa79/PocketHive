import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { pendingSwarmLifecycleFeedback } from '../../lib/swarmLifecycleAction'
import { SwarmLifecycleButtons } from './SwarmLifecycleButtons'

const ignore = () => {}

function controls(action: 'start' | 'stop', requestPending = false, canManage = true) {
  const feedback = pendingSwarmLifecycleFeedback('demo', action, {
    correlationId: 'corr', idempotencyKey: 'idem', operationUrl: '/operations/corr',
    outcomeTopic: 'outcome', timeoutMs: 90_000,
  })
  const html = renderToStaticMarkup(<SwarmLifecycleButtons
    requestPending={requestPending} feedback={feedback} canRun canManage={canManage}
    onStart={ignore} onStop={ignore} onRemove={ignore}
  />)
  return [...html.matchAll(/<button\b([^>]*)>(.*?)<\/button>/g)]
    .map((match) => ({ text: match[2].replace(/<[^>]*>/g, ''), disabled: match[1].includes('disabled=""') }))
}

describe('swarm lifecycle controls', () => {
  it('lets an operator stop an accepted START while keeping Start and Remove blocked', () => {
    expect(controls('start')).toEqual([
      { text: 'Start', disabled: true }, { text: 'Stop', disabled: false }, { text: 'Remove', disabled: true },
    ])
  })
  it('blocks repeat actions while STOP is pending', () => {
    expect(controls('stop').every((button) => button.disabled)).toBe(true)
  })
  it('waits for HTTP acceptance before permitting interruption', () => {
    expect(controls('start', true).every((button) => button.disabled)).toBe(true)
  })
  it('does not enable Stop without the backend manage permission', () => {
    expect(controls('start', false, false).find((button) => button.text === 'Stop')?.disabled).toBe(true)
  })
})
