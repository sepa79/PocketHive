import { describe, expect, it } from 'vitest'
import { parseNetworkBindings } from './networkBindings'

const binding = {
  swarmId: 'swarm-a', sutId: 'sut-a', networkMode: 'PROXIED',
  networkProfileId: 'latency', effectiveMode: 'DIRECT', requestedBy: 'tester',
  appliedAt: '2026-09-25T10:00:00Z', affectedEndpoints: [],
}

describe('network binding response projection', () => {
  it('preserves both producer modes independently without deriving effective mode', () => {
    expect(parseNetworkBindings([binding])).toEqual([binding])
    const direct = { ...binding, networkMode: 'DIRECT', effectiveMode: 'PROXIED', networkProfileId: null }
    expect(parseNetworkBindings([direct])).toEqual([direct])
  })

  it.each(['networkMode', 'effectiveMode'])('rejects invalid %s instead of supplying DIRECT', field => {
    for (const value of [undefined, null, '', 'UNKNOWN', 'direct', ' PROXIED ', 0, false, {}]) {
      expect(() => parseNetworkBindings([{ ...binding, [field]: value }])).toThrow(field)
    }
  })

  it('accepts an actually empty observation', () => {
    expect(parseNetworkBindings([])).toEqual([])
  })

  it.each([null, undefined, {}, 'unavailable'])('rejects malformed collection %j', value => {
    expect(() => parseNetworkBindings(value)).toThrow('expected an array')
  })

  it.each([null, [], 'invalid', {}, { ...binding, swarmId: '' }, { ...binding, sutId: null }])(
    'rejects a malformed entry instead of silently hiding it: %j', value => {
      expect(() => parseNetworkBindings([binding, value])).toThrow('Invalid network binding')
    },
  )

  it('does not return a partial catalogue when a later mode is invalid', () => {
    expect(() => parseNetworkBindings([binding, { ...binding, effectiveMode: 'OTHER' }])).toThrow('effectiveMode')
  })
})
