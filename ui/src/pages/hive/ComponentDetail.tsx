import { useEffect, useState, type JSX } from 'react'
import type { Component } from '../../types/hive'
import { sendConfigUpdate } from '../../lib/orchestratorApi'
import QueuesPanel from './QueuesPanel'
import { heartbeatHealth, colorForHealth } from '../../lib/health'
import WiremockPanel from './WiremockPanel'

interface Props {
  component: Component
  onClose: () => void
}

export default function ComponentDetail({ component, onClose }: Props) {
  const [toast, setToast] = useState<string | null>(null)
  const [form, setForm] = useState<Record<string, string>>({})

  useEffect(() => {
    const cfg = component.config || {}
    const init: Record<string, string> = {}
    if (cfg && typeof cfg === 'object') {
      Object.entries(cfg as Record<string, unknown>).forEach(([key, value]) => {
        if (key === 'headers' || key === 'enabled') return
        if (value === undefined || value === null) return
        if (typeof value === 'string') {
          init[key] = value
        } else if (typeof value === 'number' || typeof value === 'boolean') {
          init[key] = String(value)
        }
      })
      const headersValue = (cfg as Record<string, unknown>).headers
      if (typeof headersValue === 'string') {
        init.headers = headersValue
      } else if (headersValue && typeof headersValue === 'object') {
        try {
          init.headers = JSON.stringify(headersValue, null, 2)
        } catch {
          init.headers = ''
        }
      }
    }
    setForm(init)
  }, [component.id, component.config])

  const handleSubmit = async () => {
    const cfg: Record<string, unknown> = {}
    switch (component.role) {
      case 'generator':
        if (form.ratePerSec) cfg.ratePerSec = Number(form.ratePerSec)
        if (form.path) cfg.path = form.path
        if (form.method) cfg.method = form.method
        if (form.body) cfg.body = form.body
        if (form.headers) {
          try {
            cfg.headers = JSON.parse(form.headers)
          } catch {
            cfg.headers = undefined
          }
        }
        break
      case 'processor':
        if (form.baseUrl) cfg.baseUrl = form.baseUrl
        break
      case 'trigger':
        if (form.intervalMs) cfg.intervalMs = Number(form.intervalMs)
        if (form.actionType) cfg.actionType = form.actionType
        if (form.command) cfg.command = form.command
        if (form.url) cfg.url = form.url
        if (form.method) cfg.method = form.method
        if (form.body) cfg.body = form.body
        if (form.headers) {
          try {
            cfg.headers = JSON.parse(form.headers)
          } catch {
            cfg.headers = undefined
          }
        }
        break
      default:
        break
    }
    try {
      await sendConfigUpdate(component, cfg)
      setToast('Config update sent')
    } catch {
      setToast('Config update failed')
    }
    setTimeout(() => setToast(null), 3000)
  }

  const single = async () => {
    try {
      await sendConfigUpdate(component, { singleRequest: true })
      setToast('Config update sent')
    } catch {
      setToast('Config update failed')
    }
    setTimeout(() => setToast(null), 3000)
  }

  const health = heartbeatHealth(component.lastHeartbeat)
  const role = component.role.trim() || '—'
  const normalizedRole = component.role.trim().toLowerCase()
  const isWiremock = normalizedRole === 'wiremock'

  const renderedContent = renderForm(component, form, setForm, single)
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
        <button
          className="mb-4 rounded bg-blue-600 px-3 py-1 text-sm"
          onClick={handleSubmit}
        >
          Confirm
        </button>
      )}
      {toast && (
        <div className="fixed bottom-4 right-4 bg-black/80 text-white px-4 py-2 rounded">
          {toast}
        </div>
      )}
      {!isWiremock && (
        <>
          <h3 className="text-lg mb-2">Queues</h3>
          <QueuesPanel queues={component.queues} />
        </>
      )}
    </div>
  )
}

function renderForm(
  component: Component,
  form: Record<string, string>,
  setForm: (f: Record<string, string>) => void,
  single: () => void,
) {
  const role = component.role?.trim().toLowerCase()
  const input = (
    key: string,
    type: string = 'text',
    extra?: { placeholder?: string },
  ): JSX.Element => (
    <input
      className="w-full rounded bg-white/10 px-2 py-1"
      type={type}
      value={form[key] ?? ''}
      placeholder={extra?.placeholder}
      onChange={(e) => setForm({ ...form, [key]: e.target.value })}
    />
  )
  switch (role) {
    case 'wiremock':
      return <WiremockPanel component={component} />
    case 'generator':
      return (
        <div className="space-y-2">
          <label className="block">Rate/sec {input('ratePerSec', 'number')}</label>
          <label className="block">Path {input('path')}</label>
          <label className="block">Method {input('method')}</label>
          <label className="block">
            Body
            <textarea
              className="w-full rounded bg-white/10 px-2 py-1"
              value={form.body ?? ''}
              onChange={(e) => setForm({ ...form, body: e.target.value })}
            />
          </label>
          <label className="block">
            Headers (JSON)
            <textarea
              className="w-full rounded bg-white/10 px-2 py-1"
              value={form.headers ?? ''}
              onChange={(e) => setForm({ ...form, headers: e.target.value })}
            />
          </label>
          <button
            className="rounded bg-blue-700 px-2 py-1 text-xs"
            onClick={single}
          >
            Single request
          </button>
        </div>
      )
    case 'processor':
      return (
        <div className="space-y-2">
          <label className="block">Base URL {input('baseUrl')}</label>
        </div>
      )
    case 'trigger':
      return (
        <div className="space-y-2">
          <label className="block">Interval ms {input('intervalMs', 'number')}</label>
          <label className="block">Action type {input('actionType')}</label>
          <label className="block">Command {input('command')}</label>
          <label className="block">URL {input('url')}</label>
          <label className="block">Method {input('method')}</label>
          <label className="block">
            Body
            <textarea
              className="w-full rounded bg-white/10 px-2 py-1"
              value={form.body ?? ''}
              onChange={(e) => setForm({ ...form, body: e.target.value })}
            />
          </label>
          <label className="block">
            Headers (JSON)
            <textarea
              className="w-full rounded bg-white/10 px-2 py-1"
              value={form.headers ?? ''}
              onChange={(e) => setForm({ ...form, headers: e.target.value })}
            />
          </label>
          <button
            className="rounded bg-blue-700 px-2 py-1 text-xs"
            onClick={single}
          >
            Single trigger
          </button>
        </div>
      )
    default:
      return <div>No additional settings</div>
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

