import { afterEach, describe, expect, it, vi } from 'vitest'
import { validateExistingScenarioBundle } from './scenariosApi'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('validateExistingScenarioBundle', () => {
  it('preserves the Scenario Manager scenario name in the validation result', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      ok: true,
      source: 'existing-bundle',
      bundleKey: 'bundles/demo',
      bundlePath: 'bundles/demo',
      scenarioId: 'demo',
      scenarioName: 'Demo scenario',
      validation: {
        scenarioProtocolVersion: '2.0.0',
        supportedScenarioProtocolVersion: '2.0.0',
        scenarioManagerVersion: '0.15.35',
        artifactDigest: 'sha256:test',
      },
      summary: { errors: 0, warnings: 0 },
      findings: [],
    }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await validateExistingScenarioBundle('bundles/demo')

    expect(result.scenarioName).toBe('Demo scenario')
    expect(fetchMock).toHaveBeenCalledWith(
      '/scenario-manager/validation/scenario-bundles/existing?bundleKey=bundles%2Fdemo',
      { method: 'POST', headers: { Accept: 'application/json' } },
    )
  })
})
