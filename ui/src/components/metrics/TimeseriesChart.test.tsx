import { render, screen } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

const lineMock = vi.fn(({ data, style }: { data: unknown[]; style: unknown }) => (
  <div data-testid={`line-${(style as { data: { stroke: string } }).data.stroke}`}>{data.length}</div>
))

vi.mock('victory', () => ({
  VictoryChart: ({ children }: { children: ReactNode }) => <div data-testid="chart">{children}</div>,
  VictoryAxis: () => <div data-testid="axis" />,
  VictoryLine: lineMock,
  VictoryTooltip: () => null,
  VictoryVoronoiContainer: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  VictoryTheme: { material: { axis: {} } },
}))

const { TimeseriesChart } = await import('./TimeseriesChart')

describe('TimeseriesChart', () => {
  it('renders a line for each series', () => {
    render(
      <TimeseriesChart
        data={[
          {
            metric: { ph_swarm: 'alpha' },
            samples: [
              { timestamp: 0, rawValue: '1', value: 1 },
              { timestamp: 1000, rawValue: '2', value: 2 },
            ],
          },
          {
            metric: { ph_swarm: 'beta' },
            samples: [
              { timestamp: 0, rawValue: '3', value: 3 },
              { timestamp: 1000, rawValue: '4', value: 4 },
            ],
          },
        ]}
      />,
    )

    expect(screen.getByTestId('chart')).toBeInTheDocument()
    expect(lineMock).toHaveBeenCalledTimes(2)
    expect((lineMock.mock.calls[0][0] as { data: unknown[] }).data).toHaveLength(2)
  })
})
