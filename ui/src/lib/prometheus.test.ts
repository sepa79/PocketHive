import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'

vi.mock('./api', () => ({
  apiFetch: vi.fn(),
}))

const { setConfig } = await import('./config')
const apiModule = await import('./api')
const { prometheusInstantQuery, prometheusRangeQuery, prometheusSeries } = await import('./prometheus')

describe('prometheus helpers', () => {
  let apiFetchSpy: MockInstance<typeof apiModule.apiFetch>

  beforeEach(() => {
    setConfig({ prometheus: 'http://prom.test/' })
    apiFetchSpy = vi.spyOn(apiModule, 'apiFetch')
  })

  afterEach(() => {
    apiFetchSpy.mockRestore()
  })

  it('builds range query URLs and normalises samples', async () => {
    apiFetchSpy.mockResolvedValue(
      new Response(
        JSON.stringify({
          status: 'success',
          data: {
            resultType: 'matrix',
            result: [
              {
                metric: { __name__: 'ph_metric_total' },
                values: [
                  [1, '1.5'],
                  ['2', 'NaN'],
                ],
              },
            ],
          },
        }),
      ) as unknown as Response,
    )

    const result = await prometheusRangeQuery({
      query: 'ph_metric_total',
      start: new Date(1_000),
      end: new Date(31_000),
      stepSeconds: 30,
    })

    expect(apiFetchSpy).toHaveBeenCalledWith(
      'http://prom.test/api/v1/query_range?query=ph_metric_total&step=30&start=1&end=31',
      { cache: 'no-store' },
    )
    expect(result.series).toHaveLength(1)
    expect(result.series[0].samples).toEqual([
      { timestamp: 1000, rawValue: '1.5', value: 1.5 },
      { timestamp: 2000, rawValue: 'NaN', value: null },
    ])
  })

  it('injects label matchers into expressions with aggregations', async () => {
    apiFetchSpy.mockResolvedValue(
      new Response(
        JSON.stringify({
          status: 'success',
          data: {
            resultType: 'matrix',
            result: [],
          },
        }),
      ) as unknown as Response,
    )

    await prometheusRangeQuery({
      query: 'sum(rate(ph_processor_calls_total[5m]))',
      start: 0,
      end: 60,
      labelMatchers: { ph_swarm: 'alpha' },
    })

    const calledUrl = new URL(apiFetchSpy.mock.calls[0][0] as string)
    expect(calledUrl.searchParams.get('query')).toBe(
      'sum(rate(ph_processor_calls_total{ph_swarm="alpha"}[5m]))',
    )
  })

  it('reads instant vector and scalar queries', async () => {
    apiFetchSpy
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            status: 'success',
            data: {
              resultType: 'vector',
              result: [
                { metric: { swarm: 'alpha' }, value: [10, '2'] },
              ],
            },
          }),
        ) as unknown as Response,
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            status: 'success',
            data: {
              resultType: 'scalar',
              result: [20, '3.5'],
            },
          }),
        ) as unknown as Response,
      )

    const vector = await prometheusInstantQuery({ query: 'ph_metric_total' })
    expect(vector.resultType).toBe('vector')
    expect(vector.vector?.[0].sample.value).toBe(2)

    const scalar = await prometheusInstantQuery({ query: 'ph_metric_total' })
    expect(scalar.resultType).toBe('scalar')
    expect(scalar.scalar?.sample.timestamp).toBe(20_000)
  })

  it('fetches series and returns label sets', async () => {
    apiFetchSpy.mockResolvedValue(
      new Response(
        JSON.stringify({
          status: 'success',
          data: [
            { ph_swarm: 'alpha', instance: 'a' },
            { ph_swarm: 'beta', instance: 'b' },
          ],
        }),
      ) as unknown as Response,
    )

    const result = await prometheusSeries({ metric: 'ph_metric_total', start: 0, end: 60 })
    expect(apiFetchSpy).toHaveBeenCalledWith(
      'http://prom.test/api/v1/series?match%5B%5D=ph_metric_total&start=0&end=60',
      { cache: 'no-store' },
    )
    expect(result.series).toHaveLength(2)
  })
})
