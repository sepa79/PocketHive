import { useState } from 'react'
import type { Component } from '../../types/hive'
import {
  fetchWiremockComponent,
  type WiremockComponentConfig,
} from '../../lib/wiremockClient'
import WiremockStat from './WiremockStat'
import WiremockRequestsTable from './WiremockRequestsTable'
import WiremockScenarioList from './WiremockScenarioList'

interface Props {
  component: Component
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isWiremockConfig(value: unknown): value is WiremockComponentConfig {
  if (!isRecord(value)) return false
  return (
    typeof value['healthStatus'] === 'string' &&
    Array.isArray(value['recentRequests']) &&
    Array.isArray(value['unmatchedRequests']) &&
    Array.isArray(value['scenarios']) &&
    typeof value['adminUrl'] === 'string' &&
    typeof value['lastUpdatedTs'] === 'number'
  )
}

function formatRelative(timestamp?: number) {
  if (typeof timestamp !== 'number' || Number.isNaN(timestamp) || timestamp <= 0) return '—'
  const diffSec = Math.max(0, Math.floor((Date.now() - timestamp) / 1000))
  if (diffSec < 60) {
    return `${diffSec}s ago`
  }
  if (diffSec < 3600) {
    const minutes = Math.floor(diffSec / 60)
    const seconds = diffSec % 60
    return `${minutes}m ${seconds}s ago`
  }
  const hours = Math.floor(diffSec / 3600)
  const minutes = Math.floor((diffSec % 3600) / 60)
  return `${hours}h ${minutes}m ago`
}

function formatNumber(value?: number) {
  if (typeof value !== 'number' || Number.isNaN(value)) return '—'
  return value.toLocaleString()
}

export default function WiremockPanel({ component }: Props) {
  const [config, setConfig] = useState<WiremockComponentConfig | null>(() =>
    isWiremockConfig(component.config) ? component.config : null,
  )
  const [refreshing, setRefreshing] = useState(false)
  const [refreshError, setRefreshError] = useState<string | null>(null)

  const handleRefresh = async () => {
    setRefreshing(true)
    setRefreshError(null)
    try {
      const latest = await fetchWiremockComponent()
      const latestConfig = latest?.config
      if (isWiremockConfig(latestConfig)) {
        setConfig(latestConfig)
      } else {
        setRefreshError('WireMock snapshot unavailable.')
      }
    } catch (error) {
      setRefreshError('Unable to refresh WireMock metrics.')
    } finally {
      setRefreshing(false)
    }
  }

  if (!config) {
    return (
      <div className="rounded border border-white/10 bg-white/5 p-4 text-sm text-white/60">
        Unable to display WireMock metrics.
      </div>
    )
  }

  const healthLabel = config.healthStatus || component.status || 'UNKNOWN'
  const versionLabel = config.version ?? '—'
  const stubCountLabel = config.stubCountError ? '—' : formatNumber(config.stubCount)
  const unmatchedLabel = formatNumber(config.unmatchedCount)
  const heartbeatLabel = formatRelative(component.lastHeartbeat)
  const lastUpdatedLabel = formatRelative(config.lastUpdatedTs)

  const formatTimestamp = (timestamp: number) => formatRelative(timestamp)

  return (
    <div className="space-y-4" data-testid="wiremock-panel">
      <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-white/70">
        <div>Updated {lastUpdatedLabel}</div>
        <div className="flex items-center gap-3">
          <a
            href={config.adminUrl}
            target="_blank"
            rel="noopener"
            className="text-blue-300 hover:text-blue-200"
          >
            Open admin
          </a>
          <button
            className="rounded bg-blue-600 px-3 py-1 text-xs text-white disabled:bg-blue-600/40"
            onClick={handleRefresh}
            disabled={refreshing}
            aria-busy={refreshing}
          >
            {refreshing ? 'Refreshing…' : 'Retry'}
          </button>
        </div>
      </div>
      {refreshError ? (
        <div className="text-xs text-red-400">{refreshError}</div>
      ) : null}
      <div className="grid gap-3 sm:grid-cols-2">
        <WiremockStat label="Health" value={healthLabel} />
        <WiremockStat label="Version" value={versionLabel} />
        <WiremockStat label="Stub count" value={stubCountLabel} error={config.stubCountError} />
        <WiremockStat
          label="Unmatched total"
          value={unmatchedLabel}
          error={config.unmatchedRequestsError}
        />
        <WiremockStat label="Last heartbeat" value={heartbeatLabel} />
      </div>
      <WiremockRequestsTable
        title="Recent requests"
        requests={config.recentRequests}
        error={config.recentRequestsError}
        emptyMessage="No recent requests."
        formatTimestamp={formatTimestamp}
      />
      <WiremockRequestsTable
        title="Unmatched requests"
        requests={config.unmatchedRequests}
        error={config.unmatchedRequestsError}
        emptyMessage="No unmatched requests."
        formatTimestamp={formatTimestamp}
      />
      <WiremockScenarioList scenarios={config.scenarios} error={config.scenariosError} />
    </div>
  )
}
