// @ts-nocheck
import type React from 'react'
import { Handle, Position, type NodeProps } from '@xyflow/react'
import {
  computePerfMetrics,
  type PerfNodeData,
  type PerfMetrics,
} from '../../lib/perfModel'

export interface PerfNodeUIData extends PerfNodeData {
  kind: 'in' | 'service' | 'out'
  isDb?: boolean
  metrics?: PerfMetrics
  incomingTpsGraph?: number
  outputTps?: number
  onChange?: (id: string, patch: Partial<PerfNodeData>) => void
  onRemove?: (id: string) => void
}

export function PerfNode({ id, data }: NodeProps<PerfNodeUIData>) {
  const kind = data.kind ?? 'service'

  const metrics: PerfMetrics =
    data.metrics ?? computePerfMetrics({ ...data, inputMode: 'tps' })

  const incomingTps = data.incomingTpsGraph ?? 0

  const threadsInUse =
    kind === 'service'
      ? Math.min(
          data.maxConcurrentIn,
          metrics.effectiveTps * metrics.serviceTimeSec,
        )
      : 0

  let offeredTps = 0
  if (kind === 'service') {
    offeredTps = incomingTps
  } else if (kind === 'in') {
    if (data.inputMode === 'tps') {
      offeredTps = Math.max(0, data.incomingTps)
    } else if (
      metrics.clientIdealTps !== null &&
      Number.isFinite(metrics.clientIdealTps)
    ) {
      offeredTps = metrics.clientIdealTps
    }
  }
  const errorTps =
    offeredTps > metrics.effectiveTps
      ? offeredTps - metrics.effectiveTps
      : 0
  const errorPct =
    offeredTps > 0 && errorTps > 0
      ? (errorTps / offeredTps) * 100
      : 0

  const hasFiniteCapacity =
    Number.isFinite(metrics.maxTpsOverall) && metrics.maxTpsOverall > 0

  const inboundIsBottleneck =
    hasFiniteCapacity &&
    Number.isFinite(metrics.maxTpsInbound) &&
    metrics.maxTpsInbound > 0 &&
    metrics.maxTpsInbound === metrics.maxTpsOverall &&
    (metrics.inboundStatus === 'high' || metrics.inboundStatus === 'overloaded')

  const depIsBottleneck =
    hasFiniteCapacity &&
    Number.isFinite(metrics.maxTpsDependency) &&
    metrics.maxTpsDependency > 0 &&
    metrics.maxTpsDependency === metrics.maxTpsOverall &&
    (metrics.depStatus === 'high' || metrics.depStatus === 'overloaded')

  let cardBorderColor = 'border-slate-600'
  if (metrics.inboundStatus === 'overloaded' || metrics.depStatus === 'overloaded') {
    cardBorderColor = 'border-red-500/70'
  } else if (metrics.inboundStatus === 'high' || metrics.depStatus === 'high') {
    cardBorderColor = 'border-amber-400/70'
  }

  const handleTextChange =
    (field: keyof PerfNodeData) => (event: React.ChangeEvent<HTMLInputElement>) => {
      data.onChange?.(id, { [field]: event.target.value } as Partial<PerfNodeData>)
    }

  const handleNumberChange =
    (field: keyof PerfNodeData) => (event: React.ChangeEvent<HTMLInputElement>) => {
      const raw = event.target.value
      const parsed = Number(raw)
      const value = Number.isFinite(parsed) ? parsed : 0
      data.onChange?.(id, { [field]: value } as Partial<PerfNodeData>)
    }

  const handleInputModeChange = (event: React.ChangeEvent<HTMLSelectElement>) => {
    const inputMode = event.target.value === 'concurrency' ? 'concurrency' : 'tps'
    data.onChange?.(id, { inputMode } as Partial<PerfNodeData>)
  }

  const handleTransportChange = (event: React.ChangeEvent<HTMLSelectElement>) => {
    const value = event.target.value
    const transport =
      value === 'jetty' || value === 'netty'
        ? value
        : 'tomcat'
    data.onChange?.(id, { transport } as Partial<PerfNodeData>)
  }

  const handleBooleanChange =
    (field: keyof PerfNodeData) =>
    (event: React.ChangeEvent<HTMLInputElement>) => {
      data.onChange?.(id, { [field]: event.target.checked } as Partial<PerfNodeData>)
    }

  const includeOutDeps = data.includeOutDeps ?? true
  const outboundDisabled = kind === 'service' && !includeOutDeps

  return (
    <div
      className={`rounded-md border ${cardBorderColor} bg-slate-900/95 shadow-lg text-[10px] text-slate-100 min-w-[260px] max-w-[320px]`}
    >
      <div className="flex items-start justify-between gap-1 border-b border-slate-700 px-2 py-1.5">
        <div className="flex-1">
          <input
            className="w-full rounded bg-slate-950/60 px-1 py-0.5 text-[11px] font-medium text-slate-50 border border-slate-700 focus:outline-none focus:ring-1 focus:ring-amber-400"
            value={data.name}
            onChange={handleTextChange('name')}
            placeholder="Component name"
          />
          <div className="mt-0.5 flex gap-1 text-[10px] text-slate-400 items-center">
            <span className="inline-flex items-center rounded-full bg-slate-800/80 px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wide text-slate-100">
              {kind === 'in'
                ? 'IN'
                : kind === 'out' && data.isDb
                ? 'DB'
                : kind === 'out'
                ? 'OUT'
                : 'Service'}
            </span>
            {kind === 'in' && (
              <select
                className="rounded bg-slate-950/70 px-1 py-0.5 border border-slate-700"
                value={data.inputMode}
                onChange={handleInputModeChange}
              >
                <option value="tps">TPS mode</option>
                <option value="concurrency">Concurrency mode</option>
              </select>
            )}
            {kind === 'service' && (
              <select
                className="rounded bg-slate-950/70 px-1 py-0.5 border border-slate-700"
                value={data.transport}
                onChange={handleTransportChange}
              >
                <option value="tomcat">Tomcat</option>
                <option value="jetty">Jetty (blocking)</option>
                <option value="netty">Netty (non-blocking)</option>
              </select>
            )}
          </div>
        </div>
        {(!data.isDb || kind !== 'out') && (
          <button
            type="button"
            className="ml-1 rounded-full border border-slate-600 bg-slate-900/80 px-1.5 text-[10px] text-slate-300 hover:bg-red-500/20 hover:text-red-200 hover:border-red-400"
            onClick={() => data.onRemove?.(id)}
            aria-label="Remove component"
          >
            ×
          </button>
        )}
      </div>
      <div className="px-2 py-1.5 space-y-1.5">
        <div className="grid grid-cols-2 gap-1.5">
          {kind === 'in' && (
            <label className="flex flex-col gap-0.5">
              <span className="text-[9px] text-slate-400">
                {data.inputMode === 'concurrency'
                  ? 'Client concurrency (hammer)'
                  : 'Incoming TPS'}
              </span>
              <input
                type="number"
                min={0}
                className="w-full rounded border border-slate-700 bg-slate-950/70 px-1 py-0.5 text-[10px]"
                value={
                  data.inputMode === 'concurrency'
                    ? data.clientConcurrency
                    : data.incomingTps
                }
                onChange={
                  data.inputMode === 'concurrency'
                    ? handleNumberChange('clientConcurrency')
                    : handleNumberChange('incomingTps')
                }
              />
            </label>
          )}
          {kind !== 'in' && (
            <div className="col-span-2">
              <MetricLine
                label="Incoming TPS"
                value={incomingTps}
                unit="req/s"
              />
            </div>
          )}
          {kind === 'in' &&
            data.inputMode === 'concurrency' &&
            metrics.clientIdealTps !== null && (
            <MetricLine
              label="Client ideal TPS"
              value={metrics.clientIdealTps}
              unit="req/s"
            />
          )}
          {kind === 'service' && (
            <>
              <div className="col-span-1 flex flex-col gap-0.5">
                <span className="text-[9px] font-semibold text-slate-300">
                  Inbound HTTP pool
                </span>
                <label className="flex flex-col gap-0.5">
                  <span className="text-[9px] text-slate-400">
                    Max concurrent in
                    {inboundIsBottleneck && (
                      <span
                        className="ml-1 text-[9px] font-semibold text-red-400"
                        title="Bottleneck: inbound threads are limiting capacity"
                      >
                        !
                      </span>
                    )}
                  </span>
                  <input
                    type="number"
                    min={1}
                    className="w-full rounded border border-slate-700 bg-slate-950/70 px-1 py-0.5 text-[10px]"
                    value={data.maxConcurrentIn}
                    onChange={handleNumberChange('maxConcurrentIn')}
                  />
                </label>
                <label className="flex flex-col gap-0.5">
                  <span className="text-[9px] text-slate-400">Internal latency (ms)</span>
                  <input
                    type="number"
                    min={0}
                    className="w-full rounded border border-slate-700 bg-slate-950/70 px-1 py-0.5 text-[10px]"
                    value={data.internalLatencyMs}
                    onChange={handleNumberChange('internalLatencyMs')}
                  />
                </label>
              </div>
              <div className="col-span-1 flex flex-col gap-0.5">
                <span
                  className={`text-[9px] font-semibold ${
                    outboundDisabled ? 'text-slate-500' : 'text-slate-300'
                  }`}
                >
                  Outbound HTTP pool
                </span>
                <label className="flex flex-col gap-0.5">
                  <span className="text-[9px] text-slate-400">HTTP client</span>
                  <select
                    className={`rounded bg-slate-950/70 px-1 py-0.5 border ${
                      outboundDisabled ? 'border-slate-800 text-slate-500' : 'border-slate-700'
                    }`}
                    value={data.httpClient}
                    disabled={outboundDisabled}
                    onChange={(event) =>
                      data.onChange?.(id, {
                        httpClient:
                          event.target.value === 'webclient'
                            ? 'webclient'
                            : 'httpclient',
                      } as Partial<PerfNodeData>)
                    }
                  >
                    <option value="httpclient">HttpClient (blocking)</option>
                    <option value="webclient">WebClient (reactive)</option>
                  </select>
                </label>
                <label className="flex flex-col gap-0.5">
                  <span className="text-[9px] text-slate-400">
                    Dependency pool size
                    {depIsBottleneck && (
                      <span
                        className="ml-1 text-[9px] font-semibold text-red-400"
                        title="Bottleneck: dependency pool is limiting capacity"
                      >
                        !
                      </span>
                    )}
                  </span>
                  <input
                    type="number"
                    min={1}
                    className={`w-full rounded border bg-slate-950/70 px-1 py-0.5 text-[10px] ${
                      outboundDisabled ? 'border-slate-800 text-slate-500' : 'border-slate-700'
                    }`}
                    value={data.depPool}
                    onChange={handleNumberChange('depPool')}
                    disabled={outboundDisabled}
                  />
                </label>
              </div>
              <div className="col-span-2 mt-1 flex items-center gap-1.5">
                <input
                  id={`${id}-deps-parallel`}
                  type="checkbox"
                  className={`h-3 w-3 rounded bg-slate-900 text-amber-400 ${
                    outboundDisabled ? 'border-slate-700' : 'border-slate-600'
                  }`}
                  checked={data.depsParallel}
                  onChange={handleBooleanChange('depsParallel')}
                  disabled={outboundDisabled}
                />
                <label
                  htmlFor={`${id}-deps-parallel`}
                  className={`text-[9px] cursor-pointer ${
                    outboundDisabled ? 'text-slate-500' : 'text-slate-300'
                  }`}
                >
                  Call OUT dependencies in parallel
                </label>
              </div>
              <div className="col-span-2 flex items-center gap-1.5">
                <input
                  id={`${id}-include-out-deps`}
                  type="checkbox"
                  className="h-3 w-3 rounded border-slate-600 bg-slate-900 text-amber-400"
                  checked={data.includeOutDeps ?? true}
                  onChange={handleBooleanChange('includeOutDeps')}
                />
                <label
                  htmlFor={`${id}-include-out-deps`}
                  className="text-[9px] text-slate-300 cursor-pointer"
                >
                  Include OUT dependencies in service latency
                </label>
              </div>
            </>
          )}
          {kind === 'out' && !data.isDb && (
            <label className="flex flex-col gap-0.5">
              <span className="text-[9px] text-slate-400">Dependency latency (ms)</span>
              <input
                type="number"
                min={0}
                className="w-full rounded border border-slate-700 bg-slate-950/70 px-1 py-0.5 text-[10px]"
                value={data.depLatencyMs}
                onChange={handleNumberChange('depLatencyMs')}
              />
            </label>
          )}
          {kind === 'out' && data.isDb && (
            <>
              <label className="flex flex-col gap-0.5">
                <span className="text-[9px] text-slate-400">DB latency (ms)</span>
                <input
                  type="number"
                  min={0}
                  className="w-full rounded border border-slate-700 bg-slate-950/70 px-1 py-0.5 text-[10px]"
                  value={data.depLatencyMs}
                  onChange={handleNumberChange('depLatencyMs')}
                />
              </label>
              <label className="flex flex-col gap-0.5">
                <span className="text-[9px] text-slate-400">
                  DB pool size
                  {depIsBottleneck && (
                    <span
                      className="ml-1 text-[9px] font-semibold text-red-400"
                      title="Bottleneck: DB pool is limiting capacity"
                    >
                      !
                    </span>
                  )}
                </span>
                <input
                  type="number"
                  min={1}
                  className="w-full rounded border border-slate-700 bg-slate-950/70 px-1 py-0.5 text-[10px]"
                  value={data.depPool}
                  onChange={handleNumberChange('depPool')}
                />
              </label>
            </>
          )}
          {kind === 'service' && (
            <div className="col-span-2 mt-1 flex items-center gap-1.5">
              <input
                id={`${id}-db-enabled`}
                type="checkbox"
                className="h-3 w-3 rounded border-slate-600 bg-slate-900 text-amber-400"
                checked={data.dbEnabled}
                onChange={handleBooleanChange('dbEnabled')}
              />
              <label
                htmlFor={`${id}-db-enabled`}
                className="text-[9px] text-slate-300 cursor-pointer"
              >
                Include one DB call per request
              </label>
            </div>
          )}
        </div>
        <div className="border-t border-slate-800 pt-1.5 space-y-0.5">
          <MetricLine
            label="Service time"
            value={metrics.serviceTimeMs}
            unit="ms"
            secondary={`${formatNumber(metrics.serviceTimeSec, 3)} s`}
          />
          <MetricLine
            label="Max TPS inbound"
            value={metrics.maxTpsInbound}
            unit="req/s"
          />
          <MetricLine
            label="Max TPS dependency"
            value={metrics.maxTpsDependency}
            unit="req/s"
          />
          <MetricLine
            label="Max TPS overall"
            value={metrics.maxTpsOverall}
            unit="req/s"
          />
          <MetricLine
            label="Effective TPS"
            value={metrics.effectiveTps}
            unit="req/s"
          />
          {kind === 'service' && (
            <MetricLine
              label="Threads in use"
              value={threadsInUse}
              secondary={`of ${data.maxConcurrentIn}`}
            />
          )}
          {errorTps > 0 && (
            <MetricLine
              label="Theoretical drop rate"
              value={errorTps}
              unit="req/s"
              secondary={`${formatNumber(errorPct, 1)}% of offered`}
            />
          )}
        </div>
        {kind === 'service' && (
          <div className="border-t border-slate-800 pt-1.5 grid grid-cols-2 gap-1.5">
            <UtilisationBadge
              label="Inbound utilisation"
              utilisation={metrics.utilInbound}
              status={metrics.inboundStatus}
            />
            <UtilisationBadge
              label="Dependency utilisation"
              utilisation={metrics.utilDep}
              status={metrics.depStatus}
            />
          </div>
        )}
      </div>
      {kind !== 'in' && (
        <Handle type="target" position={Position.Left} style={{ top: '50%' }} />
      )}
      {kind !== 'out' && (
        <Handle type="source" position={Position.Right} style={{ top: '50%' }} />
      )}
    </div>
  )
}

