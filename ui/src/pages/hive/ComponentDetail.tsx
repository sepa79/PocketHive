import { useEffect, useState } from 'react'
import type { Component } from '../../types/hive'
import { sendConfigUpdate, requestStatus } from '../../lib/stompClient'
import QueuesPanel from './QueuesPanel'
import { heartbeatHealth, colorForHealth } from '../../lib/health'

interface Props {
  component: Component
  onClose: () => void
}

export default function ComponentDetail({ component, onClose }: Props) {
  const [toast, setToast] = useState<string | null>(null)
  const [form, setForm] = useState<Record<string, any>>({})

  useEffect(() => {
    const cfg = component.config || {}
    const init: Record<string, any> = { ...cfg }
    if (cfg.headers && typeof cfg.headers === 'object') {
      try {
        init.headers = JSON.stringify(cfg.headers, null, 2)
      } catch {
        init.headers = ''
      }
    }
    setForm(init)
  }, [component.id, component.config])

  const handleSubmit = async () => {
    const cfg: any = {}
    switch (component.name) {
      case 'generator':
        if (form.ratePerSec) cfg.ratePerSec = Number(form.ratePerSec)
        if (form.path) cfg.path = form.path
        if (form.method) cfg.method = form.method
        if (form.body) cfg.body = form.body
        if (form.headers) {
          try {
            cfg.headers = JSON.parse(form.headers)
          } catch {}
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
          } catch {}
        }
        break
      default:
        break
    }
    try {
      await sendConfigUpdate(component.id, cfg)
      await requestStatus(component.id)
      setToast('Config update sent')
    } catch {
      setToast('Config update failed')
    }
    setTimeout(() => setToast(null), 3000)
  }

  const single = async () => {
    try {
      await sendConfigUpdate(component.id, { singleRequest: true })
      await requestStatus(component.id)
      setToast('Config update sent')
    } catch {
      setToast('Config update failed')
    }
    setTimeout(() => setToast(null), 3000)
  }

  const health = heartbeatHealth(component.lastHeartbeat)

  return (
    <div className="flex-1 p-4 overflow-y-auto relative">
      <button className="absolute top-2 right-2" onClick={onClose}>
        ×
      </button>
      <h2 className="text-xl mb-2 flex items-center gap-2">
        {component.name}
        <span className={`h-3 w-3 rounded-full ${colorForHealth(health)}`} />
      </h2>
      <div className="space-y-1 text-sm mb-4">
        <div>Version: {component.version ?? '—'}</div>
        <div>Uptime: {formatSeconds(component.uptimeSec)}</div>
        <div>Last heartbeat: {timeAgo(component.lastHeartbeat)}</div>
        <div>Env: {component.env ?? '—'}</div>
        <div>Status: {component.status ?? '—'}</div>
      </div>
      <div className="p-4 border border-white/10 rounded mb-4 text-sm text-white/60 space-y-2">
        {renderForm(component.name, form, setForm, single)}
      </div>
      <button
        className="mb-4 rounded bg-blue-600 px-3 py-1 text-sm"
        onClick={handleSubmit}
      >
        Confirm
      </button>
      {toast && (
        <div className="fixed bottom-4 right-4 bg-black/80 text-white px-4 py-2 rounded">
          {toast}
        </div>
      )}
      <h3 className="text-lg mb-2">Queues</h3>
      <QueuesPanel queues={component.queues} />
    </div>
  )
}

function renderForm(
  name: string,
  form: Record<string, any>,
  setForm: (f: Record<string, any>) => void,
  single: () => void,
) {
  const input = (
    key: string,
    type: string = 'text',
    extra?: { placeholder?: string },
  ) => (
    <input
      className="w-full rounded bg-white/10 px-2 py-1"
      type={type}
      value={form[key] ?? ''}
      placeholder={extra?.placeholder}
      onChange={(e) => setForm({ ...form, [key]: e.target.value })}
    />
  )
  switch (name) {
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

