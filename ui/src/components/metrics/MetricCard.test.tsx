import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { ReactElement } from 'react'
import '@testing-library/jest-dom/vitest'

const timeseriesStub = vi.fn(() => <div data-testid="chart" />)

vi.mock('../../lib/prometheus', () => ({
  usePrometheusRangeQuery: vi.fn(),
}))

vi.mock('./TimeseriesChart', () => ({
  TimeseriesChart: (props: unknown): ReactElement => timeseriesStub(props),
}))

const { usePrometheusRangeQuery } = await import('../../lib/prometheus')
const { MetricCard } = await import('./MetricCard')

const definition = {
  title: 'Processor Calls',
  query: 'ph_processor_calls_total',
  unit: 'ops/s',
}

describe('MetricCard', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('shows loading state while query is pending', () => {
    vi.mocked(usePrometheusRangeQuery).mockReturnValue({
      data: undefined,
      isPending: true,
      isError: false,
      error: null,
    } as never)

    render(<MetricCard definition={definition} />)
    expect(screen.getByText(/loading metrics/i)).toBeInTheDocument()
  })

  it('renders chart when data is available', () => {
    vi.mocked(usePrometheusRangeQuery).mockReturnValue({
      data: {
        series: [
          {
            metric: { swarm: 'alpha' },
            samples: [],
          },
        ],
        warnings: [],
      },
      isPending: false,
      isError: false,
      error: null,
    } as never)

    render(<MetricCard definition={definition} />)
    expect(screen.getByTestId('chart')).toBeInTheDocument()
    expect(timeseriesStub).toHaveBeenCalled()
  })

  it('renders error state', () => {
    vi.mocked(usePrometheusRangeQuery).mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: new Error('boom'),
    } as never)

    render(<MetricCard definition={definition} />)
    expect(screen.getByText(/boom/)).toBeInTheDocument()
  })
})