interface MetricLineProps {
  label: string
  value: number
  unit?: string
  secondary?: string
}

function MetricLine({ label, value, unit, secondary }: MetricLineProps) {
  return (
    <div className="flex items-baseline justify-between gap-1">
      <span className="text-[9px] text-slate-400">{label}</span>
      <div className="text-right text-[10px]">
        <span className="font-mono text-slate-50">
          {formatNumber(value, 1)}
          {unit ? <span className="text-slate-400"> {unit}</span> : null}
        </span>
        {secondary && (
          <div className="font-mono text-[9px] text-slate-500 leading-tight">
            {secondary}
          </div>
        )}
      </div>
    </div>
  )
}

interface UtilisationBadgeProps {
  label: string
  utilisation: number
  status: 'ok' | 'high' | 'overloaded'
}

function UtilisationBadge({ label, utilisation, status }: UtilisationBadgeProps) {
  const percent = utilisation * 100
  const rounded = Number.isFinite(percent) ? Math.round(percent) : 0

  let colorClasses =
    'border-emerald-500/40 bg-emerald-500/15 text-emerald-200 shadow-[0_0_0_1px_rgba(16,185,129,0.3)]'
  let labelText = 'OK'

  if (status === 'high') {
    colorClasses =
      'border-amber-400/50 bg-amber-500/20 text-amber-200 shadow-[0_0_0_1px_rgba(251,191,36,0.35)]'
    labelText = 'High'
  } else if (status === 'overloaded') {
    colorClasses =
      'border-red-500/60 bg-red-600/25 text-red-100 shadow-[0_0_0_1px_rgba(248,113,113,0.45)]'
    labelText = 'Overloaded'
  }

  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-[9px] text-slate-400">{label}</span>
      <div
        className={`inline-flex items-center justify-between gap-1 rounded border px-1.5 py-0.5 text-[9px] font-medium ${colorClasses}`}
      >
        <span>{labelText}</span>
        <span className="font-mono">{rounded}%</span>
      </div>
    </div>
  )
}

function formatNumber(value: number, fractionDigits = 1): string {
  if (!Number.isFinite(value)) return '∞'
  if (Math.abs(value) >= 1000) {
    return value.toFixed(0)
  }
  if (Math.abs(value) < 0.001) {
    return '0'
  }
  return value.toFixed(fractionDigits)
}
