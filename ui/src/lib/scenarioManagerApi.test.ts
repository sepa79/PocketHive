import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { getScenario } from './scenarioManagerApi'

vi.mock('./api', () => ({
  apiFetch: vi.fn(),
}))

const { apiFetch } = await import('./api')

describe('scenarioManagerApi', () => {
  beforeEach(() => {
    ;(apiFetch as unknown as Mock).mockReset()
  })

  it('parses template ports, work maps, and topology edges', async () => {
    ;(apiFetch as unknown as Mock).mockResolvedValue({
      ok: true,
      json: async () => ({
        id: 'sample',
        name: 'Sample scenario',
        template: {
          image: 'swarm-controller:latest',
          bees: [
            {
              id: 'genA',
              role: 'generator',
              instanceId: 'gen-1',
              image: 'generator:latest',
              work: {
                out: { out: 'genQ', extra: 42 },
              },
              ports: [
                { id: 'out', direction: 'out' },
                { id: '', direction: '' },
                { id: 'audit', direction: 'out' },
              ],
              config: { inputs: { type: 'SCHEDULER' } },
            },
            {
              role: 'processor',
              instanceId: 'proc-1',
              image: 'processor:latest',
              work: {
                in: { in: 'genQ' },
                out: { out: 'finalQ' },
              },
            },
          ],
        },
        topology: {
          version: 1,
          edges: [
            {
              id: 'edge-1',
              from: { beeId: 'genA', port: 'out' },
              to: { beeId: 'procA', port: 'in' },
              selector: { policy: 'predicate', expr: 'payload.priority > 10' },
            },
          ],
        },
      }),
    })

    const scenario = await getScenario('sample')
    expect(scenario?.id).toBe('sample')
    expect(scenario?.template?.image).toBe('swarm-controller:latest')
    expect(scenario?.template?.bees.length).toBe(2)
    expect(scenario?.templateRoles).toEqual(['generator', 'processor'])

    const generator = scenario?.template?.bees[0]
    expect(generator?.id).toBe('genA')
    expect(generator?.work?.out).toEqual({ out: 'genQ' })
    expect(generator?.ports).toEqual([
      { id: 'out', direction: 'out' },
      { id: 'audit', direction: 'out' },
    ])

    const processor = scenario?.template?.bees[1]
    expect(processor?.work?.in).toEqual({ in: 'genQ' })
    expect(processor?.work?.out).toEqual({ out: 'finalQ' })

    expect(scenario?.topology?.version).toBe(1)
    expect(scenario?.topology?.edges).toEqual([
      {
        id: 'edge-1',
        from: { beeId: 'genA', port: 'out' },
        to: { beeId: 'procA', port: 'in' },
        selector: { policy: 'predicate', expr: 'payload.priority > 10' },
      },
    ])
  })
})
