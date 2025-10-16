/**
 * @vitest-environment jsdom
 */
import { describe, expect, it, beforeEach, vi } from 'vitest'
import { fetchWiremockComponent, type WiremockComponentConfig } from './wiremockClient'
import { apiFetch } from './api'

vi.mock('./api', () => ({
  apiFetch: vi.fn(),
}))

const apiFetchMock = vi.mocked(apiFetch)

function jsonResponse(payload: unknown, init?: ResponseInit) {
  return new Response(JSON.stringify(payload), {
    headers: { 'content-type': 'application/json' },
    ...init,
  })
}

describe('fetchWiremockComponent', () => {
  beforeEach(() => {
    apiFetchMock.mockReset()
  })

  it('returns a populated component snapshot when metrics are available', async () => {
    const responses = new Map<string, Response>([
      ['http://localhost:8080/__admin/health', jsonResponse({ status: 'OK', version: '3.1.0' })],
      ['http://localhost:8080/__admin/requests/count', jsonResponse({ count: 42 })],
      [
        'http://localhost:8080/__admin/requests?limit=25',
        jsonResponse({
          requests: [
            {
              id: 'abc',
              loggedDate: 1700000000000,
              request: { method: 'GET', url: '/path' },
              response: { status: 200 },
            },
          ],
        }),
      ],
      [
        'http://localhost:8080/__admin/requests/unmatched',
        jsonResponse({ meta: { total: 1 }, requests: [] }),
      ],
      [
        'http://localhost:8080/__admin/mappings',
        jsonResponse({ meta: { total: 5 } }),
      ],
      [
        'http://localhost:8080/__admin/scenarios',
        jsonResponse({ scenarios: [{ id: 's', name: 'Scenario', state: 'Started' }] }),
      ],
    ])

    apiFetchMock.mockImplementation(async (input: RequestInfo) => {
      const key = typeof input === 'string' ? input : input.url
      const response = responses.get(key)
      if (!response) throw new Error(`Unexpected request: ${key}`)
      return response
    })

    const component = await fetchWiremockComponent()

    expect(component).not.toBeNull()
    if (!component) throw new Error('component missing')
    expect(component.id).toBe('wiremock')
    expect(component.swarmId).toBe('default')
    expect(component.status).toBe('OK')
    expect(component.config).toEqual(
      expect.objectContaining({
        healthStatus: 'OK',
        version: '3.1.0',
        requestCount: 42,
        stubCount: 5,
        unmatchedCount: 1,
        scenarios: [expect.objectContaining({ name: 'Scenario', state: 'Started' })],
      }),
    )
  })

  it('still returns a component snapshot when all endpoints fail', async () => {
    apiFetchMock.mockResolvedValue(new Response(null, { status: 404 }))

    const component = await fetchWiremockComponent()

    expect(component).not.toBeNull()
    if (!component) throw new Error('component missing')
    expect(component.lastHeartbeat).toBe(0)
    expect(component.status).toBe('ALERT')
    const config = component.config as WiremockComponentConfig
    expect(config.healthStatus).toBe('UNKNOWN')
    expect(config.requestCount).toBeUndefined()
    expect(config.requestCountError).toBe(true)
    expect(config.stubCount).toBeUndefined()
    expect(config.stubCountError).toBe(true)
    expect(config.recentRequests).toEqual([])
    expect(config.recentRequestsError).toBe(true)
    expect(config.unmatchedRequests).toEqual([])
    expect(config.unmatchedRequestsError).toBe(true)
    expect(config.scenarios).toEqual([])
    expect(config.scenariosError).toBe(true)
  })

  it('falls back to request meta totals and loggedDateString when counts are unavailable', async () => {
    const responses = new Map<string, Response>([
      ['http://localhost:8080/__admin/health', jsonResponse({ status: 'OK' })],
      ['http://localhost:8080/__admin/requests/count', new Response(null, { status: 404 })],
      [
        'http://localhost:8080/__admin/requests?limit=25',
        jsonResponse({
          meta: { total: 96 },
          requests: [
            {
              id: 'req-1',
              loggedDateString: '2024-05-26T10:00:00Z',
              request: { method: 'POST', url: '/api/test' },
              response: { status: 201 },
            },
          ],
        }),
      ],
      ['http://localhost:8080/__admin/requests/unmatched', jsonResponse({ requests: [] })],
      ['http://localhost:8080/__admin/mappings', jsonResponse({})],
      ['http://localhost:8080/__admin/scenarios', jsonResponse({ scenarios: [] })],
    ])

    apiFetchMock.mockImplementation(async (input: RequestInfo) => {
      const key = typeof input === 'string' ? input : input.url
      const response = responses.get(key)
      if (!response) throw new Error(`Unexpected request: ${key}`)
      return response
    })

    const component = await fetchWiremockComponent()

    expect(component).not.toBeNull()
    if (!component) throw new Error('component missing')
    const config = component.config as WiremockComponentConfig
    expect(config.requestCount).toBe(96)
    expect(config.requestCountError).toBeUndefined()
    expect(config.recentRequests[0]).toEqual(
      expect.objectContaining({
        id: 'req-1',
        loggedDate: expect.any(Number),
      }),
    )
    expect(config.recentRequests[0]?.loggedDate).toBe(Date.parse('2024-05-26T10:00:00Z'))
  })
})

