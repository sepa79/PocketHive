import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import Editor from '@monaco-editor/react'
import { apiFetch } from '../../lib/api'
import WiremockPanel from '../hive/WiremockPanel'
import { fetchWiremockComponent } from '../../lib/wiremockClient'
import type { Component } from '../../types/hive'
import {
  normalizeSutList,
  type SutEnvironment,
} from '../../lib/sutEnvironments'

export default function SutEnvironmentsPage() {
  const navigate = useNavigate()
  const [envs, setEnvs] = useState<SutEnvironment[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [searchParams] = useSearchParams()
  const initialId = searchParams.get('sutId')
  const [selectedId, setSelectedId] = useState<string | null>(initialId)
  const [wiremockComponent, setWiremockComponent] = useState<Component | null>(null)
  const [wiremockError, setWiremockError] = useState<string | null>(null)
  const [wiremockLoading, setWiremockLoading] = useState(false)
  const [editing, setEditing] = useState(false)
  const [rawYaml, setRawYaml] = useState('')
  const [rawError, setRawError] = useState<string | null>(null)
  const [rawLoading, setRawLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  const loadEnvs = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const response = await apiFetch('/scenario-manager/sut-environments')
      if (!response.ok) {
        throw new Error(`HTTP ${response.status} from scenario-manager`)
      }
      const raw = (await response.json()) as unknown
      const normalized = normalizeSutList(raw)
      setEnvs(normalized)
      if (normalized.length > 0) {
        setSelectedId((current) => current ?? normalized[0].id)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load environments')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      await loadEnvs()
      if (cancelled) return
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [loadEnvs])

  const selected = useMemo(
    () => envs.find((e) => e.id === selectedId) ?? null,
    [envs, selectedId],
  )

  useEffect(() => {
    if (!selected) {
      setWiremockComponent(null)
      setWiremockError(null)
      setWiremockLoading(false)
      return
    }
    const panelId = selected.ui?.panelId?.trim()
    if (panelId !== 'wiremock') {
      setWiremockComponent(null)
      setWiremockError(null)
      setWiremockLoading(false)
      return
    }
    let cancelled = false
    const loadWiremock = async () => {
      setWiremockLoading(true)
      setWiremockError(null)
      try {
        const latest = await fetchWiremockComponent()
        if (cancelled) return
        if (latest) {
          setWiremockComponent(latest)
        } else {
          setWiremockComponent(null)
          setWiremockError('WireMock snapshot unavailable.')
        }
      } catch {
        if (!cancelled) {
          setWiremockComponent(null)
          setWiremockError('Unable to load WireMock metrics.')
        }
      } finally {
        if (!cancelled) {
          setWiremockLoading(false)
        }
      }
    }
    void loadWiremock()
    return () => {
      cancelled = true
    }
  }, [selected])

  const endpointEntries = useMemo(() => {
    if (!selected?.endpoints) return []
    return Object.entries(selected.endpoints)
      .map(([id, value]) => ({
        id,
        kind: value?.kind ?? null,
        baseUrl: value?.baseUrl ?? null,
      }))
      .sort((a, b) => a.id.localeCompare(b.id))
  }, [selected])

  return (
    <div className="flex h-full min-h-0 bg-[#05070b] text-white">
      <div className="w-72 border-r border-white/10 px-4 py-4 space-y-3">
        <div className="flex items-center justify-between">
          <h1 className="text-sm font-semibold text-white/90">Systems under test</h1>
          <div className="flex items-center gap-2">
            <button
              type="button"
              className="rounded border border-white/15 bg-white/10 px-2 py-1 text-[11px] text-white/80 hover:bg-white/20"
              onClick={async () => {
                if (editing) {
                  setEditing(false)
                  setRawError(null)
                  return
                }
                setRawLoading(true)
                setRawError(null)
                try {
                  const response = await apiFetch('/scenario-manager/sut-environments/raw')
                  if (!response.ok) {
                    throw new Error(`HTTP ${response.status} from scenario-manager`)
                  }
                  const text = await response.text()
                  setRawYaml(text)
                  setEditing(true)
                } catch (e) {
                  setRawError(
                    e instanceof Error
                      ? e.message
                      : 'Failed to load raw SUT environments',
                  )
                  setEditing(false)
                } finally {
                  setRawLoading(false)
                }
              }}
            >
              {editing ? 'Close editor' : 'Edit YAML'}
            </button>
            <button
              type="button"
              className="text-xs text-sky-300 hover:text-sky-200 transition"
              onClick={() => navigate(-1)}
            >
              Back
            </button>
          </div>
        </div>
        {loading && (
          <div className="text-xs text-white/60">Loading environments…</div>
        )}
        {error && (
          <div className="text-xs text-red-400">Failed to load: {error}</div>
        )}
        <div className="space-y-1 overflow-y-auto max-h-[calc(100vh-6rem)] pr-1">
          {envs.map((env) => {
            const isSelected = env.id === selectedId
            const endpointCount = env.endpoints
              ? Object.keys(env.endpoints).length
              : 0
            return (
              <button
                key={env.id}
                type="button"
                onClick={() => setSelectedId(env.id)}
                className={`w-full text-left rounded-md px-3 py-2 text-xs border ${
                  isSelected
                    ? 'border-sky-400 bg-sky-500/10'
                    : 'border-white/10 bg-white/5 hover:bg-white/10'
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium text-white/90">
                    {env.name || env.id}
                  </span>
                  {env.type && (
                    <span className="px-1.5 py-0.5 rounded-full bg-white/10 text-[10px] text-white/80">
                      {env.type}
                    </span>
                  )}
                </div>
                <div className="mt-0.5 text-[10px] text-white/60">
                  {env.id}
                  {endpointCount > 0 && ` • ${endpointCount} endpoint${endpointCount === 1 ? '' : 's'}`}
                </div>
              </button>
            )
          })}
          {!loading && !error && envs.length === 0 && (
            <div className="text-xs text-white/60">No environments defined.</div>
          )}
        </div>
      </div>
      <div className="flex-1 px-6 py-4 overflow-y-auto">
        {editing ? (
          <div className="flex flex-col gap-3 h-full">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-semibold text-white/90">
                Edit SUT environments (YAML)
              </h2>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  className="rounded bg-white/5 px-2 py-1 text-[11px] text-white/80 hover:bg-white/15"
                  onClick={() => {
                    const template = [
                      '# Example SUT environment',
                      '- id: new-sut-id',
                      '  name: New SUT',
                      '  type: dev',
                      '  endpoints:',
                      '    default:',
                      '      kind: HTTP',
                      "      baseUrl: http://example:8080",
                      '  ui:',
                      "    panelId: wiremock # optional; binds WireMock panel",
                      '',
                    ].join('\n')
                    setRawYaml((current) => {
                      const trimmed = current.trim()
                      if (!trimmed) {
                        return template
                      }
                      return `${current.replace(/\s*$/, '')}\n\n${template}`
                    })
                  }}
                >
                  Insert template
                </button>
                <button
                  type="button"
                  className="rounded bg-white/10 px-3 py-1 text-xs text-white/90 hover:bg-white/20 disabled:opacity-50"
                  disabled={saving || rawLoading}
                  onClick={async () => {
                    setSaving(true)
                    setRawError(null)
                    try {
                      const response = await apiFetch(
                        '/scenario-manager/sut-environments/raw',
                        {
                          method: 'PUT',
                          headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
                          body: rawYaml,
                        },
                      )
                      if (!response.ok) {
                        const msg = await response.text()
                        throw new Error(msg || `HTTP ${response.status}`)
                      }
                      await loadEnvs()
                      setEditing(false)
                    } catch (e) {
                      setRawError(
                        e instanceof Error ? e.message : 'Failed to save environments',
                      )
                    } finally {
                      setSaving(false)
                    }
                  }}
                >
                  {saving ? 'Saving…' : 'Save'}
                </button>
              </div>
            </div>
            {rawLoading && (
              <div className="text-xs text-white/60">Loading YAML…</div>
            )}
            {rawError && (
              <div className="text-xs text-red-400">Failed to load/save: {rawError}</div>
            )}
            <div className="flex-1 min-h-0 border border-white/15 rounded overflow-hidden">
              <Editor
                height="100%"
                defaultLanguage="yaml"
                theme="vs-dark"
                value={rawYaml}
                onChange={(value) => setRawYaml(value ?? '')}
                options={{
                  fontSize: 11,
                  minimap: { enabled: false },
                  scrollBeyondLastLine: false,
                  wordWrap: 'on',
                }}
              />
            </div>
          </div>
        ) : (
          <>
            {!selected && !loading && (
              <div className="text-sm text-white/70">
                Select an environment on the left to view details.
              </div>
            )}
            {selected && (
              <div className="space-y-4">
                <div>
                  <h2 className="text-base font-semibold text-white/90">
                    {selected.name || selected.id}
                  </h2>
                  <div className="mt-1 text-xs text-white/60 space-x-2">
                    <span>ID: {selected.id}</span>
                    {selected.type && <span>Type: {selected.type}</span>}
                  </div>
                </div>
                <div>
                  <h3 className="text-xs font-semibold text-white/80 mb-1.5">
                    Endpoints
                  </h3>
                  {endpointEntries.length === 0 ? (
                    <div className="text-xs text-white/60">No endpoints defined.</div>
                  ) : (
                    <table className="w-full text-xs border border-white/10 rounded-md overflow-hidden">
                      <thead className="bg-white/5 text-white/70">
                        <tr>
                          <th className="px-2 py-1 text-left font-medium">ID</th>
                          <th className="px-2 py-1 text-left font-medium">Kind</th>
                          <th className="px-2 py-1 text-left font-medium">Base URL</th>
                        </tr>
                      </thead>
                      <tbody>
                        {endpointEntries.map((ep) => (
                          <tr key={ep.id} className="border-t border-white/10">
                            <td className="px-2 py-1 align-top font-mono text-[11px] text-white/90">
                              {ep.id}
                            </td>
                            <td className="px-2 py-1 align-top text-white/80">
                              {ep.kind || '—'}
                            </td>
                            <td className="px-2 py-1 align-top font-mono text-[11px] text-sky-100 break-all">
                              {ep.baseUrl || '—'}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
                {selected.ui?.panelId?.trim() === 'wiremock' && (
                  <div>
                    <h3 className="text-xs font-semibold text-white/80 mb-1.5">
                      WireMock metrics
                    </h3>
                    {wiremockLoading && (
                      <div className="text-xs text-white/60">
                        Loading WireMock snapshot…
                      </div>
                    )}
                    {wiremockError && (
                      <div className="text-xs text-red-400">{wiremockError}</div>
                    )}
                    {wiremockComponent && <WiremockPanel component={wiremockComponent} />}
                  </div>
                )}
                <div>
                  <h3 className="text-xs font-semibold text-white/80 mb-1.5">
                    Raw environment (JSON)
                  </h3>
                  <pre className="bg-black/60 border border-white/10 rounded-md p-3 text-[11px] text-sky-100 overflow-x-auto whitespace-pre-wrap">
{JSON.stringify(selected, null, 2)}
                  </pre>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
