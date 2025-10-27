import type { JSX } from 'react'
import type { Component } from '../../types/hive'
import QueuesPanel from './QueuesPanel'
import { heartbeatHealth, colorForHealth } from '../../lib/health'
import WiremockPanel from './WiremockPanel'
import WorkerCapabilitiesPanel from './WorkerCapabilitiesPanel'

interface Props {
  component: Component
  onClose: () => void
}

export default function ComponentDetail({ component, onClose }: Props) {
  const health = heartbeatHealth(component.lastHeartbeat)
  const role = component.role.trim() || '—'
  const normalizedRole = component.role.trim().toLowerCase()
  const isWiremock = normalizedRole === 'wiremock'

  const renderedContent = renderContent(component)
  const containerClass = isWiremock
    ? 'mb-4'
    : 'p-4 border border-white/10 rounded mb-4 text-sm text-white/60 space-y-2'

  return (
    <div className="flex-1 p-4 overflow-y-auto relative">
      <button className="absolute top-2 right-2" onClick={onClose}>
        ×
      </button>
      <h2 className="text-xl mb-1 flex items-center gap-2">
        {component.id}
        <span className={`h-3 w-3 rounded-full ${colorForHealth(health)}`} />
        {/* status refresh no longer supported */}
      </h2>
      <div className="text-sm text-white/60 mb-3">{role}</div>
      <div className="space-y-1 text-sm mb-4">
        <div>Version: {component.version ?? '—'}</div>
        <div>Uptime: {formatSeconds(component.uptimeSec)}</div>
        <div>Last heartbeat: {timeAgo(component.lastHeartbeat)}</div>
        <div>Env: {component.env ?? '—'}</div>
        <div>
          Enabled:{' '}
          {component.config?.enabled === false
            ? 'false'
            : component.config?.enabled === true
            ? 'true'
            : '—'}
        </div>
      </div>
      <div className={containerClass}>{renderedContent}</div>
      {!isWiremock && (
        <>
          <h3 className="text-lg mb-2">Queues</h3>
          <QueuesPanel queues={component.queues} />
        </>
      )}
    </div>
  )
}

function renderContent(component: Component): JSX.Element {
  const role = component.role?.trim().toLowerCase()
  switch (role) {
    case 'wiremock':
      return <WiremockPanel component={component} />
    default:
      return <WorkerCapabilitiesPanel component={component} />
  }
}

function formatSeconds(sec?: number) {
  if (sec == null) return '—'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  return `${h}h ${m}m ${s}s`
}

function timeAgo(ts: number) {
  const diff = Math.floor((Date.now() - ts) / 1000)
  return `${diff}s ago`
}

