import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { apiFetch } from '../../lib/api'
import WiremockPanel from '../hive/WiremockPanel'
import { fetchWiremockComponent } from '../../lib/wiremockClient'
import type { Component } from '../../types/hive'
import {
  normalizeSutList,
  type SutEnvironment,
} from '../../lib/sutEnvironments'

interface Props {
  sutId: string
  onClose?: () => void
}

export default function SutDetailPanel({ sutId, onClose }: Props) {
  const [envs, setEnvs] = useState<SutEnvironment[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [wiremockComponent, setWiremockComponent] = useState<Component | null>(null)
  const [wiremockError, setWiremockError] = useState<string | null>(null)
  const [wiremockLoading, setWiremockLoading] = useState(false)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      setLoading(true)
      setError(null)
      try {
        const response = await apiFetch('/scenario-manager/sut-environments')
        if (!response.ok) {
          throw new Error(`HTTP ${response.status} from scenario-manager`)
        }
        let raw: unknown
        try {
          raw = (await response.json()) as unknown
        } catch {
          throw new Error('Unexpected non‑JSON response from scenario-manager (sut-environments)')
        }
        if (cancelled) return
        const normalized = normalizeSutList(raw)
        setEnvs(normalized)
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : 'Failed to load environments')
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [])

  const selected = useMemo(
    () => envs.find((e) => e.id === sutId) ?? null,
    [envs, sutId],
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
    <div className="flex-1 flex flex-col">
      <div className="flex items-center justify-between border-b border-white/10 px-6 py-4">
        <div>
          <div className="text-xs uppercase tracking-wide text-white/40">
            System under test
          </div>
          <div className="mt-1 text-sm font-semibold text-white/90 truncate">
            {selected?.name || sutId}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Link
            to={`/sut?sutId=${encodeURIComponent(sutId)}`}
            className="text-xs text-sky-300 hover:text-sky-200"
          >
            Open SUT page
          </Link>
          {onClose && (
            <button
              type="button"
              className="text-xs text-white/60 hover:text-white/90"
              onClick={onClose}
            >
              Close
            </button>
          )}
        </div>
      </div>
      <div className="flex-1 overflow-y-auto px-6 py-4 space-y-4">
        {loading && (
          <div className="text-xs text-white/60">
            Loading environments…
          </div>
        )}
        {error && (
          <div className="text-xs text-red-400">
            Failed to load: {error}
          </div>
        )}
        {!loading && !error && !selected && (
          <div className="text-xs text-white/60">
            Environment “{sutId}” not found.
          </div>
        )}
        {selected && (
          <>
            <div className="space-y-1">
              <div className="text-xs text-white/60">
                ID: <span className="font-mono text-[11px] text-white/90">{selected.id}</span>
              </div>
              {selected.type && (
                <div className="text-xs text-white/60">
                  Type: <span className="text-white/90">{selected.type}</span>
                </div>
              )}
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
                  <div className="text-xs text-white/60">Loading WireMock snapshot…</div>
                )}
                {wiremockError && (
                  <div className="text-xs text-red-400">{wiremockError}</div>
                )}
                {wiremockComponent && <WiremockPanel component={wiremockComponent} />}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
