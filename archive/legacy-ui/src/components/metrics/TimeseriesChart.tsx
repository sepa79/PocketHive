import { useMemo } from 'react'
import {
  VictoryAxis,
  VictoryChart,
  VictoryLine,
  VictoryTheme,
  VictoryTooltip,
  VictoryVoronoiContainer,
} from 'victory'
import type { PrometheusRangeSeries } from '../../lib/prometheus'

export interface TimeseriesChartProps {
  data: PrometheusRangeSeries[]
  height?: number
  unit?: string
}

interface ChartDatum {
  x: Date
  y: number
  series: string
}

const colors = ['#38bdf8', '#f97316', '#a855f7', '#22c55e', '#ef4444', '#facc15']

const chartTheme = {
  ...VictoryTheme.material,
  axis: {
    ...VictoryTheme.material.axis,
    style: {
      axis: { stroke: 'rgba(148, 163, 184, 0.4)', strokeWidth: 1 },
      grid: { stroke: 'rgba(148, 163, 184, 0.2)' },
      tickLabels: { fill: '#e2e8f0', fontSize: 10, padding: 6 },
      axisLabel: { fill: '#94a3b8', fontSize: 10, padding: 32 },
    },
  },
} as const

const formatTime = new Intl.DateTimeFormat(undefined, {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
})

function pickSeriesLabel(metric: Record<string, string>) {
  return metric.ph_swarm || metric.instance || metric.__name__ || 'series'
}

export function TimeseriesChart({ data, height = 240, unit }: TimeseriesChartProps) {
  const processed = useMemo(() => {
    return data.map((series, index) => {
      const label = pickSeriesLabel(series.metric)
      const points: ChartDatum[] = series.samples
        .filter((sample) => sample.value !== null)
        .map((sample) => ({
          x: new Date(sample.timestamp),
          y: sample.value as number,
          series: label,
        }))

      return {
        id: label,
        color: colors[index % colors.length],
        points,
      }
    })
  }, [data])

  return (
    <div style={{ position: 'relative', overflow: 'visible' }}>
      <VictoryChart
        theme={chartTheme}
        height={height}
        padding={{ top: 16, bottom: 40, left: 64, right: 24 }}
        scale={{ x: 'time', y: 'linear' }}
        containerComponent={
          <VictoryVoronoiContainer
            labels={({ datum }) => {
              const value = typeof datum.y === 'number' ? datum.y.toFixed(2) : 'N/A'
              const suffix = unit ? ` ${unit}` : ''
              return `${datum.series}\n${value}${suffix}`
            }}
            labelComponent={
              <VictoryTooltip
                constrainToVisibleArea
                flyoutStyle={{
                  fill: 'rgba(15, 23, 42, 0.95)',
                  stroke: 'rgba(148, 163, 184, 0.4)',
                  strokeWidth: 1,
                }}
                style={{ fill: '#e2e8f0', fontSize: 10 }}
              />
            }
          />
        }
      >
        <VictoryAxis tickFormat={(tick) => formatTime.format(new Date(tick))} />
        <VictoryAxis dependentAxis tickFormat={(tick) => `${tick}`} />
        {processed.map((series) => (
          <VictoryLine
            key={series.id}
            data={series.points}
            style={{ data: { stroke: series.color, strokeWidth: 2 } }}
            interpolation="monotoneX"
          />
        ))}
      </VictoryChart>
    </div>
  )
}
