import { useMemo, useState } from 'react'
import { MetricCard, type MetricDefinition } from '../components/metrics/MetricCard'
import { usePrometheusLabelValues } from '../lib/prometheus'

const METRICS: MetricDefinition[] = [
  {
    title: 'Processor Call Rate',
    query: 'sum by (ph_swarm)(rate(ph_processor_calls_total[5m]))',
    unit: 'ops/s',
    description: 'Requests handled by processors over a 5 minute rate window.',
  },
  {
    title: 'Processor Error Rate',
    query: 'sum by (ph_swarm)(rate(ph_errors_total[5m]))',
    unit: 'errors/s',
    description: 'Failures reported by processor components.',
  },
  {
    title: 'Processor Latency p50',
    query:
      'histogram_quantile(0.5, sum by (le, ph_swarm)(rate(ph_processor_latency_ms_bucket[5m])))',
    unit: 'ms',
    description: 'Median processor latency derived from histogram buckets.',
  },
  {
    title: 'Processor Latency p95',
    query:
      'histogram_quantile(0.95, sum by (le, ph_swarm)(rate(ph_processor_latency_ms_bucket[5m])))',
    unit: 'ms',
  },
  {
    title: 'Processor Latency p99',
    query:
      'histogram_quantile(0.99, sum by (le, ph_swarm)(rate(ph_processor_latency_ms_bucket[5m])))',
    unit: 'ms',
  },
  {
    title: 'Swarm Queue Depth',
    query: 'max by (ph_swarm)(ph_swarm_queue_depth)',
    unit: 'messages',
    description: 'Current backlog of pending jobs for each swarm.',
  },
]

export default function Nectar() {
  const [selectedSwarm, setSelectedSwarm] = useState<string>('')
  const {
    data: swarmNames = [],
    isPending: swarmsLoading,
  } = usePrometheusLabelValues('ph_processor_calls_total', 'ph_swarm')

  const filter = useMemo(() => {
    return selectedSwarm ? { ph_swarm: selectedSwarm } : undefined
  }, [selectedSwarm])

  return (
    <div className="mt-6 flex h-full flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="kpi-title text-2xl font-semibold">Nectar</h2>
          <p className="text-sm text-neutral-400">
            Real-time Prometheus metrics from Hive components.
          </p>
        </div>
        <div className="flex items-center gap-2 text-sm text-neutral-200">
          <label htmlFor="swarm-filter" className="text-neutral-300">
            Swarm
          </label>
          <select
            id="swarm-filter"
            value={selectedSwarm}
            disabled={swarmsLoading}
            onChange={(event) => setSelectedSwarm(event.target.value)}
            className="rounded border border-slate-600 bg-slate-900 px-3 py-1 text-sm text-neutral-100"
          >
            <option value="">All swarms</option>
            {swarmNames.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="grid flex-1 grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        {METRICS.map((metric) => (
          <MetricCard key={metric.title} definition={metric} filters={filter} />
        ))}
      </div>
    </div>
  )
}
